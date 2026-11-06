package com.example.perfectbitrate

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.SystemClock
import android.provider.DocumentsContract
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
//
//   プローブ結果はアプリ内 DB（LibraryCacheDb）に永続化する。次回からは
//   永続化済みキャッシュを即表示し（loadCachedTracks）、裏で差分スキャンを
//   回す（refreshLibrary）。差分判定は「URI + サイズ + 更新日時」で、
//   変わっていないファイルは再プローブしない。新規/変更ファイルだけを
//   プローブし、消えたファイルは一覧と DB から除く。メモリ内キャッシュも
//   併用し、同一セッション内の再スキャンを速くする。
//   強制フルリフレッシュ（全件再プローブ）は refreshLibrary(forceFull=true)。
//
//   各 API はブロッキング処理を自前スレッドで実行し、結果をコールバックで
//   返す。コールバックはワーカースレッドで呼ばれるため、UI 反映は
//   呼び出し側でメインスレッドへ載せ替えること。
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
        val source: Source,
        // ★ 実ファイルの同一性キー（「ボリューム:相対パス」形式に正規化）。
        //   MediaStore と SAF では同じファイルでも URI が別物になるため、
        //   URI では重複を弾けない。取得できた経路ではこのキーで同一視し、
        //   取れない場合は（表示名 + サイズ + 長さ）の複合キーに落とす
        //   （identityKeyOf 参照）。DB にも保存し、キャッシュ表示の段階でも
        //   重複を畳めるようにする。
        val fileKey: String? = null
    ) {
        enum class Source { MEDIASTORE, SAF }
    }

    // ★ スキャン進捗: フェーズ（走査中 / メタデータ取得中）、処理済み件数、
    //   発見総数、現在処理中の対象（フォルダ名またはファイル名）。
    //   SCANNING 中は総数が未確定なため、total はその時点の発見数と同値になる。
    data class ScanProgress(
        val phase: Phase,
        val processed: Int,
        val total: Int,
        val currentName: String
    ) {
        enum class Phase { SCANNING, PROBING }
    }

    private data class RawEntry(
        val uri: Uri,
        val displayName: String,
        val sizeBytes: Long,
        val modifiedMs: Long,
        val source: Track.Source,
        // 実ファイルの同一性キー（取得できない経路では null）
        val fileKey: String?,
        // MediaStore 由来の補助メタデータ（FFmpeg プローブ失敗時のフォールバック用）
        val fallbackTitle: String?,
        val fallbackArtist: String?,
        val fallbackAlbum: String?,
        val fallbackDurationMs: Long
    )

    private data class CacheKey(val uri: String, val sizeBytes: Long, val modifiedMs: Long)

    // 走査+プローブの結果一式（永続化の差分適用に必要な付随情報を含む）
    private data class ScanOutcome(
        val rawEntries: List<RawEntry>,
        val tracks: List<Track>,
        val probed: List<Track>,
        val mediaStoreScanned: Boolean,
        val scannedSafTrees: Set<String>
    )

    private val probeCache = ConcurrentHashMap<CacheKey, Track>()
    private val executor = Executors.newFixedThreadPool(3)
    private val cacheDb by lazy { LibraryCacheDb(context) }
    private val dbLock = Any()

    fun hasAudioPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context, Manifest.permission.READ_MEDIA_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    // ★ 永続化済みキャッシュだけを読み込んで返す（プローブなし、高速）。
    //   Local 画面の初期表示用。結果は通常のスキャンと同じ並びで返す。
    fun loadCachedTracks(onResult: (List<Track>) -> Unit) {
        executor.execute {
            val cached = try {
                val rows = synchronized(dbLock) { cacheDb.loadAll() }
                seedProbeCache(rows)
                // 旧バージョンで DB に入り込んだ重複行（同一ファイルの
                // MediaStore 行と SAF 行）は、表示の段階でも畳む。
                // 実体の掃除は次回リフレッシュの削除調整が行う。
                sortTracks(dedupeTracks(rows))
            } catch (e: Exception) {
                Log.w(TAG, "loadCachedTracks failed", e)
                emptyList()
            }
            onResult(cached)
        }
    }

    // ★ 楽曲一覧を取得する（差分スキャン、進捗なしの互換ラッパー）。
    fun loadTracks(onResult: (List<Track>) -> Unit) {
        refreshLibrary(forceFull = false, onProgress = null, onResult = onResult)
    }

    // ★ ライブラリのスキャン本体。
    //   forceFull=false: 永続化キャッシュと突き合わせて、変わったファイル
    //     だけを再プローブする差分スキャン。
    //   forceFull=true: 全件を再プローブする（「再スキャン」ボタン用）。
    //   結果は永続化キャッシュへ反映され（新規/変更は upsert、消えた曲は
    //   削除）、ソート済みの一覧が onResult へ返る。onProgress は進捗表示用。
    fun refreshLibrary(
        forceFull: Boolean,
        onProgress: ((ScanProgress) -> Unit)? = null,
        onResult: (List<Track>) -> Unit
    ) {
        executor.execute {
            val result = try {
                refreshBlocking(forceFull, ProgressReporter(onProgress))
            } catch (e: Exception) {
                Log.w(TAG, "refreshLibrary failed", e)
                emptyList()
            }
            onResult(result)
        }
    }

    private fun refreshBlocking(forceFull: Boolean, reporter: ProgressReporter): List<Track> {
        val prefs = AppPreferences.get()
        val persisted = try {
            synchronized(dbLock) { cacheDb.loadAll() }
        } catch (e: Exception) {
            Log.w(TAG, "persisted cache load failed", e)
            emptyList()
        }
        seedProbeCache(persisted)

        val outcome = try {
            scanAndProbe(prefs, forceFull, reporter)
        } catch (e: Exception) {
            // 走査/プローブに失敗したら永続化済みの内容をそのまま返す
            // （表示中の一覧を空にしないためのフォールバック。DB は触らない）
            Log.w(TAG, "scan failed, falling back to persisted cache", e)
            return sortTracks(persisted)
        }

        // 永続化の反映: 新規/変更を upsert、消えた曲を削除する。
        // 削除は「今回そのソースを実際に走査できた」場合に限る（権限を
        // 失ったソースの行を誤って消さないため）。取得範囲（prefs）から
        // 外れた SAF ツリー配下の行も掃除対象にする。
        // 生存判定は「一意化後の最終一覧」の URI で行う: 重複として畳んだ
        // 側の URI は一覧に残らないため、DB に残っている旧重複行
        // （SAF 側コピー等）もここで削除対象になり、リフレッシュのたびに
        // 既存の重複が解消されていく。
        val liveUris = outcome.tracks.map { it.uri.toString() }.toSet()
        val deletes = persisted.filter { row ->
            row.uri.toString() !in liveUris &&
                isReconcileTarget(
                    row, outcome.mediaStoreScanned, outcome.scannedSafTrees, prefs.safFolderUris
                )
        }.map { it.uri.toString() }
        if (outcome.probed.isNotEmpty() || deletes.isNotEmpty()) {
            try {
                synchronized(dbLock) { cacheDb.applyChanges(outcome.probed, deletes) }
            } catch (e: Exception) {
                Log.w(TAG, "persist changes failed", e)
            }
        }
        // メモリ内キャッシュの肥大化防止: 現存しないキーを落とす
        val liveKeys = outcome.rawEntries
            .map { CacheKey(it.uri.toString(), it.sizeBytes, it.modifiedMs) }.toSet()
        probeCache.keys.retainAll(liveKeys)

        return sortTracks(outcome.tracks)
    }

    private fun scanAndProbe(
        prefs: AppPreferences,
        forceFull: Boolean,
        reporter: ProgressReporter
    ): ScanOutcome {
        // ── フェーズ1: 走査（対象ファイルの列挙） ──
        val found = mutableListOf<RawEntry>()
        var mediaStoreScanned = false
        if (prefs.useMediaStore && hasAudioPermission()) {
            found += queryMediaStore(reporter)
            mediaStoreScanned = true
        }
        val scannedSafTrees = mutableSetOf<String>()
        val safUris = prefs.safFolderUris
        if (safUris.isNotEmpty()) {
            found += scanSafFolders(safUris, reporter, scannedSafTrees)
        }
        reporter.scanning(found.size, found.size, "", force = true)
        // 一意化（1段目）: URI の一致に加え、実ファイルの同一性キー
        // （fileKey）が取れているものはキーでも畳む。MediaStore と SAF が
        // 同じフォルダ（例: 本体の Music）を指すと同じ曲が別 URI で2件
        // 入るため、URI だけでは重複を弾けない。列挙順は MediaStore が
        // 先なので、先勝ちで MediaStore 側が残る。
        val rawEntries = dedupeRawEntries(found)

        // ── フェーズ2: メタデータ取得（差分のみプローブ） ──
        val dsdRate = prefs.dsdPcmRateHz
        val total = rawEntries.size
        val tracks = ArrayList<Track>(total)
        val probed = mutableListOf<Track>()
        val seenIdentities = HashSet<String>()
        rawEntries.forEachIndexed { index, entry ->
            val key = CacheKey(entry.uri.toString(), entry.sizeBytes, entry.modifiedMs)
            val cached = if (forceFull) null else probeCache[key]
            // ★ DSD 曲のキャッシュ済み sampleRate はプローブ時点の変換後
            //   レート（§5.2 の設定値）なので、設定が変わっている場合は
            //   ファイル自体に変更がなくても再プローブする。これで
            //   DSD 変換レートを変更した直後のリフレッシュから、表示
            //   レートが新しい設定に追いつく（再プローブ結果は probed に
            //   載り、DB にも upsert される）。
            val reused = cached?.takeUnless { it.isDsd && it.sampleRate != dsdRate }
            val track = reused ?: buildTrack(entry, dsdRate).also {
                probeCache[key] = it
            }
            // 一意化（2段目）: fileKey が無い経路（外部ストレージ以外の
            // SAF プロバイダ等）は、プローブ後に（表示名 + サイズ + 長さ）の
            // 複合キーで同一視する。畳んだ側は一覧にも DB の upsert にも
            // 載せない（DB 側の既存重複行は refreshBlocking の削除調整で
            // 消える）。プローブ結果自体は probeCache に残るので、次回の
            // 差分スキャンで再プローブは発生しない。
            val identity = identityKeyOf(track)
            if (identity == null || seenIdentities.add(identity)) {
                tracks += track
                if (reused == null) probed += track
            }
            reporter.probing(
                index + 1, total, entry.displayName, force = index + 1 == total
            )
        }
        return ScanOutcome(rawEntries, tracks, probed, mediaStoreScanned, scannedSafTrees)
    }

    // ★ 削除調整の対象かどうか: そのソースを今回実際に走査できたかで判定する。
    private fun isReconcileTarget(
        row: Track,
        mediaStoreScanned: Boolean,
        scannedSafTrees: Set<String>,
        prefsSafUris: Set<String>
    ): Boolean {
        return when (row.source) {
            Track.Source.MEDIASTORE -> mediaStoreScanned
            Track.Source.SAF -> {
                val tree = treeUriOf(row.uri.toString())
                // 走査できたツリー配下はもちろん、取得範囲から外れたツリー
                // 配下も対象。prefs に残っているのに走査できなかった
                // （権限喪失など）ツリー配下は消さない。
                tree != null && (tree in scannedSafTrees || tree !in prefsSafUris)
            }
        }
    }

    // SAF のドキュメント URI（.../tree/<id>/document/<doc>）から
    // ツリー URI（.../tree/<id>）を取り出す。形式が違う場合は null。
    private fun treeUriOf(documentUriString: String): String? {
        val idx = documentUriString.indexOf("/document/")
        return if (idx >= 0) documentUriString.substring(0, idx) else null
    }

    // ── 重複の一意化 ──
    //
    // 同一の実ファイルが MediaStore と SAF の両方から別 URI で取得され
    // るため、同一視は URI ではなく実ファイル基準で行う。キーの優先順:
    //   1. fileKey（実パスの正規形「ボリューム:相対パス」）。
    //      MediaStore の DATA 列と SAF のドキュメント ID はどちらも
    //      この形に直せるため、経路をまたいで一致する。
    //   2. 複合キー（表示名 + サイズ + 長さ）。fileKey が取れない経路
    //      （外部ストレージ以外の SAF プロバイダ等）用で、プローブ後の
    //      Track に対してだけ使う。サイズと長さが両方そろわないものは
    //      同一視しない（別曲を誤って畳まないための安全側）。
    // 複合キーで本当に別物の2曲（別フォルダに同名・同サイズ・同長さで
    // 存在）が一致する確率は実質ゼロに近く、一致した場合は中身が同一の
    // コピーである可能性が極めて高いため、この設計を採る。

    // 走査段階の一意化: URI と fileKey の両方で先勝ち。
    private fun dedupeRawEntries(entries: List<RawEntry>): List<RawEntry> {
        val seenUris = HashSet<String>()
        val seenFileKeys = HashSet<String>()
        return entries.filter { entry ->
            if (!seenUris.add(entry.uri.toString())) return@filter false
            val fileKey = entry.fileKey ?: return@filter true
            seenFileKeys.add(fileKey)
        }
    }

    // Track の同一性キー。同一視してよい材料が無い場合は null
    // （その Track は常に残す）。
    private fun identityKeyOf(track: Track): String? {
        track.fileKey?.let { return "file:$it" }
        if (track.sizeBytes > 0 && track.durationMs > 0) {
            return "meta:${track.displayName.lowercase(Locale.ROOT)}" +
                "|${track.sizeBytes}|${track.durationMs}"
        }
        return null
    }

    // Track 群の一意化: MediaStore 側を優先して残し、残したものの
    // 相対順序は維持する（キャッシュ表示用。スキャン側は列挙順が
    // 既に MediaStore 先行なので同じ規則で畳める）。
    private fun dedupeTracks(tracks: List<Track>): List<Track> {
        val seen = HashSet<String>()
        val survivors = HashSet<Track>()
        val ordered = tracks.sortedBy { if (it.source == Track.Source.MEDIASTORE) 0 else 1 }
        for (track in ordered) {
            val identity = identityKeyOf(track)
            if (identity == null || seen.add(identity)) {
                survivors += track
            }
        }
        return tracks.filter { it in survivors }
    }

    // MediaStore の DATA（絶対パス）を fileKey の正規形に直す。
    // 内部ストレージの実体パスは端末により表記が揺れる
    // （/storage/emulated/0、/sdcard、/storage/self/primary）ため、
    // すべて SAF のドキュメント ID と同じ「primary:相対パス」に寄せる。
    // SD カードは「<ボリュームID>:相対パス」。どれにも当たらない
    // パスは "path:<絶対パス>" として保持する（SAF 側とは一致しないが、
    // MediaStore 内の二重登録はこれでも畳める）。
    private fun fileKeyFromPath(rawPath: String?): String? {
        val path = rawPath?.trim()?.replace('\\', '/')?.trimEnd('/') ?: return null
        if (path.isEmpty()) return null
        val primaryPrefixes = listOf(
            "/storage/emulated/0/",
            "/storage/self/primary/",
            "/sdcard/",
            "/mnt/sdcard/"
        )
        for (prefix in primaryPrefixes) {
            if (path.startsWith(prefix)) {
                return "primary:" + path.substring(prefix.length)
            }
        }
        val storageMatch = Regex("^/(?:storage|mnt/media_rw)/([^/]+)/(.+)$").find(path)
        if (storageMatch != null) {
            val volume = storageMatch.groupValues[1].lowercase(Locale.ROOT)
            // emulated/0 の2要素形（/storage/emulated/0/...）も primary 扱い
            return if (volume == "emulated") {
                val rest = storageMatch.groupValues[2]
                if (rest == "0" || rest.startsWith("0/")) {
                    "primary:" + rest.removePrefix("0").removePrefix("/")
                } else {
                    "path:$path"
                }
            } else {
                "$volume:${storageMatch.groupValues[2]}"
            }
        }
        return "path:$path"
    }

    // SAF のドキュメント URI から fileKey を得る。外部ストレージ
    // プロバイダのドキュメント ID は「ボリューム:相対パス」形式で、
    // MediaStore 側の正規形と一致する。それ以外のプロバイダ
    // （ダウンロード等）はパス形式の ID を持たないため null にし、
    // 複合キーでの同一視に委ねる。
    private fun safFileKey(uri: Uri): String? {
        if (uri.authority != "com.android.externalstorage.documents") return null
        val docId = try {
            DocumentsContract.getDocumentId(uri)
        } catch (e: Exception) {
            null
        } ?: return null
        val sep = docId.indexOf(':')
        if (sep <= 0 || sep >= docId.length - 1) return null
        val volume = docId.substring(0, sep).lowercase(Locale.ROOT)
        val relative = docId.substring(sep + 1).trimStart('/')
        if (relative.isEmpty()) return null
        return "$volume:$relative"
    }

    private fun seedProbeCache(tracks: List<Track>) {
        for (track in tracks) {
            probeCache[CacheKey(track.uri.toString(), track.sizeBytes, track.modifiedMs)] = track
        }
    }

    private fun sortTracks(tracks: List<Track>): List<Track> {
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
            source = entry.source,
            fileKey = entry.fileKey
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

    private fun queryMediaStore(reporter: ProgressReporter): List<RawEntry> {
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
            MediaStore.Audio.Media.MIME_TYPE,
            // 実パス（同一性キーの材料。列が引けない/空の端末では null 扱い）
            MediaStore.Audio.Media.DATA
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
                val dataCol = cursor.getColumnIndex(MediaStore.Audio.Media.DATA)
                while (cursor.moveToNext()) {
                    val name = cursor.getString(nameCol) ?: continue
                    if (!isSupportedExtension(name)) continue
                    val id = cursor.getLong(idCol)
                    val uri = Uri.withAppendedPath(collection, id.toString())
                    entries += RawEntry(
                        uri = uri,
                        displayName = name,
                        fileKey = if (dataCol >= 0) {
                            fileKeyFromPath(cursor.getString(dataCol))
                        } else {
                            null
                        },
                        sizeBytes = if (sizeCol >= 0) cursor.getLong(sizeCol) else -1L,
                        modifiedMs = if (modifiedCol >= 0) cursor.getLong(modifiedCol) * 1000L else 0L,
                        source = Track.Source.MEDIASTORE,
                        fallbackTitle = if (titleCol >= 0) cursor.getString(titleCol) else null,
                        fallbackArtist = if (artistCol >= 0) cursor.getString(artistCol) else null,
                        fallbackAlbum = if (albumCol >= 0) cursor.getString(albumCol) else null,
                        fallbackDurationMs = if (durationCol >= 0) cursor.getLong(durationCol) else 0L
                    )
                    reporter.scanning(entries.size, entries.size, name)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "queryMediaStore failed", e)
        }
        return entries
    }

    // ── SAF ──

    private fun scanSafFolders(
        treeUriStrings: Set<String>,
        reporter: ProgressReporter,
        scannedTrees: MutableSet<String>
    ): List<RawEntry> {
        val entries = mutableListOf<RawEntry>()
        for (treeUriString in treeUriStrings) {
            try {
                val treeUri = Uri.parse(treeUriString)
                val root = DocumentFile.fromTreeUri(context, treeUri) ?: continue
                if (!root.exists()) {
                    // フォルダ自体が消えている場合は走査済み扱いにし、
                    // 配下の行を削除調整の対象にする
                    scannedTrees += treeUriString
                    continue
                }
                scanDocumentTree(root, entries, depth = 0, reporter)
                scannedTrees += treeUriString
            } catch (e: Exception) {
                Log.w(TAG, "scanSafFolders failed for $treeUriString", e)
            }
        }
        return entries
    }

    private fun scanDocumentTree(
        dir: DocumentFile,
        out: MutableList<RawEntry>,
        depth: Int,
        reporter: ProgressReporter
    ) {
        if (depth > MAX_SAF_DEPTH) return
        val children = try {
            dir.listFiles()
        } catch (e: Exception) {
            return
        }
        for (child in children) {
            if (child.isDirectory) {
                reporter.scanning(out.size, out.size, (child.name ?: "") + "/")
                scanDocumentTree(child, out, depth + 1, reporter)
            } else if (child.isFile) {
                val name = child.name ?: continue
                if (!isSupportedExtension(name)) continue
                out += RawEntry(
                    uri = child.uri,
                    displayName = name,
                    fileKey = safFileKey(child.uri),
                    sizeBytes = try { child.length() } catch (e: Exception) { -1L },
                    modifiedMs = try { child.lastModified() } catch (e: Exception) { 0L },
                    source = Track.Source.SAF,
                    fallbackTitle = null,
                    fallbackArtist = null,
                    fallbackAlbum = null,
                    fallbackDurationMs = 0L
                )
                reporter.scanning(out.size, out.size, name)
            }
        }
    }

    fun shutdown() {
        executor.shutdown()
    }

    // ★ 進捗コールバックの間引き付き中継。コールバックはワーカースレッドで
    //   呼ばれる。通知の失敗でスキャン自体を止めないよう例外は握り潰す。
    private class ProgressReporter(private val callback: ((ScanProgress) -> Unit)?) {
        private var lastEmitMs = 0L

        fun scanning(processed: Int, total: Int, currentName: String, force: Boolean = false) {
            emit(ScanProgress(ScanProgress.Phase.SCANNING, processed, total, currentName), force)
        }

        fun probing(processed: Int, total: Int, currentName: String, force: Boolean = false) {
            emit(ScanProgress(ScanProgress.Phase.PROBING, processed, total, currentName), force)
        }

        private fun emit(progress: ScanProgress, force: Boolean) {
            val cb = callback ?: return
            val now = SystemClock.elapsedRealtime()
            if (!force && now - lastEmitMs < EMIT_INTERVAL_MS) return
            lastEmitMs = now
            try {
                cb(progress)
            } catch (e: Exception) {
                // 進捗通知の失敗でスキャンを止めない
            }
        }

        companion object {
            private const val EMIT_INTERVAL_MS = 50L
        }
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
