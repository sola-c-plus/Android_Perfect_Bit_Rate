package com.example.perfectbitrate

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioMixerAttributes
import android.media.AudioTrack
import android.net.wifi.WifiManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.media.VolumeProviderCompat
import androidx.media.app.NotificationCompat.MediaStyle
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max

class BitPerfectPlaybackService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    var audioTrack: AudioTrack? = null
    private lateinit var audioManager: AudioManager

    var baseSampleRate = 48000
    var effectiveSampleRate = 48000

    // ★ レートの要求/実績（Phase 4 のビットモード分離と同型）:
    //   lastRequestedTargetRate は doInit が最後に受け取った「要求」目標レート。
    //   effectiveSampleRate は交渉後の「実績」レートを保持するため、pushPcm の
    //   再生成判定は要求同士（こちら）で比較しないと、クランプ発生時に毎回再初期化される。
    private var lastRequestedTargetRate = 0

    // ★ 交渉で確定した実績倍率。native エンジンは doInit でこの倍率で初期化されるため、
    //   nativeProcessUpsample へは要求倍率ではなくこちらを渡す。
    //   初回 init 前は pushPcm が必ず再生成経路に入り native へ到達しないため初期値 1 でよい。
    private var negotiatedFactor = 1

    // ★ Phase 4: ビットモードの要求値。ユーザー設定であり、Prefs.selectedBitMode と
    //   同期する唯一の永続値。doInit の交渉はこの値を入力として受け取る。
    //   交渉結果をここへ書き戻してはならない（フォールバックで保存設定が劣化するため）。
    var requestedBitMode = "16bit"

    // ★ Phase 4: ビットモードの実績値。doInit の交渉結果であり読取専用。
    //   表示と実ストリームの解釈（native 出力形式・ピーク解析・フレーム数換算）はこちらを使う。
    //   フォールバックが起きても requestedBitMode は不変で、次回 init は要求を再試行する。
    var actualBitMode = "16bit"
        private set

    // ★ ユーザーが選択した本来の設定倍率 (Direct Source ON でも絶対に破壊せず保持)
    var upsampleFactor = 1
        set(value) {
            val valid = when (value) {
                2 -> 2
                4 -> 4
                8 -> 8
                else -> 1
            }
            field = valid
        }

    var isDirectSource = false
        set(value) {
            field = value
            NativeAudioEngine.nativeSetDirectSource(value)
        }

    // ★ 実行時の実効倍率 (Direct Source ON 時は 1x、OFF 時はユーザー設定値)
    val effectiveFactor: Int
        get() = if (isDirectSource) 1 else upsampleFactor

    var activeOutputDevice: AudioDeviceInfo? = null
    private val audioLock = ReentrantLock()

    private val trackExecutor = Executors.newSingleThreadExecutor()
    private val isInitializingTrack = AtomicBoolean(false)
    private val hasPendingInit = AtomicBoolean(false)
    // ★ 保留再実行の上限（案B: initAudioTrack の do-while が要求の
    //   連打で無限に回らないための保険）。署名が変わらない再要求は
    //   結果が同じため数えずに捨て、署名が変わる再実行もこの回数で
    //   打ち切る。詳細は initAudioTrack 参照。
    private val MAX_PENDING_INIT_ROUNDS = 3
    // ★ initAudioTrack が初期化を実際に実行した回数（案C で
    //   switchStreamConfiguration のタスクが「自分の呼び出しが
    //   実行したか、保留を立てただけか」を判定するために使う）。
    @Volatile
    private var initExecutionCount = 0
    private val isSwitchingRate = AtomicBoolean(false)
    private var lastConfiguredMixerDevice: AudioDeviceInfo? = null

    // ★ 現在の供給元が Local（LocalPlayer）かどうか（MainActivity が
    //   モード切替時に設定する）。ストリーム構成の切替中（isSwitchingRate）
    //   の pushPcm の扱いを供給元で分けるために使う: YT は実時間供給の
    //   ため切替中のチャンクは破棄してよいが、Local はデコード側が
    //   Service キュー残量で律速される前提のため、破棄するとキューが
    //   空のままデコードだけが走り切ってしまう（awaitSwitchComplete 参照）。
    @Volatile
    var isLocalSourceActive = false

    // ストリーム構成切替の完了待ち（Local 供給時）。切替は trackExecutor
    // 上の AudioTrack 再初期化で、通常は短時間で終わる。上限は安全弁で、
    // 超えた場合は従来どおり破棄に倒す（デコードを無期限には止めない）。
    private val SWITCH_WAIT_MAX_MS = 3000L
    private val SWITCH_WAIT_POLL_MS = 5L

    // ★ ポーズで閉じた AudioTrack の再生成時に、停止前のシステム音量を維持するための保留記録。
    //   ウィジェット/通知ポーズは forceCloseDacStream() で AudioTrack を破棄するため、再再生時の
    //   再生成で restoreVolumeForDevice() が走り、接続/切断時の安全策（USB は音量0から開始、
    //   デバイスなしは forceResetSpeakerVolume で 0 化）が誤って適用され、音量が 0 のまま残っていた。
    //   forceCloseDacStream() が閉じた時点の出力デバイスを記録し、同一デバイスへの再生成でのみ
    //   安全側の 0 化をスキップする。デバイスが変わる再生成や切断フローでは従来どおり安全側に倒す。
    @Volatile private var volumePreservePending = false
    @Volatile private var volumePreserveDeviceId = -1 // -1 = デバイスなし（内蔵スピーカー）

    // ★ 最後に生成へ成功した AudioTrack の出力デバイス ID（-1 = デバイスなし
    //   ＝内蔵スピーカー）。まだ生成していない状態は Int.MIN_VALUE で区別する。
    //   restoreVolumeForDevice() の「接続時は音量 0 から開始」はデバイス接続
    //   イベントに対する安全策だが、tryCreateTrack は同一デバイスでの構成変更
    //   （サンプルレート切替・モード往復・DSP 構成変更）に伴う再生成でも呼ばれる
    //   ため、そのままでは再生のたびにユーザーが上げた音量が 0 へ戻ってしまう。
    //   そこで、再生成先が「前回生成したトラックと同じデバイス」なら接続では
    //   なく構成変更とみなし、安全側の 0 化をスキップしてセッション中の音量を
    //   維持する（ポーズ由来の保留＝ consumeVolumePreserve と併用する判定）。
    //   デバイス変更（setOutputDevice の changed）と切断フロー
    //   （handleBecomingNoisyOrDisconnected）ではこの記録をリセットし、
    //   次回生成は従来どおり安全側（0 から開始）に倒す。
    @Volatile private var lastCreatedTrackDeviceId = Int.MIN_VALUE

    private val MAX_QUEUE_CAPACITY = 64
    val pcmQueue = LinkedBlockingQueue<ByteArray>(MAX_QUEUE_CAPACITY)

    // ★ Local 再生（LocalPlayer）のフロー制御・進捗補正用の読み取り口。
    //   キューの操作（offer/poll/clear）自体は従来どおり Service 内部と
    //   pushPcm / resetBuffer 経由に限る。ここは残量の参照だけを公開する。
    val pcmQueueSize: Int get() = pcmQueue.size
    val pcmQueueCapacity: Int get() = MAX_QUEUE_CAPACITY

    // ★ プリロール閾値（再生開始・再開時に何チャンク溜まってから出し始めるか）。
    //   1チャンク = 4096フレーム。48kHz 換算で 1チャンク ≒ 85ms なので、
    //   6チャンク ≒ 0.5秒分の先読みになる（実時間はソースのサンプルレートで変動）。
    //   旧値3（≒256ms）では数百ms 級の供給瞬断でキューが枯れ、再開時に遅延分が
    //   吸着して「一瞬進みが飛ぶ」症状2になっていたため引き上げた（案8）。
    //   吸収力と操作感（開始遅延）の調整はこの1箇所の値だけで行う。
    private val PREROLL_THRESHOLD = 6
    private val isBuffering = AtomicBoolean(true)

    // ★ Local の曲切り替え/シーク直後だけ使う小さいプリロール。
    //   Local の供給は端末内デコードで速く安定しているため、YT 側の
    //   供給瞬断対策である 6 チャンクまで待つ必要がなく、切り替え後の
    //   再開を早められる。アームは resetBuffer("local") でのみ行い、
    //   再開が成立した時点で消費される（再生中のアンダーラン回復では
    //   使われず、従来どおり 6 チャンクで復帰する）。YT 由来の
    //   フラッシュ（"page" / "mode"）では必ず解除し、YT 経路の
    //   再開条件には影響させない。
    private val LOCAL_PREROLL_THRESHOLD = 3
    @Volatile private var localFastPrerollArmed = false
    private fun activePrerollThreshold(): Int =
        if (localFastPrerollArmed) LOCAL_PREROLL_THRESHOLD else PREROLL_THRESHOLD

    // ★ バッファ破棄の計測カウンタ（診断用、サービス起動以来の累計。再生挙動には影響しない）
    val bufferDropCount = AtomicLong(0)     // キューフルで最古パケットを落とした回数
    val bufferDropMs = AtomicLong(0)        // 落とした音の合計時間 (ms)
    val bufferFlushCount = AtomicLong(0)    // resetBuffer による flush の総回数
    val bufferFlushPageCount = AtomicLong(0) // うちページ由来 ({type:"flush"})
    val bufferUnderrunCount = AtomicLong(0) // 再生中にキューが枯れた回数
    val bufferResumeCount = AtomicLong(0)   // アンダーラン後にプリロール再開した回数
    private val underrunOpen = AtomicBoolean(false) // アンダーラン中（再開待ち）か

    // ★ True Peak ガードの低減量の表示専用キャッシュ。低減量の正は native であり、
    //   ここは nativeGetTruePeakStats のポーリング結果を保持するだけ。
    //   要求値や Prefs へは絶対に書き戻さない（状態統一 Phase 4 の原則）。
    @Volatile var truePeakCurrentReductionDb: Float = 0f
    @Volatile var truePeakMaxReductionDb: Float = 0f
    @Volatile var truePeakActivationCount: Long = 0L
    @Volatile var truePeakCapHitCount: Long = 0L
    private val truePeakStatsBuffer = FloatArray(4)

    // ★ スペクトラム表示の「再生時刻整列」用スナップショット保持。
    //   native のスペクトラムは DSP 処理時点の音（リングバッファ末尾）から
    //   算出されるため、Local のように処理が再生より数秒先行する供給では、
    //   表示側が最新値をそのまま読むと「バースト時に一括算出 → 次のバースト
    //   まで完全静止」の凍結→ジャンプになる。そこで pushPcm がチャンクを
    //   処理するたびに、その時点のバンドレベルを「処理済み累積フレーム位置
    //   （出力フレーム単位）」と組にして保持し、表示側は
    //   getSpectrumAlignedToPlayback() で「今鳴っている位置」に最も近い
    //   スナップショットを受け取る。保持は先行分（キュー最大 64 チャンク＋
    //   ハードウェアバッファ）を賄う 128 件で十分なため、コストは
    //   チャンク毎の小さなコピーと取得時の線形走査だけに抑える。
    private val SPECTRUM_BAND_COUNT = 32
    private val SPECTRUM_RING_CAPACITY = 128
    private val spectrumSnapshotLock = Any()
    private val spectrumSnapshotPositions = LongArray(SPECTRUM_RING_CAPACITY)
    private val spectrumSnapshotLevels =
        Array(SPECTRUM_RING_CAPACITY) { FloatArray(SPECTRUM_BAND_COUNT) { -60f } }
    private var spectrumSnapshotWriteIndex = 0
    private var spectrumSnapshotCount = 0
    // 処理済み累積フレーム数（出力フレーム単位）。totalWrittenFrames と同じ
    // 時刻軸で数え、リセットも totalWrittenFrames = 0 の箇所と同期させる。
    @Volatile private var spectrumProcessedFrames = 0L
    private val spectrumCaptureBuffer = FloatArray(SPECTRUM_BAND_COUNT)

    // ★ レベルメーター表示の「再生時刻整列」用スナップショット保持。
    //   ピーク値そのものは再生ループがチャンクを取り出すたびに算出される
    //   （analyzeAndDispatchPeak）が、その配送はチャンク処理の到着駆動で、
    //   Local のバースト供給（フロー制御のヒステリシスで約1.5秒分を一括
    //   処理し、キューは 32〜48 チャンク先行）では配送間隔が空いたり
    //   固まったりして、メーターが凍結→ジャンプになる（YT では供給が
    //   実時間ペースで再生ループも等間隔に回るため目立たない）。
    //   そこで、AudioTrack への書込みが成功したチャンクについて、その
    //   ピークを「書込み済み累積フレーム位置（チャンク終端）」と組で
    //   保持し、表示側は getPeakAlignedToPlayback() で再生ヘッド位置に
    //   あるチャンクのピークを受け取る（スペクトラムの 3ae6aee と同方式、
    //   ただしこちらは処理時刻ではなく書込み時刻の時刻軸）。
    //   保持は先行分（AudioTrack バッファ約0.5秒＋余裕）を賄う 128 件で
    //   十分。リセットは totalWrittenFrames = 0 の箇所（スペクトラムと
    //   同じ resetSpectrumTimeline）に同期させる。
    private val PEAK_RING_CAPACITY = 128
    private val peakSnapshotLock = Any()
    private val peakSnapshotPositions = LongArray(PEAK_RING_CAPACITY)
    private val peakSnapshotDbL = FloatArray(PEAK_RING_CAPACITY) { -60f }
    private val peakSnapshotDbR = FloatArray(PEAK_RING_CAPACITY) { -60f }
    private var peakSnapshotWriteIndex = 0
    private var peakSnapshotCount = 0

    // ★ 再生ヘッド位置の「表示時刻軸」における基点。
    //   AudioTrack.playbackHeadPosition は AudioTrack インスタンス生成が
    //   起点の累積値で、flush() では 0 に戻らず「その時点までに書いた
    //   総フレーム数」へ跳ぶ。一方 totalWrittenFrames やスナップショット
    //   位置は resetBuffer 等で 0 から数え直すため、両者を直接比較すると
    //   フラッシュを挟んだ途端に軸がずれる（ピーク取得が常時不一致に
    //   なり、スペクトラムの整列も処理位置へ退化する）。そこで
    //   totalWrittenFrames = 0 の箇所（resetSpectrumTimeline）で、その
    //   時点のヘッド値を基点として記録し、表示系の再生位置は必ず
    //   「ヘッド − 基点」で求める（playedFramesInTimeline）。
    @Volatile private var playbackHeadBaseFrames = 0L

    // 表示時刻軸（totalWrittenFrames と同じエポック）での再生済み
    // フレーム数。基点より前には戻らないため 0 未満にはならない。
    private fun playedFramesInTimeline(): Long {
        val head = (audioTrack?.playbackHeadPosition?.toLong() ?: 0L) and 0xFFFFFFFFL
        return (head - playbackHeadBaseFrames).coerceAtLeast(0L)
    }

    @Volatile private var isRunning = false
    private var playbackThread: Thread? = null

    // ★ (peakDbL, peakDbR, bitMask, queueSize, latencyMs)
    var onPeakListener: ((Float, Float, Int, Int, Int) -> Unit)? = null
    // ★ Phase 4: 実績値の通知は表示専用。受け手は要求値や Prefs へ書き戻してはならない。
    var onActualBitModeChanged: ((String) -> Unit)? = null

    // ★ ストリーム構成の結果通知（MainActivity が表示と Local 側の
    //   対処に使う）。いずれも initAudioTrack の実行スレッドから
    //   呼ばれるため、受け手は UI 操作を UI スレッドへ寄せること。
    // レートフォールバック発生（要求レート → 実績レート）。整数倍に
    // 落とす範囲のフォールバック（アップサンプリング倍率の切り下げ）。
    var onStreamRateFallback: ((requestedRate: Int, actualRate: Int) -> Unit)? = null
    // 出力レート上限の要求: ソースレートそのもので AudioTrack を作れ
    // なかったため、供給側（Local のデコーダ）にこのレートへの変換を
    // 求める通知。受け手が上限を適用して再供給すると、その レートで
    // 再構成が走り直す。
    var onOutputRateCapNeeded: ((capRateHz: Int) -> Unit)? = null
    // 構成失敗の上限到達: 同じ構成で MAX_STREAM_INIT_FAILURES 回連続
    // して AudioTrack を作れなかった（理由は「構成できません」として
    // ユーザーへ出し、Local 供給時はその曲を終えるための通知）。
    var onStreamConfigurationFailed: ((baseRate: Int, targetRate: Int) -> Unit)? = null

    // doInit が記録し、initAudioTrack が消費する保留中の構成結果。
    private var pendingRateFallback: Pair<Int, Int>? = null
    private var pendingOutputRateCapHz = 0

    // ★ 構成失敗の連続回数（同じ構成＝署名が同じ間だけ数える）。
    //   pushPcm の再構成ループに上限を設けるための記録で、成功する
    //   か構成（署名）が変わればリセットされる。レート上限の適用で
    //   回復が進んでいる間（pendingOutputRateCapHz が出た失敗）は
    //   数えない（上限適用は「一段下げる」進行そのもののため）。
    private var streamInitFailureCount = 0
    private var streamInitFailureSignature: String? = null
    private var streamFailureNotified = false
    private val MAX_STREAM_INIT_FAILURES = 3

    // ソースレート未満へのフォールバック先候補（高い順）。出力
    // デバイスの対応レートが取れない経路（本体出力等）で使う。
    private val STANDARD_FALLBACK_RATES =
        listOf(48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000)

    // ★ Phase 0: 表示用スナップショット。ライブ再生状態の正は Service であり、
    //   MainActivity はこれを「表示キャッシュ」として読む（ミラー変数の代わり）。
    data class PlaybackStateSnapshot(
        val effectiveSampleRate: Int,
        val effectiveFactor: Int,
        val actualBitMode: String,
        val isDirectSource: Boolean,
        val isPlaying: Boolean,
        val title: String,
        val artist: String,
        val positionMs: Long,
        val durationMs: Long,
        val artwork: Bitmap?
    )

    var onPlaybackStateChanged: ((PlaybackStateSnapshot) -> Unit)? = null

    fun getPlaybackStateSnapshot(): PlaybackStateSnapshot {
        return PlaybackStateSnapshot(
            effectiveSampleRate = effectiveSampleRate,
            effectiveFactor = effectiveFactor,
            actualBitMode = actualBitMode,
            isDirectSource = isDirectSource,
            isPlaying = isCurrentlyPlaying,
            title = currentTitle,
            artist = currentArtist,
            positionMs = currentPosition,
            durationMs = currentDuration,
            artwork = currentArtworkBitmap
        )
    }

    private fun notifyPlaybackStateChanged() {
        onPlaybackStateChanged?.invoke(getPlaybackStateSnapshot())
    }

    private var totalWrittenFrames = 0L

    var isVolumeLocked = false
        set(value) {
            field = value
            updateVolumeControlMode()
        }

    private lateinit var mediaSession: MediaSessionCompat
    var onCommandListener: ((String) -> Unit)? = null
    var onSeekListener: ((Long) -> Unit)? = null

    private var currentTitle = "Perfect Bit Rate"
    private var currentArtist = "YouTube Music"
    private var currentCodec = "Opus 160kbps"
    private var currentDuration = 0L
    private var currentPosition = 0L
    @Volatile var isCurrentlyPlaying = false
    private var currentArtworkBitmap: Bitmap? = null
    private val imageExecutor = Executors.newSingleThreadExecutor()

    private var lastVolumeKeyTime = 0L
    private val volumeProvider = object : VolumeProviderCompat(
        VOLUME_CONTROL_RELATIVE,
        100,
        100
    ) {
        override fun onAdjustVolume(direction: Int) {
            val now = SystemClock.elapsedRealtime()
            if (now - lastVolumeKeyTime < 350L) return
            lastVolumeKeyTime = now

            lockSystemVolumeToMax()
            if (direction > 0) {
                onCommandListener?.invoke("next")
            } else if (direction < 0) {
                onCommandListener?.invoke("prev")
            }
            if (direction != 0) {
                // リモート再生扱いのこの経路は MainActivity のキー処理を通らないため、
                // 曲送りが実際に発火した時に合わせて触覚フィードバックも鳴らす
                this@BitPerfectPlaybackService.hapticTick(AppPreferences.get())
            }
        }
    }

    fun updateVolumeControlMode() {
        try {
            if (isVolumeLocked && isUsbDevice(activeOutputDevice)) {
                lockSystemVolumeToMax()
                mediaSession.setPlaybackToRemote(volumeProvider)
            } else {
                mediaSession.setPlaybackToLocal(AudioManager.STREAM_MUSIC)
            }
        } catch (e: Exception) {
            Log.e("BitPerfect", "Volume mode update error", e)
        }
    }

    private val volumeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (isVolumeLocked && isUsbDevice(activeOutputDevice)) {
                lockSystemVolumeToMax()
            }
        }
    }

    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                handleBecomingNoisyOrDisconnected()
            }
        }
    }

    private val binder = LocalBinder()

    inner class LocalBinder : Binder() {
        fun getService(): BitPerfectPlaybackService = this@BitPerfectPlaybackService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        // AppPreferences の初期化は MainActivity.onCreate だけに依存していたため、
        // Service 単独で生成される経路では onAdjustVolume の AppPreferences.get() が
        // 未初期化で IllegalStateException になりうる。init は冪等（初期化済みなら
        // 何もしない）ので、Service 側でも初期化を保証しておく。
        AppPreferences.init(this)
        NativeAudioEngine.nativeInit()

        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PerfectBitRate::ServiceWakeLock")
        wakeLock?.acquire()

        try {
            val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            wifiLock = wifiManager?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "PerfectBitRate::WifiLock")
            wifiLock?.acquire()
        } catch (e: Exception) {
            Log.w("BitPerfect", "WifiLock acquisition failed", e)
        }

        try {
            ContextCompat.registerReceiver(
                this,
                volumeReceiver,
                IntentFilter("android.media.VOLUME_CHANGED_ACTION"),
                ContextCompat.RECEIVER_EXPORTED
            )
            ContextCompat.registerReceiver(
                this,
                noisyReceiver,
                IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
                ContextCompat.RECEIVER_EXPORTED
            )
        } catch (e: Exception) {
            Log.e("BitPerfect", "Receiver registration error", e)
        }

        createNotificationChannel()
        setupMediaSession()

        startPlaybackLoop()
        updateNotification()
        PlayerWidgetProvider.updateAllWidgets(this, currentTitle, currentArtist, currentArtworkBitmap, isCurrentlyPlaying, currentPosition, currentDuration)
    }

    fun isUsbDevice(device: AudioDeviceInfo?): Boolean {
        if (device == null) return false
        return device.type == AudioDeviceInfo.TYPE_USB_DEVICE || device.type == AudioDeviceInfo.TYPE_USB_HEADSET
    }

    fun isBluetoothDevice(device: AudioDeviceInfo?): Boolean {
        if (device == null) return false
        return device.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
               (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && (
                   device.type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
                   device.type == AudioDeviceInfo.TYPE_BLE_SPEAKER ||
                   device.type == AudioDeviceInfo.TYPE_BLE_BROADCAST
               )) ||
               device.type == AudioDeviceInfo.TYPE_HEARING_AID
    }

    fun lockSystemVolumeToMax() {
        try {
            if (isUsbDevice(activeOutputDevice)) {
                val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, max, 0)
                audioTrack?.setVolume(1.0f)
            }
        } catch (e: Exception) {
            Log.e("BitPerfect", "Volume lock error", e)
        }
    }

    fun muteVolumeToZero() {
        try {
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
        } catch (e: Exception) {}
    }

    fun forceResetSpeakerVolume() {
        muteVolumeToZero()
        trackExecutor.execute {
            try {
                Thread.sleep(100)
                muteVolumeToZero()
                Thread.sleep(200)
                muteVolumeToZero()
            } catch (e: Exception) {}
        }
    }

    fun restoreVolumeForDevice(device: AudioDeviceInfo?) {
        try {
            if (isUsbDevice(device)) {
                if (isVolumeLocked) {
                    lockSystemVolumeToMax()
                } else {
                    // ★ DAC接続時は安全のため音量0から開始
                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
                    audioTrack?.setVolume(1.0f)
                }
            } else if (device == null) {
                forceResetSpeakerVolume()
            }
        } catch (e: Exception) {}
    }

    // ★ ポーズ（forceCloseDacStream）で閉じた AudioTrack の再生成かどうかを判定し、保留を消費する。
    //   同一デバイスへの再生成なら true を返し、呼び出し側は restoreVolumeForDevice() の安全側 0 化を
    //   スキップして停止前の音量をそのまま維持する（ストリーム音量には触らない）。
    //   Vol Lock 中はロック側（最大化）が正なので対象外。デバイスが変わっていた場合も保留を破棄して
    //   false を返し、従来の安全側動作（接続時は 0 から開始）に倒す。
    private fun consumeVolumePreserve(targetDevice: AudioDeviceInfo?): Boolean {
        if (!volumePreservePending) return false
        volumePreservePending = false
        if (isVolumeLocked) return false
        return (targetDevice?.id ?: -1) == volumePreserveDeviceId
    }

    fun handleBecomingNoisyOrDisconnected() {
        // 切断フローでは音量 0 化こそが意図的な安全策なので、ポーズ由来の音量維持の保留は破棄する。
        // あわせて「前回生成デバイス」の記録も破棄し、再接続後の初回生成を安全側（0 から開始）に戻す
        volumePreservePending = false
        lastCreatedTrackDeviceId = Int.MIN_VALUE
        isVolumeLocked = false
        isCurrentlyPlaying = false
        updateVolumeControlMode()

        try {
            audioTrack?.setVolume(0f)
            audioTrack?.pause()
            audioTrack?.flush()
        } catch (e: Exception) {}

        pcmQueue.clear()
        isBuffering.set(true)
        NativeAudioEngine.nativeResetUpsampler()
        totalWrittenFrames = 0L
        resetSpectrumTimeline()
        onPeakListener?.invoke(-60f, -60f, 0, 0, 0)

        forceResetSpeakerVolume()
        onCommandListener?.invoke("pause")

        trackExecutor.execute {
            audioLock.lock()
            try {
                audioTrack?.let { track ->
                    try {
                        track.stop()
                        track.release()
                    } catch (e: Exception) {
                        Log.w("BitPerfect", "AudioTrack release on disconnect", e)
                    }
                }
                audioTrack = null
                clearPreviousMixerAttributes()
            } finally {
                audioLock.unlock()
            }
        }

        updatePlaybackState(false)
        updateNotification()
        PlayerWidgetProvider.updateAllWidgets(this, currentTitle, currentArtist, currentArtworkBitmap, false, currentPosition, currentDuration)
    }

    private fun clearPreviousMixerAttributes() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val devToClear = lastConfiguredMixerDevice ?: activeOutputDevice
            devToClear?.let { dev ->
                try {
                    val mediaAttr = AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                    audioManager.clearPreferredMixerAttributes(mediaAttr, dev)
                    Log.i("BitPerfect", "★ Cleared previous mixer attributes on ${dev.productName}")
                } catch (e: Exception) {
                    Log.e("BitPerfect", "Clear mixer attributes error", e)
                }
            }
            lastConfiguredMixerDevice = null
        }
    }

    fun setOutputDevice(device: AudioDeviceInfo?) {
        val changed = (activeOutputDevice?.id != device?.id)
        val prevDev = activeOutputDevice
        activeOutputDevice = device

        if (changed) {
            // 出力デバイスが切り替わる再生成は「接続時」の安全側動作が正。ポーズ由来の保留は破棄する。
            // 「前回生成デバイス」の記録も破棄し、新デバイスでの初回生成を安全側に戻す
            // （同一デバイスでの構成変更に伴う再生成だけが音量維持の対象）。
            volumePreservePending = false
            lastCreatedTrackDeviceId = Int.MIN_VALUE
        }

        if (device == null) {
            isVolumeLocked = false
            forceResetSpeakerVolume()
            if (isUsbDevice(prevDev)) {
                handleBecomingNoisyOrDisconnected()
                return
            }
        }

        updateVolumeControlMode()

        if (changed && device != null && isCurrentlyPlaying) {
            switchStreamConfiguration()
        }
    }

    fun setDirectSourceMode(isDirect: Boolean) {
        if (isDirectSource == isDirect) return
        isDirectSource = isDirect
        if (isCurrentlyPlaying) {
            switchStreamConfiguration()
        }
    }

    fun setUpsampling(factor: Int) {
        val validFactor = when (factor) {
            2 -> 2
            4 -> 4
            8 -> 8
            else -> 1
        }
        upsampleFactor = validFactor
        if (isCurrentlyPlaying) {
            switchStreamConfiguration()
        }
    }

    fun updateBaseSampleRate(newRate: Int) {
        if (baseSampleRate == newRate) return
        baseSampleRate = newRate
        if (isCurrentlyPlaying) {
            switchStreamConfiguration()
        }
    }

    // ★ AudioTrack 初期化の非同期要求（案A: UI スレッド等からの入口）。
    //   initAudioTrack を呼び出しスレッドで同期実行すると、実行中に
    //   追加要求が来た場合に保留ループ（hasPendingInit の do-while）が
    //   そのスレッドを捕まえ続ける（Local 再生中のリログ操作でメイン
    //   スレッドが捕まりフリーズした事故の経路）。初期化の実行は
    //   常に trackExecutor へ寄せ、呼び出し側は「現在のフィールド値
    //   での初期化を要求した」だけで即座に戻る。
    fun requestStreamReconfiguration() {
        trackExecutor.execute {
            initAudioTrack()
        }
    }

    private fun switchStreamConfiguration() {
        val factorToApply = effectiveFactor
        val targetRate = baseSampleRate * factorToApply

        isSwitchingRate.set(true)
        isBuffering.set(true)
        pcmQueue.clear()

        effectiveSampleRate = targetRate
        NativeAudioEngine.nativeConfigureUpsampler(factorToApply, baseSampleRate)
        notifyPlaybackStateChanged()

        trackExecutor.execute {
            // ★ 案C: isSwitchingRate を落とすのは、このタスク自身が
            //   init を実行した場合だけにする。従来は finally で無条件
            //   に落としていたため、初期化の実行中（audioTrack が null
            //   の窓）に割り込んで保留を立てただけのタスクでもフラグが
            //   先に落ちて供給が再開され、pushPcm がその窓で再構成
            //   要求を出し直す連鎖が成立していた。保留の再実行は実行中
            //   の initAudioTrack 呼び出しの中で行われ、その呼び出しの
            //   タスクが実行者として戻るため、フラグは必ず実行者側で
            //   落ちる（案A で初期化の実行は trackExecutor へ統一済み）。
            var executedInit = false
            try {
                val executionsBefore = initExecutionCount
                initAudioTrack()
                executedInit = initExecutionCount > executionsBefore
            } finally {
                pcmQueue.clear()
                if (executedInit) {
                    isSwitchingRate.set(false)
                }
            }
        }
    }

    fun pushPcm(pcmBytes: ByteArray, sampleRate: Int, inBitMode: String) {
        if (!isCurrentlyPlaying) {
            isCurrentlyPlaying = true
        }

        if (isSwitchingRate.get()) {
            // ★ Local 供給時は破棄せず、構成切替の完了を待ってから
            //   通常経路で処理する。Local のデコードは Service キュー
            //   残量へのフロー制御（awaitQueueRoom）で実時間に追従する
            //   前提だが、切替中は pushPcm が素通りで破棄され、再生
            //   ループも止まるためキュー残量が 0 のままになり、律速が
            //   効かずデコードがファイル末尾まで走り切ってしまう。
            //   あわせて再生し切り待ち（waitForDrainThenAdvance）も
            //   残量 0 を読み続けて即進行するため、モード切替直後の
            //   最初の曲だけが「爆速で終わって」次曲へ進んでいた。
            //   YT はページ側が実時間で供給し続けるため、従来どおり
            //   破棄で問題ない（待ちを入れると供給が詰まる）。
            if (!isLocalSourceActive) return
            if (!awaitSwitchComplete()) return
        }

        val actualInputRate = if (sampleRate > 0) sampleRate else baseSampleRate
        val factorToApply = effectiveFactor
        val targetEffectiveRate = actualInputRate * factorToApply

        // ★ 再生成判定は要求同士で比較する。effectiveSampleRate は実績値（クランプ後は
        //   要求と異なる）ため、ここで使うとクランプのたびに再初期化が繰り返される。
        val needsRecreate = (audioTrack == null || audioTrack?.state != AudioTrack.STATE_INITIALIZED ||
                             actualInputRate != baseSampleRate || targetEffectiveRate != lastRequestedTargetRate)

        if (needsRecreate) {
            // ★ 再構成の無限ループ防止: 同じ構成で AudioTrack を
            //   作れなかったことが上限回数続いている間は、再構成を
            //   起動せずチャンクを捨てるだけにする。従来は AudioTrack
            //   が null の間「再構成 → チャンク破棄」を無制限に
            //   繰り返し、1 チャンクごとに再構成（数百 ms）が走って
            //   再生位置が実時間の約 1/10 でしか進まず、無音で
            //   固まったように見えた。構成が変われば（別のレート・
            //   デバイス・倍率・ビットモード）署名が変わり、通常
            //   どおり再構成される。
            if (isStreamInitExhausted(actualInputRate, factorToApply)) return
            baseSampleRate = actualInputRate
            switchStreamConfiguration()
            return
        }

        // ★ native は doInit で実績倍率（negotiatedFactor）で初期化済み。要求倍率を渡すと
        //   クランプ時に native の想定とずれるため、実績倍率を渡す。
        val processedBytes = NativeAudioEngine.nativeProcessUpsample(
            pcmBytes, pcmBytes.size, inBitMode, actualBitMode, negotiatedFactor
        ) ?: pcmBytes

        // ★ このチャンクのスペクトラムを処理済み累積位置と組で保持する
        //   （表示の再生時刻整列用。音声・DSP 処理自体は無変更）。
        captureSpectrumSnapshot(processedBytes)

        if (!pcmQueue.offer(processedBytes)) {
            val dropped = pcmQueue.poll()
            pcmQueue.offer(processedBytes)
            if (dropped != null) {
                bufferDropCount.incrementAndGet()
                val bytesPerSample = when (actualBitMode) { "32bit" -> 4; "24bit" -> 3; else -> 2 }
                if (effectiveSampleRate > 0) {
                    val droppedFrames = dropped.size / (bytesPerSample * 2)
                    bufferDropMs.addAndGet(droppedFrames * 1000L / effectiveSampleRate)
                }
            }
        }

        if (isBuffering.get() && pcmQueue.size >= activePrerollThreshold()) {
            isBuffering.set(false)
            localFastPrerollArmed = false
            if (underrunOpen.compareAndSet(true, false)) {
                bufferResumeCount.incrementAndGet()
            }
        }
    }

    // ★ ストリーム構成切替（isSwitchingRate）の完了を上限付きで待つ。
    //   Local のデコードスレッドから pushPcm 経由で呼ばれる。切替は
    //   trackExecutor 上の AudioTrack 再初期化であり、この待ちの間も
    //   そちらは進む（このメソッドはロックを取らない）。完了したら
    //   true（呼び出し側は通常処理へ進む）、上限を超えたら false
    //   （呼び出し側はそのチャンクを破棄して従来動作に倒す）。
    private fun awaitSwitchComplete(): Boolean {
        val deadline = System.currentTimeMillis() + SWITCH_WAIT_MAX_MS
        while (isSwitchingRate.get()) {
            if (System.currentTimeMillis() >= deadline) return false
            try {
                Thread.sleep(SWITCH_WAIT_POLL_MS)
            } catch (e: InterruptedException) {
                return false
            }
        }
        return true
    }

    // 構成の同一性を表す署名（失敗回数の数え分けと上限判定に使う）。
    private fun streamInitSignature(baseRate: Int, factor: Int): String =
        "$baseRate/$factor/${activeOutputDevice?.id}/$requestedBitMode"

    // 同じ構成での AudioTrack 生成失敗が上限に達しているか
    // （pushPcm の再構成ループの上限判定）。
    private fun isStreamInitExhausted(inputRate: Int, factor: Int): Boolean {
        if (streamInitFailureCount < MAX_STREAM_INIT_FAILURES) return false
        return streamInitSignature(inputRate, factor) == streamInitFailureSignature
    }

    // ★ initAudioTrack の結果処理: doInit が残した保留（フォール
    //   バック発生・出力レート上限の要求）をコールバックへ流し、
    //   失敗の連続回数を更新する。成功すれば失敗記録はリセット。
    //   上限要求が出た失敗は「一段下げての回復が進行中」なので
    //   失敗として数えない（上限の梯子が尽きた後の失敗から数える）。
    private fun handleStreamInitOutcome(success: Boolean) {
        pendingRateFallback?.let { (requested, actual) ->
            pendingRateFallback = null
            Log.i(
                "BitPerfect",
                "★ Stream rate fallback: requested ${requested}Hz -> actual ${actual}Hz"
            )
            onStreamRateFallback?.invoke(requested, actual)
        }
        if (success) {
            streamInitFailureCount = 0
            streamInitFailureSignature = null
            streamFailureNotified = false
            return
        }
        val capHz = pendingOutputRateCapHz
        pendingOutputRateCapHz = 0
        if (capHz > 0) {
            onOutputRateCapNeeded?.invoke(capHz)
            return
        }
        val signature = streamInitSignature(baseSampleRate, effectiveFactor)
        if (signature == streamInitFailureSignature) {
            streamInitFailureCount++
        } else {
            streamInitFailureSignature = signature
            streamInitFailureCount = 1
            streamFailureNotified = false
        }
        if (streamInitFailureCount >= MAX_STREAM_INIT_FAILURES && !streamFailureNotified) {
            streamFailureNotified = true
            Log.w(
                "BitPerfect",
                "★ Stream configuration failed $streamInitFailureCount times " +
                    "for $signature; giving up on this configuration"
            )
            onStreamConfigurationFailed?.invoke(
                baseSampleRate, baseSampleRate * effectiveFactor
            )
        }
    }

    fun resetBuffer(reason: String = "unknown") {
        bufferFlushCount.incrementAndGet()
        if (reason == "page") bufferFlushPageCount.incrementAndGet()
        Log.i("BitPerfect", "★ Buffer flush (reason=$reason) total=${bufferFlushCount.get()}")
        isBuffering.set(true)
        // ★ 高速プリロールのアームは Local 由来のフラッシュだけ。
        //   YT 由来（ページ/モード切替）では解除して持ち越さない。
        if (reason == "local") localFastPrerollArmed = true
        if (reason == "page" || reason == "mode") localFastPrerollArmed = false
        pcmQueue.clear()
        audioLock.lock()
        try {
            audioTrack?.flush()
        } catch (e: Exception) {}
        finally {
            audioLock.unlock()
        }
        NativeAudioEngine.nativeResetUpsampler()
        totalWrittenFrames = 0L
        resetSpectrumTimeline()
        onPeakListener?.invoke(-60f, -60f, 0, 0, 0)
    }

    // ★ pushPcm の処理直後に呼ばれる。このチャンクのバンドレベル
    //   （native リングバッファ末尾＝ちょうど処理した音の末尾）を、
    //   処理済み累積フレーム位置（このチャンクの終端）と組で保持する。
    //   位置の加算はスペクトラム無効時も常時行い（時刻軸の一貫性のため）、
    //   FFT の取得だけを従来の表示ポーリング側ゲートと同条件
    //   （isSpectrumEnabled）で間引く。無効の間はスナップショット自体を
    //   作らないため、保持コストも発生しない。
    private fun captureSpectrumSnapshot(processedBytes: ByteArray) {
        val bytesPerFrame = when (actualBitMode) { "32bit" -> 8; "24bit" -> 6; else -> 4 }
        val frames = (processedBytes.size / bytesPerFrame).toLong()
        if (frames <= 0L) return
        synchronized(spectrumSnapshotLock) {
            spectrumProcessedFrames += frames
        }
        val spectrumEnabled = try {
            AppPreferences.get().isSpectrumEnabled
        } catch (e: Exception) {
            false
        }
        if (!spectrumEnabled) return
        NativeAudioEngine.nativeGetSpectrum(spectrumCaptureBuffer)
        synchronized(spectrumSnapshotLock) {
            val idx = spectrumSnapshotWriteIndex
            spectrumSnapshotPositions[idx] = spectrumProcessedFrames
            spectrumCaptureBuffer.copyInto(spectrumSnapshotLevels[idx])
            spectrumSnapshotWriteIndex = (idx + 1) % SPECTRUM_RING_CAPACITY
            if (spectrumSnapshotCount < SPECTRUM_RING_CAPACITY) spectrumSnapshotCount++
        }
    }

    // ★ 表示用: 「今鳴っている位置」に整列したスペクトラムを outBands へ
    //   コピーし、スナップショットがあれば true を返す。
    //   今鳴っている位置（出力フレーム単位）は、処理済み累積から未再生分
    //   （Service キュー滞留分＋ AudioTrack へ書いたが再生ヘッドがまだ
    //   到達していない分）を差し引いたもの。後者は analyzeAndDispatchPeak
    //   の遅延推定と同じ根拠（totalWrittenFrames − 再生位置）。再生位置は
    //   生のヘッド値ではなく表示時刻軸へ換算した値を使うこと
    //   （playedFramesInTimeline。フラッシュ後のエポックずれ対策）。
    //   選択は「その位置を最初に越えるスナップショット」＝今鳴っている
    //   チャンクのもの。キュー満杯で最古チャンクが破棄された場合は滞留分
    //   が実キューから算出されるため、位置が破棄分を飛び越えて自動的に
    //   追随する（破棄された音は聞こえないので、それで正しい）。
    fun getSpectrumAlignedToPlayback(outBands: FloatArray): Boolean {
        if (outBands.size < SPECTRUM_BAND_COUNT) return false
        val bytesPerFrame = when (actualBitMode) { "32bit" -> 8; "24bit" -> 6; else -> 4 }
        var queuedBytes = 0L
        for (chunk in pcmQueue) queuedBytes += chunk.size
        val queuedFrames = queuedBytes / bytesPerFrame
        // ★ 再生ヘッドは表示時刻軸へ換算してから使う（基点の説明は
        //   playbackHeadBaseFrames 参照）。生のヘッド値を直接引くと、
        //   フラッシュ後のエポックずれで hardwareBufferedFrames が
        //   0 に潰れ、nowPos がバースト状の処理位置へ退化してしまう。
        val playedFrames = playedFramesInTimeline()
        val hardwareBufferedFrames = (totalWrittenFrames - playedFrames).coerceAtLeast(0L)
        synchronized(spectrumSnapshotLock) {
            if (spectrumSnapshotCount == 0) return false
            val nowPos = (spectrumProcessedFrames - queuedFrames - hardwareBufferedFrames)
                .coerceIn(0L, spectrumProcessedFrames)
            var bestIdx = -1
            var bestPos = Long.MAX_VALUE
            var latestIdx = -1
            var latestPos = Long.MIN_VALUE
            for (n in 0 until spectrumSnapshotCount) {
                val idx = (spectrumSnapshotWriteIndex - spectrumSnapshotCount + n +
                    SPECTRUM_RING_CAPACITY) % SPECTRUM_RING_CAPACITY
                val pos = spectrumSnapshotPositions[idx]
                if (pos > latestPos) {
                    latestPos = pos
                    latestIdx = idx
                }
                if (pos > nowPos && pos < bestPos) {
                    bestPos = pos
                    bestIdx = idx
                }
            }
            val chosen = if (bestIdx >= 0) bestIdx else latestIdx
            if (chosen < 0) return false
            spectrumSnapshotLevels[chosen].copyInto(outBands)
            return true
        }
    }

    // ★ 再生ループで AudioTrack への書込みが成功したチャンクのピークを、
    //   書込み済み累積フレーム位置（このチャンクの終端）と組で保持する。
    private fun capturePeakSnapshot(endPositionFrames: Long, dbL: Float, dbR: Float) {
        synchronized(peakSnapshotLock) {
            val idx = peakSnapshotWriteIndex
            peakSnapshotPositions[idx] = endPositionFrames
            peakSnapshotDbL[idx] = dbL
            peakSnapshotDbR[idx] = dbR
            peakSnapshotWriteIndex = (idx + 1) % PEAK_RING_CAPACITY
            if (peakSnapshotCount < PEAK_RING_CAPACITY) peakSnapshotCount++
        }
    }

    // ★ 表示用: 「今鳴っているチャンク」のピークを outDb[0]=L / outDb[1]=R
    //   へコピーし、該当スナップショットがあれば true を返す。
    //   今鳴っている位置は、書込み済み累積と同じ表示時刻軸へ換算した
    //   再生ヘッド位置（playedFramesInTimeline）で、選択は「終端が
    //   再生位置を最初に越えるスナップショット」＝現在再生中の
    //   チャンクのもの。再生位置が全スナップショットの終端以降
    //   （書いた音が鳴り終わっている）場合は false を返し、呼び出し側
    //   のフォールバック（無音扱い）に委ねる。
    fun getPeakAlignedToPlayback(outDb: FloatArray): Boolean {
        if (outDb.size < 2) return false
        val playedFrames = playedFramesInTimeline()
        synchronized(peakSnapshotLock) {
            if (peakSnapshotCount == 0) return false
            for (n in 0 until peakSnapshotCount) {
                val idx = (peakSnapshotWriteIndex - peakSnapshotCount + n +
                    PEAK_RING_CAPACITY) % PEAK_RING_CAPACITY
                if (peakSnapshotPositions[idx] > playedFrames) {
                    outDb[0] = peakSnapshotDbL[idx]
                    outDb[1] = peakSnapshotDbR[idx]
                    return true
                }
            }
            return false
        }
    }

    // ★ スペクトラム／ピークの表示時刻軸をリセットする。totalWrittenFrames
    //   = 0L と同じ箇所（resetBuffer / AudioTrack 再生成 / ポーズでの破棄 /
    //   切断フロー）から呼び、処理済み累積・書込み済み累積と再生ヘッドの
    //   基準を揃える。
    private fun resetSpectrumTimeline() {
        // ★ 再生ヘッドの基点を「今の」ヘッド値へ合わせ直す。呼び出し
        //   箇所では totalWrittenFrames が 0 に戻された直後であり、
        //   resetBuffer では flush() 済み（ヘッドは旧エポックの書込み
        //   総数へ跳んだ後）、AudioTrack 再生成では新規トラックの
        //   ヘッド（≒0）が読める。いずれも新しい表示時刻軸の原点と
        //   して正しい。
        playbackHeadBaseFrames =
            (audioTrack?.playbackHeadPosition?.toLong() ?: 0L) and 0xFFFFFFFFL
        synchronized(spectrumSnapshotLock) {
            spectrumProcessedFrames = 0L
            spectrumSnapshotWriteIndex = 0
            spectrumSnapshotCount = 0
        }
        synchronized(peakSnapshotLock) {
            peakSnapshotWriteIndex = 0
            peakSnapshotCount = 0
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "ACTION_PLAY" -> {
                isCurrentlyPlaying = true
                onCommandListener?.invoke("play")
            }
            "ACTION_PAUSE" -> {
                onCommandListener?.invoke("pause")
                forceCloseDacStream()
            }
            "ACTION_NEXT" -> onCommandListener?.invoke("next")
            "ACTION_PREV" -> onCommandListener?.invoke("prev")
        }
        return START_NOT_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        stopServiceCleanly()
    }

    private fun setupMediaSession() {
        val activityIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val sessionActivityPendingIntent = PendingIntent.getActivity(
            this,
            0,
            activityIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        mediaSession = MediaSessionCompat(this, "BitPerfectMediaSession").apply {
            setFlags(MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS)
            setSessionActivity(sessionActivityPendingIntent)
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() {
                    isCurrentlyPlaying = true
                    onCommandListener?.invoke("play")
                }
                override fun onPause() {
                    onCommandListener?.invoke("pause")
                    forceCloseDacStream()
                }
                override fun onSkipToNext() { onCommandListener?.invoke("next") }
                override fun onSkipToPrevious() { onCommandListener?.invoke("prev") }
                override fun onSeekTo(pos: Long) {
                    currentPosition = pos
                    onSeekListener?.invoke(pos)
                    resetBuffer("seek")
                    updatePlaybackState(isCurrentlyPlaying, pos)
                }
            })
            isActive = true
        }
        updateVolumeControlMode()
    }

    fun updateProgress(currentMs: Long, durationMs: Long, isPlaying: Boolean) {
        currentPosition = currentMs
        currentDuration = durationMs

        val metaBuilder = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID, "perfect_bitrate_${System.currentTimeMillis()}")
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, currentTitle)
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, currentArtist)
            .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, durationMs)

        if (currentArtworkBitmap != null) {
            metaBuilder.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, currentArtworkBitmap)
            metaBuilder.putBitmap(MediaMetadataCompat.METADATA_KEY_ART, currentArtworkBitmap)
        }

        mediaSession.setMetadata(metaBuilder.build())
        updatePlaybackState(isPlaying, currentMs)
        PlayerWidgetProvider.updateAllWidgets(this, currentTitle, currentArtist, currentArtworkBitmap, isPlaying, currentMs, durationMs)
        notifyPlaybackStateChanged()
    }

    fun forceCloseDacStream() {
        // ★ この閉じ方は「同一デバイスでの再生成」が前提のポーズ経路。デバイス切替・切断と区別する
        //   ため、閉じた時点の出力デバイスを音量維持の保留として記録する（再生成時に消費される）。
        volumePreservePending = true
        volumePreserveDeviceId = activeOutputDevice?.id ?: -1
        isCurrentlyPlaying = false
        isBuffering.set(true)
        pcmQueue.clear()
        NativeAudioEngine.nativeResetUpsampler()
        totalWrittenFrames = 0L
        resetSpectrumTimeline()
        onPeakListener?.invoke(-60f, -60f, 0, 0, 0)

        try {
            audioTrack?.setVolume(0f)
            audioTrack?.pause()
            audioTrack?.flush()
        } catch (e: Exception) {}

        trackExecutor.execute {
            audioLock.lock()
            try {
                audioTrack?.let { track ->
                    try {
                        track.stop()
                        track.release()
                    } catch (e: Exception) {}
                }
                audioTrack = null
                clearPreviousMixerAttributes()
            } finally {
                audioLock.unlock()
            }
        }
        updateNotification()
        PlayerWidgetProvider.updateAllWidgets(this, currentTitle, currentArtist, currentArtworkBitmap, false, currentPosition, currentDuration)
    }

    fun updatePlaybackState(isPlaying: Boolean, position: Long = currentPosition) {
        isCurrentlyPlaying = isPlaying
        val state = if (isPlaying) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED
        val actions = PlaybackStateCompat.ACTION_PLAY or
                PlaybackStateCompat.ACTION_PAUSE or
                PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                PlaybackStateCompat.ACTION_SEEK_TO or
                PlaybackStateCompat.ACTION_PLAY_PAUSE

        mediaSession.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(actions)
                .setState(state, position, if (isPlaying) 1.0f else 0.0f, SystemClock.elapsedRealtime())
                .build()
        )

        updateNotification()
        PlayerWidgetProvider.updateAllWidgets(this, currentTitle, currentArtist, currentArtworkBitmap, isPlaying, position, currentDuration)
        notifyPlaybackStateChanged()
    }

    fun updateCodec(codec: String) {
        currentCodec = codec
        updateNotification()
    }

    fun updateMetadata(title: String, artist: String, artworkUrl: String) {
        currentTitle = title
        currentArtist = artist

        val metaBuilder = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID, "perfect_bitrate_${System.currentTimeMillis()}")
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title)
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, artist)
            .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, currentDuration)

        if (currentArtworkBitmap != null) {
            metaBuilder.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, currentArtworkBitmap)
            metaBuilder.putBitmap(MediaMetadataCompat.METADATA_KEY_ART, currentArtworkBitmap)
        }

        mediaSession.setMetadata(metaBuilder.build())
        updateNotification()
        PlayerWidgetProvider.updateAllWidgets(this, title, artist, currentArtworkBitmap, isCurrentlyPlaying, currentPosition, currentDuration)
        notifyPlaybackStateChanged()

        if (artworkUrl.isNotEmpty()) {
            imageExecutor.execute {
                try {
                    val stream = URL(artworkUrl).openStream()
                    val bmp = BitmapFactory.decodeStream(stream)
                    currentArtworkBitmap = bmp
                    metaBuilder.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, bmp)
                    metaBuilder.putBitmap(MediaMetadataCompat.METADATA_KEY_ART, bmp)
                    mediaSession.setMetadata(metaBuilder.build())
                    updateNotification()
                    PlayerWidgetProvider.updateAllWidgets(this, currentTitle, currentArtist, currentArtworkBitmap, isCurrentlyPlaying, currentPosition, currentDuration)
                } catch (e: Exception) {}
            }
        }
    }

    // ★ Local Music 用のメタデータ受け口（仕様書 §4.3）: アートワークが URL でなく
    //   Bitmap で届くソース向けに、既存 updateMetadata（URL 経路）とは別に用意する。
    //   既存経路の挙動は変えない。artwork=null は「アートワークなし」として
    //   前ソースの画像を残さずクリアする。
    fun updateMetadataWithBitmap(title: String, artist: String, artwork: Bitmap?) {
        currentTitle = title
        currentArtist = artist
        currentArtworkBitmap = artwork

        val metaBuilder = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID, "perfect_bitrate_${System.currentTimeMillis()}")
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title)
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, artist)
            .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, currentDuration)

        if (artwork != null) {
            metaBuilder.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, artwork)
            metaBuilder.putBitmap(MediaMetadataCompat.METADATA_KEY_ART, artwork)
        }

        mediaSession.setMetadata(metaBuilder.build())
        updateNotification()
        PlayerWidgetProvider.updateAllWidgets(this, title, artist, artwork, isCurrentlyPlaying, currentPosition, currentDuration)
        notifyPlaybackStateChanged()
    }

    private fun createActionPendingIntent(action: String, requestCode: Int): PendingIntent {
        val intent = Intent(this, BitPerfectPlaybackService::class.java).apply { this.action = action }
        return PendingIntent.getService(this, requestCode, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun updateNotification() {
        val prevIntent = createActionPendingIntent("ACTION_PREV", 1)
        val playPauseIntent = createActionPendingIntent(if (isCurrentlyPlaying) "ACTION_PAUSE" else "ACTION_PLAY", 2)
        val nextIntent = createActionPendingIntent("ACTION_NEXT", 3)

        val activityIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentPendingIntent = PendingIntent.getActivity(
            this,
            0,
            activityIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val playPauseIcon = if (isCurrentlyPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
        val bitStr = actualBitMode
        val deviceLabel = when {
            isUsbDevice(activeOutputDevice) -> "DIRECT"
            isBluetoothDevice(activeOutputDevice) -> "BLUETOOTH"
            else -> "SPEAKER"
        }

        val factorTag = effectiveFactor
        val upsampleTag = if (factorTag > 1) " [FREQ ${factorTag}x]" else ""

        val notification = NotificationCompat.Builder(this, "bitperfect_service_channel")
            .setContentTitle(currentTitle)
            .setContentText("$currentArtist | $currentCodec$upsampleTag")
            .setSubText("${effectiveSampleRate}Hz $bitStr $deviceLabel")
            .setLargeIcon(currentArtworkBitmap)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(contentPendingIntent)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .addAction(android.R.drawable.ic_media_previous, "前へ", prevIntent)
            .addAction(playPauseIcon, if (isCurrentlyPlaying) "一時停止" else "再生", playPauseIntent)
            .addAction(android.R.drawable.ic_media_next, "次へ", nextIntent)
            .setStyle(
                MediaStyle()
                    .setMediaSession(mediaSession.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
            )
            .setOngoing(isCurrentlyPlaying)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOnlyAlertOnce(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1001, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(1001, notification)
        }
    }

    // ★ Phase 1: 引数は全て無視されていたため廃止し、構成は自フィールド
    //   (requestedBitMode / baseSampleRate / effectiveFactor / activeOutputDevice) が正。
    //   呼び出し側は「呼ぶ前にフィールドへ書く」ことが要求伝達そのものである。
    // ★ hasPendingInit の合体仕様: 初期化の実行中に追加要求が来た場合は保留フラグだけを立て、
    //   実行中ループが「その時点の最新フィールド値」で再実行する（要求キューではなく最後の状態が勝つ）。
    // 戻り値: 使える AudioTrack を用意できたか。生成の成否は従来
    // 呼び出し側へ伝わらず、失敗しても成功時と同じ「Ready」ログが
    // 出ていた（そのため pushPcm が再構成と破棄を繰り返し続けた）。
    fun initAudioTrack(): Boolean {
        if (isInitializingTrack.getAndSet(true)) {
            hasPendingInit.set(true)
            return audioTrack != null
        }

        var success = audioTrack != null
        try {
            // ★ 案B: 保留再実行への上限。実行中に追加要求が来ると
            //   hasPendingInit が立ち、このループが再実行するが、
            //   (1) 構成署名が前回実行時から変わっていない再要求は
            //   結果が同じため再実行せず保留を捨てる、(2) 署名が
            //   変わる再実行も MAX_PENDING_INIT_ROUNDS 回で打ち切る
            //   （いずれも理由をログに残す）。従来は上限がなく、再構成
            //   要求が続く限り実行スレッドが初期化を繰り返し続けた
            //   （Local 再生中のリログでメインスレッドが捕まった事故）。
            var lastExecutedSignature = ""
            var pendingRounds = 0
            do {
                hasPendingInit.set(false)
                lastExecutedSignature = streamInitSignature(baseSampleRate, effectiveFactor)
                initExecutionCount++
                success = doInitAudioTrackInternal(requestedBitMode, baseSampleRate, effectiveFactor, activeOutputDevice)
                handleStreamInitOutcome(success)
                if (hasPendingInit.get()) {
                    val currentSignature = streamInitSignature(baseSampleRate, effectiveFactor)
                    when {
                        currentSignature == lastExecutedSignature -> {
                            Log.w(
                                "BitPerfect",
                                "★ Pending stream init dropped: configuration unchanged ($currentSignature)"
                            )
                            hasPendingInit.set(false)
                        }
                        pendingRounds >= MAX_PENDING_INIT_ROUNDS -> {
                            Log.w(
                                "BitPerfect",
                                "★ Pending stream init dropped: round limit " +
                                    "$MAX_PENDING_INIT_ROUNDS reached (next: $currentSignature)"
                            )
                            hasPendingInit.set(false)
                        }
                        else -> pendingRounds++
                    }
                }
            } while (hasPendingInit.get())
        } finally {
            isInitializingTrack.set(false)
        }
        return success
    }

    private fun doInitAudioTrackInternal(
        bitMode: String,
        baseRate: Int,
        factor: Int,
        targetDevice: AudioDeviceInfo?
    ): Boolean {
        var initSucceeded = false
        try {
            audioLock.lock()
            try {
                val oldTrack = audioTrack
                audioTrack = null
                pcmQueue.clear()
                isBuffering.set(true)
                NativeAudioEngine.nativeResetUpsampler()

                oldTrack?.let {
                    try {
                        it.pause()
                        it.flush()
                        it.stop()
                        it.release()
                    } catch (e: Exception) {}
                }

                clearPreviousMixerAttributes()
                try { Thread.sleep(150) } catch (e: InterruptedException) {}

                activeOutputDevice = targetDevice
                baseSampleRate = baseRate

                val factorToApply = factor
                val targetRate = baseRate * factorToApply

                // ★ レートクランプ: 要求値（ユーザー設定）はここでは一切変更しない。
                //   決めるのは実績値だけである。USB DAC が要求レートに対応していない場合、
                //   従来は BIT_PERFECT ロックに失敗しても要求レートのまま AudioTrack を作り、
                //   OS ミキサーが無言でリサンプルしていた（それでもアプリは要求レートを
                //   「実績」として表示していた）。そこで、デバイス対応レートのうち
                //   「入力レートの整数倍」という設計を保てる最大のものへ実績をクランプし、
                //   ビットパーフェクトを優先する。対応レートが取得できない場合は
                //   クランプせず従来通り（要求 = 実績）とする。
                var actualTargetRate = targetRate
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
                    isUsbDevice(targetDevice) && baseRate > 0
                ) {
                    val supportedRates = try {
                        audioManager.getSupportedMixerAttributes(targetDevice!!)
                            .map { it.format.sampleRate }
                            .distinct()
                            .ifEmpty { targetDevice!!.sampleRates.toList() }
                    } catch (e: Exception) {
                        targetDevice!!.sampleRates.toList()
                    }
                    val clampedRate = supportedRates
                        .filter { it <= targetRate && it % baseRate == 0 }
                        .maxOrNull()
                    if (clampedRate != null) {
                        actualTargetRate = clampedRate
                    }
                }
                var actualFactor = if (baseRate > 0) actualTargetRate / baseRate else factorToApply

                // ★ 要求は要求として記録する（pushPcm の再生成判定用）。
                //   実績（negotiatedFactor / effectiveSampleRate / native
                //   初期化）は生成が成功したレートで後段に確定する
                //   （生成失敗時のフォールバック先が実績になるため）。
                lastRequestedTargetRate = targetRate

                val mediaAttr = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()

                var finalEncoding = AudioFormat.ENCODING_PCM_16BIT
                var lockSuccess = false

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && isUsbDevice(targetDevice)) {
                    val supportedMixers = try {
                        audioManager.getSupportedMixerAttributes(targetDevice!!)
                    } catch (e: Exception) {
                        emptyList<AudioMixerAttributes>()
                    }

                    val preferredEncList = when (bitMode) {
                        "32bit" -> listOf(
                            AudioFormat.ENCODING_PCM_32BIT,
                            AudioFormat.ENCODING_PCM_24BIT_PACKED,
                            AudioFormat.ENCODING_PCM_16BIT
                        )
                        "24bit" -> listOf(
                            AudioFormat.ENCODING_PCM_24BIT_PACKED,
                            AudioFormat.ENCODING_PCM_32BIT,
                            AudioFormat.ENCODING_PCM_16BIT
                        )
                        else -> listOf(
                            AudioFormat.ENCODING_PCM_16BIT,
                            AudioFormat.ENCODING_PCM_24BIT_PACKED,
                            AudioFormat.ENCODING_PCM_32BIT
                        )
                    }

                    // 1. supportedMixers から BIT_PERFECT を探索
                    for (tryEnc in preferredEncList) {
                        val bpMatch = supportedMixers.firstOrNull {
                            it.format.sampleRate == actualTargetRate &&
                            it.format.encoding == tryEnc &&
                            it.mixerBehavior == AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT
                        }
                        if (bpMatch != null) {
                            try {
                                val ok = audioManager.setPreferredMixerAttributes(mediaAttr, targetDevice!!, bpMatch)
                                if (ok) {
                                    lastConfiguredMixerDevice = targetDevice
                                    finalEncoding = bpMatch.format.encoding
                                    lockSuccess = true
                                    break
                                }
                            } catch (e: Exception) {}
                        }
                    }

                    // 2. 96k等で BIT_PERFECT が未定義の場合、supportedMixers の DEFAULT 動作を探索
                    if (!lockSuccess) {
                        for (tryEnc in preferredEncList) {
                            val defMatch = supportedMixers.firstOrNull {
                                it.format.sampleRate == actualTargetRate &&
                                it.format.encoding == tryEnc
                            }
                            if (defMatch != null) {
                                try {
                                    val ok = audioManager.setPreferredMixerAttributes(mediaAttr, targetDevice!!, defMatch)
                                    if (ok) {
                                        lastConfiguredMixerDevice = targetDevice
                                        finalEncoding = defMatch.format.encoding
                                        lockSuccess = true
                                        break
                                    }
                                } catch (e: Exception) {}
                            }
                        }
                    }

                    // 3. supportedMixers に無くても、DAC に対して targetRate の BIT_PERFECT を要求
                    if (!lockSuccess) {
                        for (tryEnc in preferredEncList) {
                            val forcedBp = AudioMixerAttributes.Builder(
                                AudioFormat.Builder()
                                    .setSampleRate(actualTargetRate)
                                    .setEncoding(tryEnc)
                                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                                    .build()
                            ).setMixerBehavior(AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT).build()

                            try {
                                val ok = audioManager.setPreferredMixerAttributes(mediaAttr, targetDevice!!, forcedBp)
                                if (ok) {
                                    lastConfiguredMixerDevice = targetDevice
                                    finalEncoding = tryEnc
                                    lockSuccess = true
                                    break
                                }
                            } catch (e: Exception) {}
                        }
                    }

                    // 4. 最後の手段として targetRate の DEFAULT クロック切り替えを要求 (96k/384k 物理クロック用)
                    if (!lockSuccess) {
                        for (tryEnc in preferredEncList) {
                            val forcedDef = AudioMixerAttributes.Builder(
                                AudioFormat.Builder()
                                    .setSampleRate(actualTargetRate)
                                    .setEncoding(tryEnc)
                                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                                    .build()
                            ).setMixerBehavior(AudioMixerAttributes.MIXER_BEHAVIOR_DEFAULT).build()

                            try {
                                val ok = audioManager.setPreferredMixerAttributes(mediaAttr, targetDevice!!, forcedDef)
                                if (ok) {
                                    lastConfiguredMixerDevice = targetDevice
                                    finalEncoding = tryEnc
                                    lockSuccess = true
                                    break
                                }
                            } catch (e: Exception) {}
                        }
                    }

                    if (!lockSuccess) {
                        finalEncoding = when (bitMode) {
                            "32bit" -> AudioFormat.ENCODING_PCM_32BIT
                            "24bit" -> AudioFormat.ENCODING_PCM_24BIT_PACKED
                            else -> AudioFormat.ENCODING_PCM_16BIT
                        }
                    }
                }

                val bytesPerSample = when (finalEncoding) {
                    AudioFormat.ENCODING_PCM_32BIT -> 4
                    AudioFormat.ENCODING_PCM_24BIT_PACKED -> 3
                    else -> 2
                }

                // ★ 指定レートで AudioTrack の生成を試みる（従来の
                //   3 回リトライ）。成功したらそのトラックを返す。
                fun tryCreateTrack(rateHz: Int): AudioTrack? {
                    val minBuf = AudioTrack.getMinBufferSize(rateHz, AudioFormat.CHANNEL_OUT_STEREO, finalEncoding)
                    val desiredBuf = rateHz * 2 * bytesPerSample / 4
                    val bufferSize = max(if (minBuf > 0) minBuf * 4 else 16384, desiredBuf)
                    for (retry in 0..2) {
                        try {
                            val track = AudioTrack.Builder()
                                .setAudioAttributes(mediaAttr)
                                .setAudioFormat(
                                    AudioFormat.Builder()
                                        .setEncoding(finalEncoding)
                                        .setSampleRate(rateHz)
                                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                                        .build()
                                )
                                .setBufferSizeInBytes(bufferSize)
                                .setTransferMode(AudioTrack.MODE_STREAM)
                                .build()

                            if (track.state == AudioTrack.STATE_INITIALIZED) {
                                targetDevice?.let { track.setPreferredDevice(it) }
                                // ★ 同一デバイスへの再生成では接続時の安全策（音量 0 化）を
                                //   適用せず、セッション中の音量を維持する。対象は2経路:
                                //   (1) ポーズ（forceCloseDacStream）後の再生成＝保留の消費、
                                //   (2) 構成変更（レート/モード/DSP）に伴う再生成＝前回生成
                                //   トラックと同じデバイス宛て。デバイスが変わる再生成と
                                //   初回生成は従来どおり restoreVolumeForDevice() の安全側
                                //   動作（USB は 0 から開始、デバイスなしは 0 化）にする。
                                //   Vol Lock 中はロック側（最大化）が正なので (2) の対象外
                                //   （consumeVolumePreserve の対象外条件と揃える）。
                                val targetDeviceId = targetDevice?.id ?: -1
                                val preserveVolume = consumeVolumePreserve(targetDevice) ||
                                    (!isVolumeLocked && lastCreatedTrackDeviceId == targetDeviceId)
                                if (!preserveVolume) {
                                    restoreVolumeForDevice(targetDevice)
                                } else {
                                    Log.i(
                                        "BitPerfect",
                                        "★ Volume preserved on same-device recreation " +
                                            "(deviceId=$targetDeviceId)"
                                    )
                                }
                                lastCreatedTrackDeviceId = targetDeviceId
                                track.setVolume(1.0f)
                                track.play()
                                return track
                            } else {
                                track.release()
                                try { Thread.sleep(60) } catch (e: InterruptedException) {}
                            }
                        } catch (e: Exception) {
                            try { Thread.sleep(60) } catch (e: InterruptedException) {}
                        }
                    }
                    return null
                }

                // ★ 生成候補レート（高い順）: 実績要求レート → 倍率を
                //   段階的に下げたレート（baseRate × f、f は整数）。
                //   本体出力等ではレートクランプが効かず、96k × 8 =
                //   768k のような生成不能レートが要求され得る。従来は
                //   生成失敗が伝わらず再構成が無限に繰り返されたため、
                //   作れなかったら倍率を落として作り直す（この範囲の
                //   レート変換は既存の交渉済み倍率の仕組みが担う）。
                val candidateRates = buildList {
                    add(actualTargetRate)
                    var f = actualFactor / 2
                    while (f >= 1) {
                        add(baseRate * f)
                        f /= 2
                    }
                }.distinct()

                var createdTrack: AudioTrack? = null
                var usedRate = actualTargetRate
                for ((index, candidateRate) in candidateRates.withIndex()) {
                    if (index > 0) {
                        // 前のレートで設定したミキサー属性（USB）は
                        // そのレート専用のため解除し、既定ミキシング
                        // で作り直す（従来はこの時点で無音が確定して
                        // いた経路の劣化であり、無音よりは良い）。
                        clearPreviousMixerAttributes()
                        lockSuccess = false
                        Log.w(
                            "BitPerfect",
                            "★ AudioTrack creation failed at ${candidateRates[index - 1]}Hz, " +
                                "falling back to ${candidateRate}Hz"
                        )
                    }
                    createdTrack = tryCreateTrack(candidateRate)
                    if (createdTrack != null) {
                        usedRate = candidateRate
                        break
                    }
                }

                audioTrack = createdTrack
                totalWrittenFrames = 0L
                resetSpectrumTimeline()

                if (createdTrack == null) {
                    // ★ 全候補で生成できなかった。失敗を呼び出し側へ
                    //   伝える（戻り値 false）ため、実績値は更新せず、
                    //   ソースレート未満へのフォールバック先（供給側
                    //   で変換してもらう上限レート）を保留に残す。
                    //   従来はここで成功時と同じ Ready ログが出て、
                    //   失敗がどこにも伝わらなかった。
                    pendingOutputRateCapHz = computeOutputRateCapHz(targetDevice, baseRate)
                    Log.w(
                        "BitPerfect",
                        "★ AudioTrack creation FAILED: requested ${targetRate}Hz " +
                            "(base=${baseRate}Hz), cap candidate=${pendingOutputRateCapHz}Hz"
                    )
                } else {
                    initSucceeded = true
                    // ★ 実績の確定: 成功したレートが実績になる。要求
                    //   （lastRequestedTargetRate / requestedBitMode）
                    //   には書き戻さない（PR #40 のクランプ方針と
                    //   同じ: 次回 init は要求から再試行する）。
                    if (usedRate != actualTargetRate) {
                        pendingRateFallback = targetRate to usedRate
                    }
                    actualTargetRate = usedRate
                    actualFactor = if (baseRate > 0) usedRate / baseRate else factorToApply
                    negotiatedFactor = actualFactor
                    effectiveSampleRate = actualTargetRate
                    NativeAudioEngine.nativeConfigureUpsampler(actualFactor, baseSampleRate)

                    val actualModeStr = when (finalEncoding) {
                        AudioFormat.ENCODING_PCM_32BIT -> "32bit"
                        AudioFormat.ENCODING_PCM_24BIT_PACKED -> "24bit"
                        else -> "16bit"
                    }
                    // ★ Phase 4: 交渉結果は実績値にだけ記録する。要求値には書き戻さない。
                    actualBitMode = actualModeStr
                    onActualBitModeChanged?.invoke(actualModeStr)
                    notifyPlaybackStateChanged()

                    Log.i("BitPerfect", "★ AudioTrack Ready: ${actualTargetRate}Hz ($actualModeStr) -> ${targetDevice?.productName ?: "Default"} (Lock: $lockSuccess)")
                }
            } finally {
                audioLock.unlock()
            }
        } catch (e: Exception) {
            Log.e("BitPerfect", "Critical AudioTrack init error", e)
        }
        return initSucceeded
    }

    // ★ ソースレート未満へのフォールバック先（供給側で変換して
    //   もらう上限レート）の決定規則: 出力デバイスの対応レートが
    //   取れれば、そのうちソースレート未満の最大値。取れない経路
    //   （本体出力等）では標準レート梯子のうちソースレート未満の
    //   最大値（例: 96k → 48k、48k → 44.1k）。候補が無ければ 0
    //   （= これ以上下げられない。その失敗は通常の失敗として
    //   数えられ、上限到達で理由を表示して止まる）。
    private fun computeOutputRateCapHz(targetDevice: AudioDeviceInfo?, baseRate: Int): Int {
        val deviceRates = try {
            targetDevice?.sampleRates?.toList().orEmpty()
        } catch (e: Exception) {
            emptyList()
        }
        val fromDevice = deviceRates.filter { it in 8000 until baseRate }.maxOrNull()
        if (fromDevice != null) return fromDevice
        return STANDARD_FALLBACK_RATES.firstOrNull { it < baseRate } ?: 0
    }

    private fun startPlaybackLoop() {
        isRunning = true
        playbackThread = Thread {
            var heartbeatCounter = 0
            // AudioTrack 書込みエラーの連続回数。一時的なエラーと本物の
            // 切断を切り分けるために使う（成功でリセット）。
            var writeErrorStreak = 0
            while (isRunning) {
                try {
                    heartbeatCounter++
                    if (heartbeatCounter >= 20) {
                        heartbeatCounter = 0
                        try {
                            onCommandListener?.invoke("heartbeat")
                        } catch (e: Exception) {}
                    }

                    if (isSwitchingRate.get() || !isCurrentlyPlaying) {
                        Thread.sleep(15)
                        continue
                    }

                    if (isBuffering.get()) {
                        if (pcmQueue.size < activePrerollThreshold()) {
                            Thread.sleep(10)
                            continue
                        } else {
                            isBuffering.set(false)
                            localFastPrerollArmed = false
                            if (underrunOpen.compareAndSet(true, false)) {
                                bufferResumeCount.incrementAndGet()
                            }
                        }
                    }

                    val pcm = pcmQueue.poll(100, TimeUnit.MILLISECONDS)
                    if (pcm == null) {
                        if (pcmQueue.isEmpty()) {
                            if (!isBuffering.get() && underrunOpen.compareAndSet(false, true)) {
                                bufferUnderrunCount.incrementAndGet()
                                Log.i("BitPerfect", "★ Buffer underrun total=${bufferUnderrunCount.get()}")
                            }
                            isBuffering.set(true)
                        }
                        continue
                    }

                    val chunkPeak = analyzeAndDispatchPeak(pcm, actualBitMode)

                    var track: AudioTrack? = null
                    audioLock.lock()
                    try {
                        track = audioTrack
                    } finally {
                        audioLock.unlock()
                    }

                    if (track != null && track.state == AudioTrack.STATE_INITIALIZED && !isSwitchingRate.get()) {
                        if (track.playState != AudioTrack.PLAYSTATE_PLAYING) {
                            try { track.play() } catch (e: Exception) {}
                        }
                        val written = track.write(pcm, 0, pcm.size, AudioTrack.WRITE_BLOCKING)
                        if (written > 0) {
                            writeErrorStreak = 0
                            val bytesPerSample = when (actualBitMode) {
                                "32bit" -> 4
                                "24bit" -> 3
                                else -> 2
                            }
                            val framesWritten = written / (bytesPerSample * 2)
                            totalWrittenFrames += framesWritten
                            // ★ レベルメーターの再生時刻整列用: このチャンクの
                            //   ピークを、書込み済み累積位置（チャンク終端）と
                            //   組で保持する。表示側は getPeakAlignedToPlayback
                            //   で再生ヘッド位置のチャンクのものを取得する。
                            capturePeakSnapshot(totalWrittenFrames, chunkPeak[0], chunkPeak[1])
                        } else if (written < 0) {
                            // ★ 書込みエラーを即「デバイス切断」と断定しない。
                            //   ストリーム構成の切替（updateBaseSampleRate 等に
                            //   よる AudioTrack 再生成）では、再生ループが掴んだ
                            //   旧トラック参照への書込みが切替と競合してエラーに
                            //   なることがある。従来はその1回で切断フローへ倒れ、
                            //   「pause」コマンド経由で供給係（Local 再生中なら
                            //   LocalPlayer）まで止まってしまい、再生中の突然の
                            //   停止として現れていた。切替中・トラック入れ替わり
                            //   中のエラーはこのチャンクを捨てて継続し、現在の
                            //   トラックへの書込みエラーが連続した場合だけ
                            //   従来どおり切断フローへ倒す（本物の切断では
                            //   エラーが続くため検出は1チャンク分しか遅れない）。
                            var currentTrack: AudioTrack? = null
                            audioLock.lock()
                            try {
                                currentTrack = audioTrack
                            } finally {
                                audioLock.unlock()
                            }
                            if (isSwitchingRate.get() || currentTrack !== track) {
                                writeErrorStreak = 0
                            } else if (++writeErrorStreak >= 2) {
                                writeErrorStreak = 0
                                handleBecomingNoisyOrDisconnected()
                            }
                        }
                    }
                } catch (e: InterruptedException) {
                    break
                } catch (e: Exception) {
                    Log.e("BitPerfect", "Playback loop error", e)
                }
            }
        }.apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    private fun safeAbs(value: Int): Long = abs(value.toLong())

    private fun analyzeAndDispatchPeak(pcmBytes: ByteArray, bitMode: String): FloatArray {
        val buffer = ByteBuffer.wrap(pcmBytes).order(ByteOrder.LITTLE_ENDIAN)
        var instantPeakL = -60f
        var instantPeakR = -60f
        var bitMask = 0

        when (bitMode) {
            "32bit" -> {
                var maxL = 0L
                var maxR = 0L
                while (buffer.remaining() >= 8) {
                    val rawL = buffer.int
                    val rawR = buffer.int
                    val valL = safeAbs(rawL)
                    val valR = safeAbs(rawR)
                    if (valL > maxL) maxL = valL
                    if (valR > maxR) maxR = valR
                    bitMask = bitMask or (valL.toInt() and 0x7FFFFFFF) or (valR.toInt() and 0x7FFFFFFF)
                    if (rawL < 0 || rawR < 0 || valL >= 1073741824L || valR >= 1073741824L) {
                        bitMask = bitMask or (1 shl 31)
                    }
                }
                instantPeakL = if (maxL > 0) 20 * log10(maxL / 2147483647.0f) else -60f
                instantPeakR = if (maxR > 0) 20 * log10(maxR / 2147483647.0f) else -60f
            }
            "24bit" -> {
                var maxL = 0L
                var maxR = 0L
                while (buffer.remaining() >= 6) {
                    val b0L = buffer.get().toInt() and 0xFF
                    val b1L = buffer.get().toInt() and 0xFF
                    val b2L = buffer.get().toInt()
                    val rawL = (b2L shl 16) or (b1L shl 8) or b0L
                    val valL = safeAbs(rawL)

                    val b0R = buffer.get().toInt() and 0xFF
                    val b1R = buffer.get().toInt() and 0xFF
                    val b2R = buffer.get().toInt()
                    val rawR = (b2R shl 16) or (b1R shl 8) or b0R
                    val valR = safeAbs(rawR)

                    if (valL > maxL) maxL = valL
                    if (valR > maxR) maxR = valR
                    bitMask = bitMask or (valL.toInt() and 0x7FFFFF) or (valR.toInt() and 0x7FFFFF)
                    if (rawL < 0 || rawR < 0 || valL >= 4194304L || valR >= 4194304L) {
                        bitMask = bitMask or 0x800000
                    }
                }
                instantPeakL = if (maxL > 0) 20 * log10(maxL / 8388607.0f) else -60f
                instantPeakR = if (maxR > 0) 20 * log10(maxR / 8388607.0f) else -60f
            }
            else -> {
                var maxL = 0
                var maxR = 0
                while (buffer.remaining() >= 4) {
                    val rawL = buffer.short.toInt()
                    val rawR = buffer.short.toInt()
                    val valL = abs(rawL)
                    val valR = abs(rawR)
                    if (valL > maxL) maxL = valL
                    if (valR > maxR) maxR = valR
                    bitMask = bitMask or (valL and 0x7FFF) or (valR and 0x7FFF)
                    if (rawL < 0 || rawR < 0 || valL >= 16384 || valR >= 16384) {
                        bitMask = bitMask or 0x8000
                    }
                }
                instantPeakL = if (maxL > 0) 20 * log10(maxL / 32767.0f) else -60f
                instantPeakR = if (maxR > 0) 20 * log10(maxR / 32767.0f) else -60f
            }
        }

        val queueSize = pcmQueue.size
        // ★ 遅延推定も表示時刻軸に換算した再生位置で（生のヘッド値は
        //   フラッシュ後のエポックずれで書込み累積を上回り、推定が
        //   0 に潰れてしまう）。
        val playedFrames = playedFramesInTimeline()
        val hardwareBufferedFrames = (totalWrittenFrames - playedFrames).coerceAtLeast(0L)
        val queueBufferedFrames = queueSize * 4096L
        val totalBufferedFrames = hardwareBufferedFrames + queueBufferedFrames

        val latencyMs = if (effectiveSampleRate > 0) {
            ((totalBufferedFrames * 1000L) / effectiveSampleRate).toInt().coerceIn(0, 999)
        } else {
            0
        }

        // ★ True Peak ガードの低減量を表示用にポーリングする（native が正、表示専用）
        NativeAudioEngine.nativeGetTruePeakStats(truePeakStatsBuffer)
        truePeakCurrentReductionDb = truePeakStatsBuffer[0]
        truePeakMaxReductionDb = truePeakStatsBuffer[1]
        truePeakActivationCount = truePeakStatsBuffer[2].toLong()
        truePeakCapHitCount = truePeakStatsBuffer[3].toLong()

        onPeakListener?.invoke(instantPeakL, instantPeakR, bitMask, queueSize, latencyMs)

        // ★ レベルメーターの再生時刻整列用に、このチャンクのピークを返す
        //   （保持は書込み成功時に capturePeakSnapshot で行う）。
        return floatArrayOf(instantPeakL, instantPeakR)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "bitperfect_service_channel",
                "Bit-Perfect メディア再生コントロール",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun stopServiceCleanly() {
        isRunning = false
        playbackThread?.interrupt()

        try {
            unregisterReceiver(volumeReceiver)
            unregisterReceiver(noisyReceiver)
        } catch (e: Exception) {}

        try {
            mediaSession.isActive = false
            mediaSession.release()
        } catch (e: Exception) {}

        clearPreviousMixerAttributes()

        trackExecutor.execute {
            audioLock.lock()
            try {
                audioTrack?.stop()
                audioTrack?.release()
                audioTrack = null
            } catch (e: Exception) {}
            finally {
                audioLock.unlock()
            }
        }

        if (wifiLock?.isHeld == true) {
            wifiLock?.release()
        }

        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopServiceCleanly()
    }
}