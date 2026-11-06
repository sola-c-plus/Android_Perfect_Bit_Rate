package com.example.perfectbitrate

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

// ★ Local Music の供給係（仕様書 §4.1）。GeckoSessionController.Listener と
//   同じ形の通知（PCM / コーデック / flush / メタデータ / 進捗 / 状態）を
//   Listener 経由で MainActivity へ届け、既存の Service 以下は無変更で使う。
//
//   内部構成:
//   - 再生制御（play/pause/seek/next/prev、キュー管理）はこのクラス。
//   - デコードは FfmpegDecoder を専用スレッド（decodeExecutor）で回す。
//   - 状態変更は全て stateLock で直列化し、デコード終了コールバックは
//     世代（generation）で陳腐化を判定する。
class LocalPlayer(
    private val context: Context,
    private val repository: LibraryRepository,
    private val listener: Listener
) {

    // GeckoSessionController.Listener と対をなす Local 側の通知契約。
    // onMetadata は Local 用に Bitmap を直接渡す形（仕様書 §4.3）。
    interface Listener {
        fun onPcm(pcmBytes: ByteArray, sampleRate: Int)
        fun onCodec(codec: String, sampleRate: Int)
        fun onFlush()
        fun onMetadata(title: String, artist: String, artwork: Bitmap?)
        fun onProgress(currentMs: Long, durationMs: Long, isPlaying: Boolean)
        fun onState(isPlaying: Boolean)
        fun onTrackChanged(index: Int, track: LibraryRepository.Track)
    }

    private val stateLock = Any()
    private val decodeExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "LocalPlayerDecode").apply { isDaemon = true }
    }
    private val artworkExecutor = Executors.newSingleThreadExecutor()

    private var queue: List<LibraryRepository.Track> = emptyList()
    private var currentIndex = -1
    private var decoder: FfmpegDecoder? = null
    private var decodeFuture: Future<*>? = null

    @Volatile
    private var decodeThread: Thread? = null

    @Volatile
    private var generation = 0

    @Volatile
    var isPlaying = false
        private set

    @Volatile
    var currentTrack: LibraryRepository.Track? = null
        private set

    @Volatile
    var currentInfo: FfmpegDecoder.Info? = null
        private set

    @Volatile
    private var positionMs = 0L

    @Volatile
    private var durationMs = 0L

    @Volatile
    private var pendingSeekMs = -1L

    // ── キュー ──

    fun setQueue(tracks: List<LibraryRepository.Track>) {
        synchronized(stateLock) {
            queue = tracks
            if (currentIndex >= tracks.size) currentIndex = -1
        }
    }

    fun queueSize(): Int = synchronized(stateLock) { queue.size }

    fun currentIndex(): Int = synchronized(stateLock) { currentIndex }

    // ── 再生制御 ──

    // 指定インデックスの曲を頭から再生する
    fun playAt(index: Int) {
        synchronized(stateLock) {
            if (index < 0 || index >= queue.size) return
            startTrackLocked(index, 0L)
        }
    }

    fun play() {
        synchronized(stateLock) {
            if (isPlaying) return
            if (decoder != null && currentTrack != null) {
                // ポーズで保持したデコーダを現在位置から再開する
                val gen = ++generation
                val dec = decoder!!
                val resumeAt = positionMs
                isPlaying = true
                listener.onState(true)
                decodeFuture = decodeExecutor.submit {
                    decodeThread = Thread.currentThread()
                    if (resumeAt > 0) dec.requestSeek(resumeAt)
                    runDecodeLoop(gen, dec)
                }
            } else if (currentIndex in queue.indices) {
                startTrackLocked(currentIndex, positionMs)
            } else if (queue.isNotEmpty()) {
                startTrackLocked(0, 0L)
            }
        }
    }

    fun pause() {
        synchronized(stateLock) {
            if (!isPlaying && decoder == null) return
            isPlaying = false
            generation++
            decoder?.requestStop()
            // デコーダは保持し、play() で現在位置から再開する（再オープンしない）
            listener.onState(false)
            listener.onProgress(positionMs, durationMs, false)
        }
    }

    // 完全停止（モード切替・解放時）。デコーダを解放し位置をリセットする。
    fun stop() {
        synchronized(stateLock) {
            isPlaying = false
            generation++
            stopAndReleaseDecoderLocked()
            positionMs = 0L
            pendingSeekMs = -1L
        }
    }

    // stateLock 保持必須。デコードループの終了を待ってからデコーダを解放する
    // （ループ実行中の native 解放は use-after-free になるため）。
    // デコードスレッド自身から呼ばれた場合（曲送りの連鎖）は待たない
    // （その時点でループは既に抜けている）。
    private fun stopAndReleaseDecoderLocked() {
        val dec = decoder ?: return
        dec.requestStop()
        if (Thread.currentThread() !== decodeThread) {
            try {
                decodeFuture?.get(2, TimeUnit.SECONDS)
            } catch (e: Exception) {
                Log.w(TAG, "decode loop did not stop in time", e)
            }
        }
        dec.release()
        decoder = null
        decodeFuture = null
    }

    fun next() {
        synchronized(stateLock) {
            if (queue.isEmpty()) return
            val nextIndex = currentIndex + 1
            if (nextIndex < queue.size) {
                startTrackLocked(nextIndex, 0L)
            }
        }
    }

    fun prev() {
        synchronized(stateLock) {
            if (queue.isEmpty()) return
            // 再生開始から 3 秒以内は前曲、それ以外は曲頭へ（一般的なプレイヤーの作法）
            if (isPlaying && positionMs > 3000L) {
                seekTo(0L)
                return
            }
            val prevIndex = currentIndex - 1
            if (prevIndex >= 0) {
                startTrackLocked(prevIndex, 0L)
            } else {
                seekTo(0L)
            }
        }
    }

    fun seekTo(targetMs: Long) {
        synchronized(stateLock) {
            val dec = decoder ?: return
            val clamped = targetMs.coerceIn(0L, if (durationMs > 0) durationMs else targetMs)
            positionMs = clamped
            listener.onFlush()
            if (isPlaying) {
                dec.requestSeek(clamped)
            } else {
                // ポーズ中は再開時に適用する
                pendingSeekMs = clamped
                positionMs = clamped
            }
            listener.onProgress(positionMs, durationMs, isPlaying)
        }
    }

    // Service からのコマンド文字列（通知 / メディアセッション / ウィジェット経由）
    fun handleCommand(command: String) {
        when (command) {
            "play" -> play()
            "pause" -> pause()
            "next" -> next()
            "prev" -> prev()
            else -> { /* heartbeat 等は無視する */ }
        }
    }

    fun release() {
        stop()
        decodeExecutor.shutdown()
        artworkExecutor.shutdown()
    }

    // ── 内部 ──

    // stateLock 保持必須。指定曲のデコーダを開いてデコードを開始する。
    private fun startTrackLocked(index: Int, startAtMs: Long) {
        val track = queue[index]
        val gen = ++generation

        // 前のデコードを止めてデコーダを解放する
        stopAndReleaseDecoderLocked()

        currentIndex = index
        currentTrack = track
        positionMs = startAtMs
        pendingSeekMs = -1L

        val dec = FfmpegDecoder(context)
        decoder = dec
        dec.callback = object : FfmpegDecoder.Callback {
            override fun onPcmChunk(pcmBytes: ByteArray, chunkPositionMs: Long) {
                if (gen != generation) return
                positionMs = chunkPositionMs
                val rate = currentInfo?.sampleRate ?: track.sampleRate
                listener.onPcm(pcmBytes, rate)
            }

            override fun onDecodeEnded(error: Boolean) {
                handleDecodeEnded(gen, error)
            }
        }

        val dsdRate = AppPreferences.get().dsdPcmRateHz
        val opened = dec.openUri(track.uri, dsdRate)
        if (!opened) {
            Log.w(TAG, "startTrack: open failed: ${track.displayName}")
            isPlaying = false
            listener.onState(false)
            dec.release()
            if (decoder === dec) decoder = null
            // 開けない曲は次へ送る（連続失敗時は世代側で止まるよう件数制限）
            autoAdvanceAfterFailure(gen, index)
            return
        }
        consecutiveOpenFailures = 0

        val info = dec.info()
        currentInfo = info
        durationMs = info?.durationMs?.takeIf { it > 0 } ?: track.durationMs
        val codecLabel = buildCodecLabel(info, track)
        val rate = info?.sampleRate ?: 0
        if (rate > 0) {
            listener.onCodec(codecLabel, rate)
        }
        listener.onFlush()
        listener.onMetadata(track.title, track.artist, null)
        listener.onTrackChanged(index, track)
        loadArtworkAsync(gen, track)

        isPlaying = true
        listener.onState(true)
        listener.onProgress(positionMs, durationMs, true)

        decodeFuture = decodeExecutor.submit {
            decodeThread = Thread.currentThread()
            if (startAtMs > 0) dec.requestSeek(startAtMs)
            runDecodeLoop(gen, dec)
        }
    }

    private fun runDecodeLoop(gen: Int, dec: FfmpegDecoder) {
        // ポーズ再開時に保留していたシークを先に適用する
        val pending = synchronized(stateLock) {
            val p = pendingSeekMs
            pendingSeekMs = -1L
            p
        }
        if (pending >= 0 && gen == generation) {
            dec.requestSeek(pending)
        }
        dec.decodeLoop()
    }

    private fun handleDecodeEnded(gen: Int, error: Boolean) {
        synchronized(stateLock) {
            if (gen != generation) return // 古い世代の終了は無視する
            if (error) {
                Log.w(TAG, "decode ended with error at index=$currentIndex")
            }
            isPlaying = false
            val hasNext = currentIndex + 1 < queue.size
            if (!error && hasNext) {
                // ★ 曲の終わり → 次曲へ自動送り（全曲リスト順、仕様書 §4.2）
                startTrackLocked(currentIndex + 1, 0L)
            } else {
                listener.onState(false)
                listener.onProgress(positionMs, durationMs, false)
                if (!error) {
                    // キュー末尾まで再生し切った
                    positionMs = 0L
                    decoder?.release()
                    decoder = null
                    decodeFuture = null
                    currentInfo = null
                }
            }
        }
    }

    private var consecutiveOpenFailures = 0

    private fun autoAdvanceAfterFailure(gen: Int, failedIndex: Int) {
        // startTrackLocked 内（stateLock 保持中）から呼ばれる前提
        consecutiveOpenFailures++
        if (consecutiveOpenFailures > MAX_CONSECUTIVE_FAILURES) {
            consecutiveOpenFailures = 0
            return
        }
        val nextIndex = failedIndex + 1
        if (nextIndex < queue.size && gen == generation) {
            startTrackLocked(nextIndex, 0L)
        }
    }

    private fun loadArtworkAsync(gen: Int, track: LibraryRepository.Track) {
        artworkExecutor.execute {
            val artwork = try {
                // 再生中デコーダからの取得はデコードと競合しうるため、
                // アートワークは Repository 経由で別デコーダから取る
                repository.loadArtwork(track)
            } catch (e: Exception) {
                Log.w(TAG, "artwork load failed", e)
                null
            }
            if (artwork != null) {
                synchronized(stateLock) {
                    if (gen == generation && currentTrack == track) {
                        listener.onMetadata(track.title, track.artist, artwork)
                    }
                }
            }
        }
    }

    // 上部ステータス表示用コーデック文字列（YT 側の "OPUS 160KBPS" 形式に寄せる）
    private fun buildCodecLabel(info: FfmpegDecoder.Info?, track: LibraryRepository.Track): String {
        val name = (info?.codecName ?: track.codecName).uppercase()
        if (name.isEmpty()) return "LOCAL"
        val bitRate = info?.bitRate ?: 0L
        val bits = info?.bitDepth ?: track.bitDepth
        return when {
            info?.isDsd == true || track.isDsd -> {
                val dsdRate = AppPreferences.get().dsdPcmRateHz
                "$name (DSD → PCM ${dsdRate / 1000.0}KHZ)"
            }
            bitRate > 0 -> "$name ${bitRate / 1000}KBPS"
            bits > 0 -> "$name ${bits}BIT"
            else -> name
        }
    }

    // 進捗の定期通知用に現在位置を返す（MainActivity のタイマから参照）
    fun positionSnapshot(): Pair<Long, Long> = positionMs to durationMs

    companion object {
        private const val TAG = "LocalPlayer"
        private const val MAX_CONSECUTIVE_FAILURES = 3
    }
}
