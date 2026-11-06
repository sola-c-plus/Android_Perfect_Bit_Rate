package com.example.perfectbitrate

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

// ★ Local Music の楽曲インデックス（仕様書 §6）。
//   取得経路は2つ:
//   - MediaStore（AppPreferences.useMediaStore が ON のとき）
//   - SAF ツリー（AppPreferences.safFolderUris のフォルダ配下を再帰走査）
//   メタデータは機種差を避けるため FFmpeg プローブで統一する（§6.2）。
//   キャッシュは「URI + サイズ + 更新日時」をキーにしたメモリ内キャッシュで、
//   変わったファイルだけを再プローブする（DB 永続化は Phase 2 で検討）。
//
//   loadTracks はブロッキング処理（プローブ含む）を自前スレッドで実行し、
//   結果をコールバックで返す。UI スレッドからは呼ばない前提の内部実装だが、
//   呼び出し自体はどのスレッドからでも安全。
class LibraryRepository(private val context: Context) {

    data class Track(
        val uri: Uri,
        val displayName: String,  // ファイル名（タグが無い場合の表示・ソート用）
        val title: String,
        val artist: String,
        val album: String,
        val durationMs: Long,
        val sizeBytes: Long,
        val modifiedMs: Long,
        val sampleRate: Int,
        val bitDepth: Int,
        val codecName: String,
        val isDsd: Boolean,
        val source: Source
    ) {
        enum class Source { MEDIASTORE, SAF }
    }

    private data class RawEntry(
        val uri: Uri,
        val displayName: String,
        val sizeBytes: Long,
        val modifiedMs: Long,
        val source: Track.Source,
        // MediaStore 由来の補助メタデータ（FFmpeg プローブ失敗時のフォールバック用）
        val fallbackTitle: String?,
        val fallbackArtist: String?,
        val fallbackAlbum: String?,
        val fallbackDurationMs: Long
    )

    private data class CacheKey(val uri: String, val sizeBytes: Long, val modifiedMs: Long)

    private val probeCache = ConcurrentHashMap<CacheKey, Track>()
    private val executor = Executors.newFixedThreadPool(3)

    fun hasAudioPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context, Manifest.permission.READ_MEDIA_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    // ★ 楽曲一覧を取得する。結果はタイトル順（次にアーティスト順）で返す。
    //   onResult はワーカースレッドで呼ばれるため、UI 反映は呼び出し側で
    //   メインスレッドへ載せ替えること。
    fun loadTracks(onResult: (List<Track>) -> Unit) {
        executor.execute {
            val result = try {
                loadTracksBlocking()
            } catch (e: Exception) {
                Log.w(TAG, "loadTracks failed", e)
                emptyList()
            }
            onResult(result)
        }
    }

    private fun loadTracksBlocking(): List<Track> {
        val prefs = AppPreferences.get()
        val rawEntries = mutableListOf<RawEntry>()

        if (prefs.useMediaStore && hasAudioPermission()) {
            rawEntries += queryMediaStore()
        }
        val safUris = prefs.safFolderUris
        if (safUris.isNotEmpty()) {
            rawEntries += scanSafFolders(safUris)
        }

        val dsdRate = prefs.dsdPcmRateHz
        val tracks = rawEntries.map { entry ->
            val key = CacheKey(entry.uri.toString(), entry.sizeBytes, entry.modifiedMs)
            probeCache[key] ?: buildTrack(entry, dsdRate).also { probeCache[key] = it }
        }
        // キャッシュの肥大化防止: 現存しないキーを落とす
        val liveKeys = rawEntries.map { CacheKey(it.uri.toString(), it.sizeBytes, it.modifiedMs) }.toSet()
        probeCache.keys.retainAll(liveKeys)

        return tracks.sortedWith(
            compareBy<Track> { it.title.lowercase(Locale.ROOT) }
                .thenBy { it.artist.lowercase(Locale.ROOT) }
                .thenBy { it.displayName.lowercase(Locale.ROOT) }
        )
    }

    private fun buildTrack(entry: RawEntry, dsdRate: Int): Track {
        val info = if (FfmpegDecoder.isAvailable) {
            FfmpegDecoder.probe(context, entry.uri, dsdRate)
        } else {
            null
        }
        val fileTitle = entry.displayName.substringBeforeLast('.', entry.displayName)
        return Track(
            uri = entry.uri,
            displayName = entry.displayName,
            title = info?.title?.takeIf { it.isNotBlank() }
                ?: entry.fallbackTitle?.takeIf { it.isNotBlank() }
                ?: fileTitle,
            artist = info?.artist?.takeIf { it.isNotBlank() }
                ?: entry.fallbackArtist ?: "",
            album = info?.album?.takeIf { it.isNotBlank() }
                ?: entry.fallbackAlbum ?: "",
            durationMs = info?.durationMs?.takeIf { it > 0 }
                ?: entry.fallbackDurationMs,
            sizeBytes = entry.sizeBytes,
            modifiedMs = entry.modifiedMs,
            sampleRate = info?.sampleRate ?: 0,
            bitDepth = info?.bitDepth ?: 0,
            codecName = info?.codecName ?: "",
            isDsd = info?.isDsd ?: false,
            source = entry.source
        )
    }

