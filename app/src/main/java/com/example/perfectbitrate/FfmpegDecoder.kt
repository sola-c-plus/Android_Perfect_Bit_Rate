package com.example.perfectbitrate

import android.content.Context
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

// ★ FFmpeg デコーダの Kotlin ラッパ（仕様書 §5）。JNI ブリッジ
//   （cpp/ffmpeg_decoder.cpp、libffmpeg_bridge.so）の薄い受け口で、
//   入力ストリームの管理とコールバックの振り分けだけを担う。
//   デコードそのもの（スレッド運用・キュー・状態遷移）は LocalPlayer が持つ。
//
//   入力経路:
//   - openFile(path): 実パスがあるファイル。ネイティブが直接開く。
//   - openUri(uri): content URI。ネイティブのカスタム AVIO が、ここが保持する
//     InputStream へのコールバック（avioRead / avioSeek / avioSize）で読む。
//
//   スレッド: decodeLoop はブロッキングなので専用スレッドから呼ぶこと。
//   probe は任意スレッド可（内部で開いて閉じる）。
class FfmpegDecoder(private val context: Context) {

    // デコード結果の受け口（LocalPlayer が実装する）
    interface Callback {
        // float32 interleaved PCM（約 4096 フレーム単位）とそのチャンク先頭の再生位置
        fun onPcmChunk(pcmBytes: ByteArray, positionMs: Long)
        // デコードループ終了。error=true は異常終了
        fun onDecodeEnded(error: Boolean)
    }

    data class Info(
        val sampleRate: Int,
        val channels: Int,
        val bitDepth: Int,       // 推定ビット深度（0=不明。ロスイー系は基本 0）
        val codecName: String,
        val bitRate: Long,
        val durationMs: Long,
        val isDsd: Boolean,
        val title: String,
        val artist: String,
        val album: String,
        val track: String,
        // アルバムアーティスト（タグの album_artist 系）。無い曲は ""
        // （呼び出し側でアーティストへ代替する）。旧い .so（戻り値が
        // 11 要素まで）では常に "" になる。
        val albumArtist: String = "",
        // コンテナ形式（AVFormatContext の iformat 名、例 "flac" /
        // "mov,mp4,m4a,3gp,3g2,mj2"）。旧い .so（戻り値が 12 要素
        // まで）では常に "" になる。
        val container: String = ""
    )

    @Volatile
    var callback: Callback? = null

    private var nativeHandle: Long = 0L

    // ── AVIO 用ストリーム（openUri のときだけ使う） ──
    private val avioLock = ReentrantLock()
    private var avioUri: Uri? = null
    private var avioStream: InputStream? = null
    private var avioPosition = 0L
    private var avioSize = -1L

    val isOpen: Boolean
        get() = nativeHandle != 0L && opened

    @Volatile
    private var opened = false

    init {
        ensureNativeLoaded()
        nativeHandle = if (librariesAvailable) nativeCreate() else 0L
    }

    // ★ ファイルパスで開く。戻り値 true = 成功。
    //   pcmOutputRateHz > 0 はデコード出力レートの上限（出力側で
    //   AudioTrack を作れないレートだった場合に、既存の swresample
    //   経路で変換して供給するため）。0 は変換なし（従来動作）。
    //   DSD 経路では使われない（dsdPcmRateHz が優先される）。
    fun openFile(path: String, dsdPcmRateHz: Int, pcmOutputRateHz: Int = 0): Boolean {
        if (nativeHandle == 0L) return false
        closeAvioStream()
        val ret = nativeOpen(nativeHandle, false, path, dsdPcmRateHz, pcmOutputRateHz)
        opened = ret == 0
        return opened
    }

    // ★ content URI で開く（AVIO コールバック経由）。戻り値 true = 成功。
    fun openUri(uri: Uri, dsdPcmRateHz: Int, pcmOutputRateHz: Int = 0): Boolean {
        if (nativeHandle == 0L) return false
        avioLock.withLock {
            avioUri = uri
            avioSize = querySize(uri)
            avioPosition = 0L
            closeAvioStreamLocked()
            if (!openAvioStreamLocked(0L)) {
                Log.w(TAG, "openUri: stream open failed: $uri")
                return false
            }
        }
        val ret = nativeOpen(nativeHandle, true, null, dsdPcmRateHz, pcmOutputRateHz)
        opened = ret == 0
        if (!opened) closeAvioStream()
        return opened
    }

