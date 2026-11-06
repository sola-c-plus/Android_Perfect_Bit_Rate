package com.example.perfectbitrate

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.random.Random

// ★ Local Music の供給係（仕様書 §4.1）。GeckoSessionController.Listener と
//   同じ形の通知（PCM / コーデック / flush / メタデータ / 進捗 / 状態）を
//   Listener 経由で MainActivity へ届け、既存の Service 以下は無変更で使う。
//
//   内部構成:
//   - 再生制御（play/pause/seek/next/prev、キュー管理）はこのクラス。
//   - デコードは FfmpegDecoder を専用スレッド（decodeExecutor）で回す。
//   - 状態変更は全て stateLock で直列化し、デコード終了コールバックは
//     世代（generation）で陳腐化を判定する。
//   - 供給のフロー制御: デコードはファイル読み取り速度で進むため、そのまま
//     流すと Service の pcmQueue（容量あり・満杯時は最古を破棄）が溢れて
//     大半の PCM が捨てられ、曲が先へ飛ぶ。チャンク供給前に Service の
//     キュー残量を見て高水位以上では待機し、実時間の消費に追従させる
//     （awaitQueueRoom）。あわせて、曲送りはデコード完了ではなく Service
//     側の PCM が再生し切ってから行う（scheduleAdvanceAfterDrain）。
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
        // エラーで再生が止まった理由の通知（無言の停止をなくすため）
        fun onPlaybackError(message: String)
    }

    private val stateLock = Any()
    private val decodeExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "LocalPlayerDecode").apply { isDaemon = true }
    }
    private val artworkExecutor = Executors.newSingleThreadExecutor()

    private var queue: List<LibraryRepository.Track> = emptyList()
    private var currentIndex = -1

    // ★ 実効再生順（順序表）: playOrder[実効位置] = キュー内インデックス。
    //   次へ / 前へ / 自動進行はすべてこの順序表に従う。シャッフル OFF
    //   では元の並び順そのもの、ON では「現在曲を先頭に固定した
    //   ランダム順」。orderPosition は順序表上の現在位置で、有効な間は
    //   playOrder[orderPosition] == currentIndex を保つ。
    private var playOrder: List<Int> = emptyList()
    private var orderPosition = -1

    // ★ 順序表が「仮決め」の間 true。setQueue でキューの中身が変わると
    //   立ち、再生開始（playAt / play）で「実際に再生を始める曲」を
    //   アンカーに順序表を作り直して下ろす。閲覧だけでは setQueue
    //   自体が呼ばれなくなったため（キューはタップ再生でのみ確定
    //   する）、仮決めの順序表がそのまま再生に使われることはない。
    //   これにより、シャッフル ON で新しいキューから再生を始めたら
    //   必ず開始曲を先頭にしたシャッフル順になり、アイコン表示
    //   （prefs の ON/OFF）と実順序がずれない。
    private var orderStale = false
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

    // 現在の曲のデコードが完了した（Service 側の再生し切り待ち含む）か。
    // シーク時の扱い（ネイティブシーク不可 → 曲の再開始）を切り替えるために使う。
    @Volatile
    private var decodeFinished = false

    // ★ Service の PCM キュー残量の読み取り口（MainActivity が配線する）。
    //   未配線（null）や容量 0 のときはフロー制御を行わない（従来動作）。
    @Volatile
    var queueSizeProvider: (() -> Int)? = null

    @Volatile
    var queueCapacityProvider: (() -> Int)? = null

    // ── キュー ──

    fun setQueue(tracks: List<LibraryRepository.Track>) {
        synchronized(stateLock) {
            // ★ キューの確定は「リストで曲をタップして再生する瞬間」
            //   だけ（MainActivity のタップ経路からのみ呼ばれる）。
            //   タブ切替・検索・ソート等の閲覧では呼ばれないため、
            //   再生中キューが表示に引きずられて差し替わることはない。
            //   再生中の曲が新しいキューに含まれていれば、その位置へ
            //   インデックスを寄せ直し、次曲送りや自動進行が別の曲へ
            //   飛ばないようにする。含まれていない場合は従来どおり
            //   範囲外なら -1 へ丸める。
            // ★ 中身（URI の並び）が前と同じなら順序表は作り直さない
            //   （WALKMAN と同じく、順序は内容が変わるまで保持する）。
            //   中身が変わった場合は順序表を仮決めにし（orderStale）、
            //   直後の再生開始（playAt）で開始曲をアンカーに作り直す。
            val sameContent = tracks.size == queue.size &&
                tracks.indices.all { tracks[it].uri == queue[it].uri }
            queue = tracks
            val current = currentTrack
            val anchor = if (current != null) {
                tracks.indexOfFirst { it.uri == current.uri }
            } else {
                -1
            }
            if (anchor >= 0) {
                currentIndex = anchor
            } else if (currentIndex >= tracks.size) {
                currentIndex = -1
            }
            if (sameContent && playOrder.size == queue.size) {
                orderPosition =
                    if (currentIndex in queue.indices) playOrder.indexOf(currentIndex) else -1
            } else {
                rebuildOrderLocked(if (anchor >= 0) anchor else currentIndex)
                orderStale = true
            }
        }
    }

    // stateLock 保持必須。シャッフル状態に応じた順序表を生成する。
    // anchorQueueIndex は現在曲のキュー内インデックス（無ければ -1）。
    // シャッフル ON では現在曲を先頭（実効位置 0）に固定し、残りを
    // ランダムに並べる。OFF では元の並び順で、現在位置は現在曲の
    // 位置を引き継ぐ。
    private fun rebuildOrderLocked(anchorQueueIndex: Int) {
        if (queue.isEmpty()) {
            playOrder = emptyList()
            orderPosition = -1
            return
        }
        playOrder = if (shuffleEnabled) {
            if (anchorQueueIndex in queue.indices) {
                val rest = queue.indices
                    .filter { it != anchorQueueIndex }
                    .shuffled(Random)
                listOf(anchorQueueIndex) + rest
            } else {
                queue.indices.shuffled(Random)
            }
        } else {
            queue.indices.toList()
        }
        orderPosition =
            if (anchorQueueIndex in queue.indices) playOrder.indexOf(anchorQueueIndex) else -1
    }

    fun queueSize(): Int = synchronized(stateLock) { queue.size }

    fun currentIndex(): Int = synchronized(stateLock) { currentIndex }

    // 実効再生順における現在位置（0 始まり）。再生画面の n / N 表示と
    // キュータブの現在位置に使う。現在曲が決まっていなければ -1。
    fun effectivePosition(): Int = synchronized(stateLock) { orderPosition }

    // 実効再生順に並べた現在のキュー（キュータブ表示用）。
    fun effectiveOrderTracks(): List<LibraryRepository.Track> =
        synchronized(stateLock) { playOrder.map { queue[it] } }

    // キュータブ等から、実効再生順の位置を指定して再生する。
    fun playAtEffectivePosition(position: Int) {
        synchronized(stateLock) {
            val queueIndex = playOrder.getOrNull(position) ?: return
            if (orderStale) {
                // ★ 開始経路はリスト経由の playAt と揃える: 仮決めの
                //   順序表が残っていたら、「実際に再生を始める曲」を
                //   アンカーに作り直してから確定する。作り直しは
                //   キュー自体を変えず順序表だけを組み替えるため、
                //   先に確定した queueIndex（タップされた曲）は
                //   そのまま開始曲として使える（開始後の現在位置は
                //   startTrackLocked が新しい順序表から引き直す）。
                rebuildOrderLocked(queueIndex)
                orderStale = false
            }
            startTrackLocked(queueIndex, 0L)
        }
    }

    // ── シャッフル / リピート（Now Playing） ──
    //
    // 状態の正は prefs（AppPreferences）で、ここは読み書きの窓口を持つ。
    // シャッフルは「自動進行の行き先を都度ランダムに選ぶ」のではなく、
    // WALKMAN と同じく実効再生順（順序表）を実際に生成して、その順で
    // 再生する方式。手動の next / prev も順序表に従うため、シャッフル
    // 時の「前へ」は実際に前に再生した曲へ戻る。リピートとの優先
    // 順位は自動進行でのみ効く（リピート1曲 > 順序表の次）。

    val shuffleEnabled: Boolean
        get() = AppPreferences.get().localShuffleEnabled

    val repeatMode: Int
        get() = AppPreferences.get().localRepeatMode

    fun setShuffleEnabled(enabled: Boolean) {
        AppPreferences.get().localShuffleEnabled = enabled
        synchronized(stateLock) {
            // ★ 切替えたその場で順序表を作り直す（再生中の曲はそのまま、
            //   ON では現在曲が先頭に固定される）。作り直した順序表は
            //   現在のキューに対する確定順なので仮決めフラグも下ろす。
            rebuildOrderLocked(currentIndex)
            orderStale = false
        }
    }

    // リピートを OFF → 全曲 → 1曲 の順に循環させ、新しい状態を返す。
    fun cycleRepeatMode(): Int {
        val next = (repeatMode + 1) % 3
        AppPreferences.get().localRepeatMode = next
        return next
    }

    // stateLock 保持必須。自動進行の行き先（キュー内インデックス、
    // -1 = 再生終了）を順序表から決める。
    // 優先順位: リピート1曲 > 順序表の次の位置。順序表の末尾では、
    // リピート全曲なら先頭へ回し、それ以外は終了する。
    private fun computeAutoAdvanceIndexLocked(): Int {
        if (queue.isEmpty() || currentIndex !in queue.indices) return -1
        if (repeatMode == AppPreferences.LOCAL_REPEAT_ONE) return currentIndex
        if (orderPosition in playOrder.indices && orderPosition + 1 < playOrder.size) {
            return playOrder[orderPosition + 1]
        }
        if (repeatMode == AppPreferences.LOCAL_REPEAT_ALL && playOrder.isNotEmpty()) {
            return playOrder.first()
        }
        return -1
    }

    // ── 再生制御 ──

    // 指定インデックスの曲を頭から再生する
    fun playAt(index: Int) {
        synchronized(stateLock) {
            if (index < 0 || index >= queue.size) return
            if (orderStale) {
                // ★ 新しいキューでの再生開始: 順序表は「この開始曲」を
                //   アンカーに作り直してから確定する。シャッフル ON
                //   なら開始曲を先頭に残りをシャッフルし、OFF なら
                //   元の並び順になる。setQueue 時点の仮決め（旧再生曲
                //   アンカー）のまま再生を始めると、アイコンの ON
                //   表示と実順序が食い違うため、必ずここで作り直す。
                rebuildOrderLocked(index)
                orderStale = false
            }
            startTrackLocked(index, 0L)
        }
    }

    fun play() {
        synchronized(stateLock) {
            if (isPlaying) return
            if (orderStale) {
                // playAt を経ない再生開始（ポーズ解除の再開など）でも、
                // 仮決めの順序表が残っていたら現在曲アンカーで確定
                // させてから進む（実効順の先頭を使う分岐の前提）。
                rebuildOrderLocked(currentIndex)
                orderStale = false
            }
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
                // 現在曲が決まっていない場合は実効再生順の先頭から
                // （シャッフル中はシャッフル順の先頭）。
                startTrackLocked(playOrder.firstOrNull() ?: 0, 0L)
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
            listener.onProgress(displayPositionMs(), durationMs, false)
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
            decodeFinished = false
            // 出力レート上限は出力経路の性質だが、モードを離れたら
            // 持ち越さない（次回は素のレートから試す）。
            outputRateCapHz = 0
            rateCapReopenCount = 0
        }
    }

    // ★ エラーで完全に止める（理由をユーザーへ通知する）。モード
    //   切替用の stop() と違い、状態通知と理由表示まで行う。
    fun stopWithError(message: String) {
        synchronized(stateLock) {
            isPlaying = false
            generation++
            stopAndReleaseDecoderLocked()
            positionMs = 0L
            pendingSeekMs = -1L
            decodeFinished = false
            listener.onState(false)
            listener.onPlaybackError(message)
        }
    }

    // ★ 出力側（Service）の都合でデコード出力レートに上限が必要に
    //   なったときに MainActivity 経由で呼ばれる（例: 本体出力が
    //   96kHz の AudioTrack を作れず、48kHz へ落とす必要がある）。
    //   上限は出力経路の性質なので曲をまたいで保持し（解除は
    //   stop() と clearOutputRateCap()）、上限より高いレートで
    //   デコード中の曲があれば、聞こえている位置から変換付きで
    //   開き直す。開き直し回数には上限を設け、変換しても進展
    //   しない場合（DSD 等、上限が効かない経路）は理由を出して
    //   止める（無限の開き直しループにしない）。
    fun applyOutputRateCap(capHz: Int) {
        synchronized(stateLock) {
            if (capHz <= 0) return
            outputRateCapHz = capHz
            val currentRate = currentInfo?.sampleRate ?: 0
            if (currentIndex !in queue.indices || currentRate <= capHz) return
            if (rateCapReopenCount >= MAX_RATE_CAP_REOPENS) {
                Log.w(
                    TAG,
                    "output rate cap ${capHz}Hz did not take effect " +
                        "after $rateCapReopenCount reopens"
                )
                isPlaying = false
                generation++
                stopAndReleaseDecoderLocked()
                listener.onState(false)
                listener.onPlaybackError("出力デバイスが対応するレートで再生できませんでした")
                return
            }
            rateCapReopenCount++
            Log.i(
                TAG,
                "applying output rate cap ${capHz}Hz (was ${currentRate}Hz), reopening"
            )
            startTrackLocked(currentIndex, displayPositionMs(), isDecodeRetry = true)
        }
    }

    // 出力デバイス変更等で上限が不要になった場合の解除。
    fun clearOutputRateCap() {
        synchronized(stateLock) {
            outputRateCapHz = 0
            rateCapReopenCount = 0
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
            // 順序表の次の位置へ（シャッフル中はシャッフル順の次曲）。
            val nextPosition = orderPosition + 1
            if (nextPosition in playOrder.indices) {
                startTrackLocked(playOrder[nextPosition], 0L)
            }
        }
    }

    fun prev() {
        synchronized(stateLock) {
            if (queue.isEmpty()) return
            // 再生開始から 3 秒以内は前曲、それ以外は曲頭へ（一般的なプレイヤーの作法）。
            // 判定はユーザーが聞いている位置（表示位置）で行う。
            if (isPlaying && displayPositionMs() > 3000L) {
                seekTo(0L)
                return
            }
            // 順序表の前の位置へ（シャッフル中は実際に前に再生した曲）。
            val prevPosition = orderPosition - 1
            if (prevPosition in playOrder.indices) {
                startTrackLocked(playOrder[prevPosition], 0L)
            } else {
                seekTo(0L)
            }
        }
    }

    fun seekTo(targetMs: Long) {
        synchronized(stateLock) {
            val dec = decoder ?: return
            val clamped = targetMs.coerceIn(0L, if (durationMs > 0) durationMs else targetMs)
            if (isPlaying && decodeFinished) {
                // デコードは既に終わっていてネイティブシークが効かない
                // （再生し切り待ちの間）。曲をシーク位置から開き直す。
                startTrackLocked(currentIndex, clamped)
                return
            }
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

    // ★ 現在曲のアートワークを取り直してリスナへ再送する。
    //   モード往復（Local → YT → Local）では MainActivity 側の
    //   アートワーク保持が切替時にクリアされる一方、LocalPlayer は
    //   曲開始時にしかアートワークを送らないため、保持中の曲の絵が
    //   音符プレースホルダのまま戻らなくなる。曲を再生し直さずに
    //   表示だけを復元するための入口（アートワークが無い曲では
    //   何も送られず、プレースホルダ表示が正となる）。
    fun refreshCurrentArtwork() {
        val track = currentTrack ?: return
        loadArtworkAsync(generation, track)
    }

    // ── 内部 ──

    // stateLock 保持必須。指定曲のデコーダを開いてデコードを開始する。
    // isDecodeRetry: デコードエラーのリトライとして開き直す場合のみ true
    // （リトライ位置の記録をリセットしないため）。
    private fun startTrackLocked(index: Int, startAtMs: Long, isDecodeRetry: Boolean = false) {
        val track = queue[index]
        val gen = ++generation
        if (!isDecodeRetry) {
            lastDecodeErrorLocusMs = -1L
            decodeErrorRetriesAtLocus = 0
            rateCapReopenCount = 0
        }

        // ★ フラッシュはデコーダのオープンより先に送る。Service の
        //   キューには前曲の未再生 PCM が数秒分残っているため、
        //   オープン（プローブ等で時間がかかる）の完了を待ってから
        //   フラッシュすると、その間も前曲が流れ続けて「次へを
        //   押しても切り替わらない」体感になる。世代は既に進めて
        //   あるため、旧デコード側のチャンクがフラッシュ後に
        //   紛れ込むことはない（コールバック冒頭の世代判定）。
        listener.onFlush()

        // 前のデコードを止めてデコーダを解放する
        stopAndReleaseDecoderLocked()

        currentIndex = index
        // 順序表上の現在位置を追随させる（不変条件:
        // playOrder[orderPosition] == currentIndex）。
        orderPosition = playOrder.indexOf(index)
        currentTrack = track
        positionMs = startAtMs
        pendingSeekMs = -1L
        decodeFinished = false

        val dec = FfmpegDecoder(context)
        decoder = dec
        dec.callback = object : FfmpegDecoder.Callback {
            override fun onPcmChunk(pcmBytes: ByteArray, chunkPositionMs: Long) {
                if (gen != generation) return
                // ★ フロー制御（バックプレッシャー）: Service のキューに
                //   空きができるまでデコードスレッド側で待つ。待たないと
                //   デコード速度で PCM が溢れ、Service 側で最古チャンクが
                //   破棄され続けて曲が先へ飛ぶ。待機中に世代が変わったら
                //   （ポーズ/停止/曲変更）このチャンクは捨てて戻る
                //   — 再開・再開始側が正しい位置からデコードし直す。
                if (!awaitQueueRoom(gen)) return
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
        val opened = dec.openUri(track.uri, dsdRate, outputRateCapHz)
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
        // ★ フラッシュは開始直後に送ってある（上の onFlush 参照）。
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
        // ★ 世代ガード: このタスクが executor に積まれてから実行される
        //   までの間に世代が進んでいたら（停止→再開の連打、曲タップ
        //   直後の一時停止など）、native ループへ入らない。native 側は
        //   入場時に stopRequested をクリアするため、ここを素通りすると
        //   「積まれた後に発行された停止要求」が破棄され、古い世代の
        //   タスクがファイル全体を無音でデコードし尽くすまで単一
        //   スレッドの decodeExecutor を占有してしまう（後続の本物の
        //   再開タスクが回らず、音が出ない・シークバーが進まない
        //   状態が続く。DSD 等の遅いデコードで顕著）。
        if (gen != generation) return
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
            decodeFinished = true
            if (error) {
                // ★ デコードエラーで即・全停止にしない。一時的な読み取り
                //   失敗（SAF 越しの瞬断など）やコーデックの一時的な
                //   異常でも、聞こえている位置から開き直せば再開できる
                //   ことが多い。
                // ★ リトライ可否は「デコード位置（読み取り位置）」で
                //   判定する。旧方式は表示位置＋3秒ルールだったが、
                //   リトライは表示位置から開き直すため、再開した
                //   デコーダはキュー滞留分だけ先にある同じ不良箇所へ
                //   ほぼ即座に再到達し、その時点で表示位置は 3 秒
                //   進んでおらず、2 回目のエラーが常に対策をすり
                //   抜けて全停止していた。デコード位置同士なら同じ
                //   不良箇所はほぼ同じ位置になるため、「同一位置
                //   近傍のエラー」を数えて上限を設ける形に変える
                //   （上限到達 = その箇所は恒常的に読めないとみなし
                //   て停止し、理由をユーザーへ出す。破損ファイルで
                //   止まる従来の意図は維持される）。
                val errorPosMs = positionMs // デコード位置（最後に送出したチャンクの位置）
                val sameLocus = lastDecodeErrorLocusMs >= 0L &&
                    abs(errorPosMs - lastDecodeErrorLocusMs) <=
                    DECODE_RETRY_SAME_LOCUS_WINDOW_MS
                val retriesAtLocus = if (sameLocus) decodeErrorRetriesAtLocus else 0
                if (currentIndex in queue.indices &&
                    retriesAtLocus < DECODE_RETRY_MAX_PER_LOCUS
                ) {
                    lastDecodeErrorLocusMs = errorPosMs
                    decodeErrorRetriesAtLocus = retriesAtLocus + 1
                    val retryAtMs = displayPositionMs()
                    Log.w(
                        TAG,
                        "decode error at index=$currentIndex decodePos=${errorPosMs}ms, " +
                            "reopening at ${retryAtMs}ms " +
                            "(retry ${retriesAtLocus + 1}/$DECODE_RETRY_MAX_PER_LOCUS at this locus)"
                    )
                    startTrackLocked(currentIndex, retryAtMs, isDecodeRetry = true)
                    return
                }
                Log.w(
                    TAG,
                    "decode ended with error at index=$currentIndex decodePos=${errorPosMs}ms"
                )
                isPlaying = false
                listener.onState(false)
                listener.onProgress(displayPositionMs(), durationMs, false)
                // ★ 無言で止めない: 停止理由をユーザーへ見せる
                listener.onPlaybackError("読み取りエラーのため再生を停止しました")
                return
            }
            // ★ デコード完了 ≠ 再生完了。Service のキューには未再生の PCM が
            //   最大で容量分残っている。ここですぐ次曲へ進むと onFlush で
            //   前曲の末尾が切り捨てられ、最終曲では onState(false) で
            //   Service 側の消費自体が止まり末尾が再生されない。再生し切る
            //   まで待ってから進む/終える（scheduleAdvanceAfterDrain）。
            //   その間 isPlaying は true のまま（音はまだ出ている）。
            scheduleAdvanceAfterDrain(gen)
        }
    }

    // デコード完了後、Service のキューが空になる（＋ AudioTrack 側に残った
    // 分が流れ切るまでの猶予）まで待ち、世代が変わらなければ次曲へ進むか、
    // 最終曲なら終了処理を行う。待ちは decodeExecutor 上で stateLock を
    // 保持せずに行う（その間のポーズ/停止/曲変更は世代変更で検知して中断）。
    private fun scheduleAdvanceAfterDrain(gen: Int) {
        decodeExecutor.execute { waitForDrainThenAdvance(gen) }
    }

    // decodeExecutor 上で動く（stateLock は最後の確定時のみ取る）。
    private fun waitForDrainThenAdvance(gen: Int) {
        var emptySinceMs = -1L
        while (gen == generation) {
            val size = queueSizeProvider?.invoke() ?: 0
            val now = SystemClock.elapsedRealtime()
            if (size > 0) {
                emptySinceMs = -1L
            } else if (emptySinceMs < 0L) {
                emptySinceMs = now
            } else if (now - emptySinceMs >= DRAIN_SETTLE_MS) {
                break
            }
            try {
                Thread.sleep(DRAIN_POLL_INTERVAL_MS)
            } catch (e: InterruptedException) {
                return
            }
        }
        if (gen != generation) return
        synchronized(stateLock) {
            if (gen != generation) return
            // ★ 曲の終わり → 次曲へ自動送り。行き先はシャッフル /
            //   リピート設定を反映して決める（仕様書 §4.2 + Now Playing）
            val nextIndex = computeAutoAdvanceIndexLocked()
            if (nextIndex >= 0) {
                startTrackLocked(nextIndex, 0L)
            } else {
                // キュー末尾まで再生し切った
                isPlaying = false
                listener.onState(false)
                listener.onProgress(positionMs, durationMs, false)
                positionMs = 0L
                decoder?.release()
                decoder = null
                decodeFuture = null
                currentInfo = null
            }
        }
    }

    // ★ フロー制御: Service のキュー残量が高水位（容量の 75%）以上なら、
    //   低水位（容量の 50%）まで下がるのを待つ（ヒステリシス付き）。
    //   デコードスレッドから呼ばれる。世代が変わるか割り込まれたら false
    //   （＝このチャンクは捨てる）。プロバイダ未配線なら常に true。
    private fun awaitQueueRoom(gen: Int): Boolean {
        val sizeProvider = queueSizeProvider ?: return true
        val capacity = queueCapacityProvider?.invoke() ?: 0
        if (capacity <= 0) return true
        val highWater = (capacity * QUEUE_HIGH_WATER_RATIO).toInt().coerceAtLeast(1)
        if (sizeProvider() < highWater) return true
        val lowWater = (capacity * QUEUE_LOW_WATER_RATIO).toInt().coerceAtLeast(0)
        while (gen == generation) {
            if (sizeProvider() <= lowWater) return true
            try {
                Thread.sleep(QUEUE_POLL_INTERVAL_MS)
            } catch (e: InterruptedException) {
                return false
            }
        }
        return false
    }

    // Service のキューに溜まっている未再生 PCM の時間（現在曲の ms 換算）。
    // 1 チャンク = CHUNK_FRAMES フレーム（ソースレート基準）として計算する
    // ため、後段のアップサンプリング倍率には依存しない。
    private fun bufferedMs(): Long {
        val size = queueSizeProvider?.invoke() ?: return 0L
        if (size <= 0) return 0L
        val rate = currentInfo?.sampleRate ?: currentTrack?.sampleRate ?: 0
        if (rate <= 0) return 0L
        return size.toLong() * CHUNK_FRAMES * 1000L / rate
    }

    // 表示用の再生位置。positionMs は「Service へ渡し終えた位置」（デコード
    // 位置）なので、未再生バッファ分を差し引いて実際に鳴っている位置へ寄せる。
    private fun displayPositionMs(): Long =
        (positionMs - bufferedMs()).coerceAtLeast(0L)

    private var consecutiveOpenFailures = 0

    // デコードエラーのリトライ管理（デコード位置基準）。
    // lastDecodeErrorLocusMs は直近にリトライしたエラーのデコード
    // 位置、decodeErrorRetriesAtLocus はその位置近傍で既に使った
    // リトライ回数。新しい曲の開始（リトライでない startTrackLocked）
    // でリセットされる。
    private var lastDecodeErrorLocusMs = -1L
    private var decodeErrorRetriesAtLocus = 0

    // 出力レート上限（Hz、0 = 上限なし）。Service 側で AudioTrack を
    // 作れないレートだった場合に、デコード側（既存の swresample
    // 経路）で変換して供給するための上限。出力経路の性質なので
    // 曲をまたいで保持する。
    private var outputRateCapHz = 0
    private var rateCapReopenCount = 0

    private fun autoAdvanceAfterFailure(gen: Int, failedIndex: Int) {
        // startTrackLocked 内（stateLock 保持中）から呼ばれる前提
        consecutiveOpenFailures++
        if (consecutiveOpenFailures > MAX_CONSECUTIVE_FAILURES) {
            consecutiveOpenFailures = 0
            // ★ 無言で止めない: 停止理由をユーザーへ見せる
            listener.onPlaybackError("再生できない曲が続いたため停止しました")
            return
        }
        // 開けない曲は実効再生順の次へ送る（順序表に無い場合は
        // キュー内の次の曲で代替する）。
        val failedPosition = playOrder.indexOf(failedIndex)
        val nextIndex = if (failedPosition >= 0) {
            playOrder.getOrNull(failedPosition + 1)
        } else {
            (failedIndex + 1).takeIf { it < queue.size }
        }
        if (nextIndex != null && gen == generation) {
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

    // 進捗の定期通知用に現在位置を返す（MainActivity のタイマから参照）。
    // 位置はデコード位置ではなく、未再生バッファを差し引いた再生位置。
    fun positionSnapshot(): Pair<Long, Long> = displayPositionMs() to durationMs

    companion object {
        private const val TAG = "LocalPlayer"
        private const val MAX_CONSECUTIVE_FAILURES = 3
        // ネイティブ側のチャンク粒度（cpp の CHUNK_FRAMES、Service の想定と一致）
        private const val CHUNK_FRAMES = 4096L
        // フロー制御の水位（Service キュー容量に対する比率）とポーリング間隔
        private const val QUEUE_HIGH_WATER_RATIO = 0.75
        private const val QUEUE_LOW_WATER_RATIO = 0.50
        private const val QUEUE_POLL_INTERVAL_MS = 25L
        // 再生し切り判定: キューが空になってから AudioTrack 側の残りが
        // 流れ切るまで待つ猶予と、そのポーリング間隔
        private const val DRAIN_POLL_INTERVAL_MS = 50L
        private const val DRAIN_SETTLE_MS = 500L
        // デコードエラーのリトライ: 同一位置近傍とみなす幅と、その
        // 位置近傍でのリトライ上限（デコード位置基準。表示位置＋
        // 3秒進展ルールだった旧方式は、再開デコーダが同じ不良
        // 箇所へ即再到達して常に対策をすり抜けたため置き換えた）
        private const val DECODE_RETRY_SAME_LOCUS_WINDOW_MS = 3000L
        private const val DECODE_RETRY_MAX_PER_LOCUS = 2
        // 出力レート上限の適用（変換付きで開き直す）の上限回数
        private const val MAX_RATE_CAP_REOPENS = 3
    }
}