    // ★ 再生中トラックのアートワーク取得（ブロッキング、呼び出し側スレッドで実行）。
    fun loadArtwork(track: Track): Bitmap? {
        if (!FfmpegDecoder.isAvailable) return null
        val decoder = FfmpegDecoder(context)
        return try {
            if (decoder.openUri(track.uri, AppPreferences.get().dsdPcmRateHz)) {
                decoder.artwork()
            } else {
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "loadArtwork failed", e)
            null
        } finally {
            decoder.release()
        }
    }

    // ── MediaStore ──

    private fun queryMediaStore(): List<RawEntry> {
        val entries = mutableListOf<RawEntry>()
        val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.SIZE,
            MediaStore.Audio.Media.DATE_MODIFIED,
            MediaStore.Audio.Media.MIME_TYPE
        )
        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0"
        try {
            context.contentResolver.query(collection, projection, selection, null, null)?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
                val titleCol = cursor.getColumnIndex(MediaStore.Audio.Media.TITLE)
                val artistCol = cursor.getColumnIndex(MediaStore.Audio.Media.ARTIST)
                val albumCol = cursor.getColumnIndex(MediaStore.Audio.Media.ALBUM)
                val durationCol = cursor.getColumnIndex(MediaStore.Audio.Media.DURATION)
                val sizeCol = cursor.getColumnIndex(MediaStore.Audio.Media.SIZE)
                val modifiedCol = cursor.getColumnIndex(MediaStore.Audio.Media.DATE_MODIFIED)
                while (cursor.moveToNext()) {
                    val name = cursor.getString(nameCol) ?: continue
                    if (!isSupportedExtension(name)) continue
                    val id = cursor.getLong(idCol)
                    val uri = Uri.withAppendedPath(collection, id.toString())
                    entries += RawEntry(
                        uri = uri,
                        displayName = name,
                        sizeBytes = if (sizeCol >= 0) cursor.getLong(sizeCol) else -1L,
                        modifiedMs = if (modifiedCol >= 0) cursor.getLong(modifiedCol) * 1000L else 0L,
                        source = Track.Source.MEDIASTORE,
                        fallbackTitle = if (titleCol >= 0) cursor.getString(titleCol) else null,
                        fallbackArtist = if (artistCol >= 0) cursor.getString(artistCol) else null,
                        fallbackAlbum = if (albumCol >= 0) cursor.getString(albumCol) else null,
                        fallbackDurationMs = if (durationCol >= 0) cursor.getLong(durationCol) else 0L
                    )
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "queryMediaStore failed", e)
        }
        return entries
    }

    // ── SAF ──

    private fun scanSafFolders(treeUriStrings: Set<String>): List<RawEntry> {
        val entries = mutableListOf<RawEntry>()
        for (treeUriString in treeUriStrings) {
            try {
                val treeUri = Uri.parse(treeUriString)
                val root = DocumentFile.fromTreeUri(context, treeUri) ?: continue
                if (!root.exists()) continue
                scanDocumentTree(root, entries, depth = 0)
            } catch (e: Exception) {
                Log.w(TAG, "scanSafFolders failed for $treeUriString", e)
            }
        }
        return entries
    }

    private fun scanDocumentTree(dir: DocumentFile, out: MutableList<RawEntry>, depth: Int) {
        if (depth > MAX_SAF_DEPTH) return
        val children = try {
            dir.listFiles()
        } catch (e: Exception) {
            return
        }
        for (child in children) {
            if (child.isDirectory) {
                scanDocumentTree(child, out, depth + 1)
            } else if (child.isFile) {
                val name = child.name ?: continue
                if (!isSupportedExtension(name)) continue
                out += RawEntry(
                    uri = child.uri,
                    displayName = name,
                    sizeBytes = try { child.length() } catch (e: Exception) { -1L },
                    modifiedMs = try { child.lastModified() } catch (e: Exception) { 0L },
                    source = Track.Source.SAF,
                    fallbackTitle = null,
                    fallbackArtist = null,
                    fallbackAlbum = null,
                    fallbackDurationMs = 0L
                )
            }
        }
    }

    fun shutdown() {
        executor.shutdown()
    }

    companion object {
        private const val TAG = "LibraryRepository"
        private const val MAX_SAF_DEPTH = 8

        // 対応拡張子は §5.2 のデコーダ有効範囲に合わせる
        private val SUPPORTED_EXTENSIONS = setOf(
            "flac", "m4a", "mp3", "aac", "ogg", "oga", "opus",
            "wav", "aif", "aiff", "dsf", "dff"
        )

        fun isSupportedExtension(fileName: String): Boolean {
            val ext = fileName.substringAfterLast('.', "").lowercase(Locale.ROOT)
            return ext in SUPPORTED_EXTENSIONS
        }
    }
}