    fun info(): Info? {
        if (nativeHandle == 0L || !opened) return null
        val values = nativeGetInfo(nativeHandle) ?: return null
        if (values.size < 11) return null
        return Info(
            sampleRate = values[0].toIntOrNull() ?: 0,
            channels = values[1].toIntOrNull() ?: 0,
            bitDepth = values[2].toIntOrNull() ?: 0,
            codecName = values[3],
            bitRate = values[4].toLongOrNull() ?: 0L,
            durationMs = values[5].toLongOrNull() ?: 0L,
            isDsd = values[6] == "1",
            title = values[7],
            artist = values[8],
            album = values[9],
            track = values[10],
            albumArtist = values.getOrNull(11) ?: "",
            container = values.getOrNull(12) ?: ""
        )
    }

    // 埋め込みアートワークを Bitmap 化して返す（無ければ null）
    fun artwork(maxDimension: Int = 1024): Bitmap? {
        if (nativeHandle == 0L || !opened) return null
        val bytes = nativeGetArtwork(nativeHandle) ?: return null
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            var sample = 1
            while (bounds.outWidth / sample > maxDimension || bounds.outHeight / sample > maxDimension) {
                sample *= 2
            }
            val options = BitmapFactory.Options().apply { inSampleSize = sample }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        } catch (e: Exception) {
            Log.w(TAG, "artwork decode failed", e)
            null
        }
    }

    // ★ デコードループ（ブロッキング）。専用スレッドから呼ぶこと。
    fun decodeLoop() {
        if (nativeHandle == 0L || !opened) {
            callback?.onDecodeEnded(true)
            return
        }
        nativeDecodeLoop(nativeHandle)
    }

    fun requestStop() {
        if (nativeHandle != 0L) nativeRequestStop(nativeHandle)
    }

    fun requestSeek(positionMs: Long) {
        if (nativeHandle != 0L) nativeRequestSeek(nativeHandle, positionMs)
    }

    fun release() {
        if (nativeHandle != 0L) {
            nativeRelease(nativeHandle)
            nativeHandle = 0L
        }
        opened = false
        closeAvioStream()
    }

    // ── ネイティブからのコールバック（JNI ブリッジが呼ぶ。名前と型は
    //    cpp 側の GetMethodID と一致させること） ──

    @Suppress("unused") // JNI から呼ばれる
    fun onPcmChunk(pcmBytes: ByteArray, positionMs: Long) {
        callback?.onPcmChunk(pcmBytes, positionMs)
    }

    @Suppress("unused") // JNI から呼ばれる
    fun onDecodeEnded(error: Boolean) {
        callback?.onDecodeEnded(error)
    }

    @Suppress("unused") // JNI から呼ばれる: バッファへ最大 buf.size バイト読む (0=EOF, -1=エラー)
    fun avioRead(buf: ByteArray): Int {
        return avioLock.withLock {
            val stream = avioStream ?: return -1
            try {
                val read = stream.read(buf, 0, buf.size)
                if (read > 0) avioPosition += read
                // InputStream の -1 (EOF) は 0 に読み替える（ネイティブ側で
                // AVERROR_EOF に変換される。-1 のままだと EIO 扱いになる）
                if (read == -1) 0 else read
            } catch (e: Exception) {
                Log.w(TAG, "avioRead failed", e)
                -1
            }
        }
    }

    @Suppress("unused") // JNI から呼ばれる: whence は stdio 互換 (0=SET, 1=CUR, 2=END)
    fun avioSeek(offset: Long, whence: Int): Long {
        return avioLock.withLock {
            val size = avioSize
            val target = when (whence) {
                0 -> offset // SEEK_SET
                1 -> avioPosition + offset // SEEK_CUR
                2 -> if (size >= 0) size + offset else return -1L // SEEK_END
                else -> return -1L
            }
            if (target < 0) return -1L
            if (target == avioPosition) return avioPosition

            // バッファ済み範囲内なら巻き戻し/早送りで済ませる
            val stream = avioStream
            if (stream != null && stream.markSupported()) {
                val delta = target - avioPosition
                if (delta < 0 && -delta <= MARK_READ_LIMIT) {
                    try {
                        stream.reset()
                        skipFully(stream, target)
                        avioPosition = target
                        return avioPosition
                    } catch (e: Exception) {
                        // フォールバックして開き直す
                    }
                }
            }
            // それ以外は開き直して先頭からスキップする（SAF 越しの実用解）
            if (!openAvioStreamLocked(0L)) return -1L
            try {
                skipFully(avioStream!!, target)
                avioPosition = target
                avioPosition
            } catch (e: Exception) {
                Log.w(TAG, "avioSeek failed", e)
                -1L
            }
        }
    }

    @Suppress("unused") // JNI から呼ばれる
    fun avioSize(): Long = avioSize

    // ── AVIO ストリーム管理 ──

    private fun querySize(uri: Uri): Long {
        // AssetFileDescriptor の長さが最も確実。取れなければ OpenableColumns。
        try {
            context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { afd ->
                val len = afd.length
                if (len >= 0) return len
            }
        } catch (e: Exception) { /* 次の手段へ */ }
        var cursor: Cursor? = null
        return try {
            cursor = context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)
            if (cursor != null && cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (idx >= 0 && !cursor.isNull(idx)) cursor.getLong(idx) else -1L
            } else {
                -1L
            }
        } catch (e: Exception) {
            -1L
        } finally {
            cursor?.close()
        }
    }

    // ストリームを開いてバッファリングし、mark を打つ。position は 0 起点で管理する。
    private fun openAvioStreamLocked(startAt: Long): Boolean {
        val uri = avioUri ?: return false
        closeAvioStreamLocked()
        return try {
            val raw: InputStream = if (uri.scheme == "file") {
                FileInputStream(File(uri.path ?: return false))
            } else {
                context.contentResolver.openInputStream(uri) ?: return false
            }
            val buffered = BufferedInputStream(raw, BUFFER_SIZE)
            buffered.mark(MARK_READ_LIMIT)
            avioStream = buffered
            avioPosition = 0L
            if (startAt > 0) {
                skipFully(buffered, startAt)
                avioPosition = startAt
            }
            true
        } catch (e: Exception) {
            Log.w(TAG, "openAvioStream failed", e)
            false
        }
    }

    private fun closeAvioStreamLocked() {
        try {
            avioStream?.close()
        } catch (e: Exception) { /* 無視 */ }
        avioStream = null
    }

    private fun closeAvioStream() {
        avioLock.withLock { closeAvioStreamLocked() }
    }

    private fun skipFully(stream: InputStream, bytes: Long) {
        var remaining = bytes
        while (remaining > 0) {
            val skipped = stream.skip(remaining)
            if (skipped <= 0) {
                // skip が進まないストリーム向けに 1 バイト読んで捨てる
                if (stream.read() == -1) break
                remaining--
            } else {
                remaining -= skipped
            }
        }
    }

    // ── JNI ──

    private external fun nativeCreate(): Long
    private external fun nativeRelease(handle: Long)
    private external fun nativeOpen(handle: Long, useAvio: Boolean, path: String?, dsdPcmRateHz: Int, pcmOutputRateHz: Int): Int
    private external fun nativeGetInfo(handle: Long): Array<String>?
    private external fun nativeGetArtwork(handle: Long): ByteArray?
    private external fun nativeDecodeLoop(handle: Long)
    private external fun nativeRequestStop(handle: Long)
    private external fun nativeRequestSeek(handle: Long, positionMs: Long)

    companion object {
        private const val TAG = "FfmpegDecoder"
        private const val BUFFER_SIZE = 256 * 1024
        private const val MARK_READ_LIMIT = 1024 * 1024

        @Volatile
        private var librariesAvailable = false

        @Volatile
        private var loadAttempted = false

        // ★ .so のロードは依存順（avutil → swresample/avcodec → avformat → bridge）。
        //   jniLibs に .so が配置されていない環境ではここで失敗し、Local 再生全体が
        //   無効化される（isAvailable = false）。YT モードには一切影響しない。
        @Synchronized
        private fun ensureNativeLoaded() {
            if (loadAttempted) return
            loadAttempted = true
            librariesAvailable = try {
                System.loadLibrary("avutil")
                System.loadLibrary("swresample")
                System.loadLibrary("avcodec")
                System.loadLibrary("avformat")
                System.loadLibrary("ffmpeg_bridge")
                Log.i(TAG, "FFmpeg libraries loaded")
                true
            } catch (e: UnsatisfiedLinkError) {
                Log.w(TAG, "FFmpeg libraries not available (jniLibs に .so 未配置の可能性)", e)
                false
            }
        }

        val isAvailable: Boolean
            get() {
                ensureNativeLoaded()
                return librariesAvailable
            }

        // ★ メタデータのみの軽量プローブ（開いて info を取り、すぐ閉じる）。
        //   ライブラリ一覧の構築用。デコードは行わない。
        fun probe(context: Context, uri: Uri, dsdPcmRateHz: Int): Info? {
            if (!isAvailable) return null
            val decoder = FfmpegDecoder(context)
            return try {
                if (decoder.nativeHandle == 0L) {
                    null
                } else if (decoder.openUri(uri, dsdPcmRateHz)) {
                    decoder.info()
                } else {
                    null
                }
            } catch (e: Exception) {
                Log.w(TAG, "probe failed: $uri", e)
                null
            } finally {
                decoder.release()
            }
        }
    }
}
