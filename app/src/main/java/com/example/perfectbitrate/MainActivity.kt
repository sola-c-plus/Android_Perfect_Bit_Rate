package com.example.perfectbitrate

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.net.URL
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var mainRootLayout: View
    private lateinit var badgeDirect: TextView
    private lateinit var textDacName: TextView
    private lateinit var textRateBits: TextView
    private lateinit var textCodec: TextView
    private lateinit var textTransfer: TextView
    private lateinit var textPeak: TextView
    private lateinit var textBufferLatency: TextView
    private lateinit var textBitDepth: TextView
    private lateinit var textCpuRamMonitor: TextView
    private var walkmanLevelMeter: WalkmanLevelMeterView? = null
    private var topInfoPanel: View? = null
    private var panelDividerLine: View? = null

    private lateinit var btnReload: ImageButton
    private lateinit var btnDspSettings: ImageButton
    private lateinit var btnUiSettings: ImageButton

    private val appPrefs by lazy { AppPreferences.get() }
    private lateinit var btCodecTracker: BluetoothCodecTracker
    private lateinit var geckoController: GeckoSessionController
    private var activePlayerDialog: PlayerDialogController? = null
    private var activeDspDialog: DspSettingsDialog? = null

    private var playbackService: BitPerfectPlaybackService? = null
    private var isServiceBound = false
    private var audioManager: AudioManager? = null
    private var appWakeLock: PowerManager.WakeLock? = null

    private var baseSampleRate = 48000
    private var upsampleFactor = 1
    private var pcmPacketCount = 0L
    private var outputDeviceName = "内蔵スピーカー"
    private var activeOutputDevice: AudioDeviceInfo? = null
    private var currentCodec = "OPUS 160kbps (48k)"
    private var currentBtCodecName = ""

    private var currentTitle = "YouTube Music"
    private var currentArtist = ""
    private var currentDuration = 0L
    private var currentPosition = 0L
    private var currentArtworkBitmap: Bitmap? = null
    private val imageExecutor = Executors.newSingleThreadExecutor()

    private var isDirectSource = false
    private var currentThemeMode = "dark"
    // ★ Phase 4: このフィールドは要求値専用（Prefs.selectedBitMode と同期）。
    //   実績値は Service の actualBitMode（スナップショット経由）を読むこと。
    private var currentBitMode = "16bit"
    private var isVolLockOn = false
    private var isPlayingState = false
    private var isHandlingDisconnect = false
    private var lastBackPressTime = 0L

    private var peakDbL = -60f
    private var peakDbR = -60f
    private var bitActivityMask = 0
    private var lastBitResetTime = 0L
    private var lastPcmTime = 0L

    // ★ 再生ストール検出（ウォッチドッグ）用定数・状態。
    //   ページ側メディア要素がストールすると paused=false のまま PCM だけが途絶するため、
    //   isPlayingState だけでは検知できない。PCM の途絶時間で段階的に復旧を試みる。
    private val STALL_THRESHOLD_MS = 3000L       // 再生中なのに PCM がこの時間来なければストールとみなす
    private val STALL_STEP_INTERVAL_MS = 5000L   // 復旧ステップの間隔
    private val WATCHDOG_RELOAD_COOLDOWN_MS = 60000L // ページ reload の最小間隔
    private var stallRecoveryStep = 0            // 0=正常, 1=resume_audio 済, 2=play 済, 3=reload 段, 4=セッション再生成 済（以後は自動復旧しない）
    private var lastStallActionTime = 0L
    private var lastWatchdogReloadTime = 0L

    // ★ 最終段: GeckoSession 再生成（ページ reload でも PCM が戻らない場合のみ）。
    //   実機で「通知系（progress/meta/flush）は生きているのに PCM だけ全途絶し、
    //   スキップでもページ reload でも直らず、アプリ再起動でのみ復活する」事象を
    //   2 回観測したための段。ページ内操作では届かない、セッション寿命に紐づく
    //   捕捉経路の死を想定し、セッション自体を作り直す。
    private val SESSION_RECREATE_DELAY_MS = 10000L     // reload 後、PCM 復帰を待つ時間
    private val SESSION_RECREATE_COOLDOWN_MS = 300000L // セッション再生成の最小間隔（5分）
    private val RECREATE_PLAY_DELAY_MS = 5000L         // 再生成後、play を送るまでの最小待ち時間
    private val RECREATE_FOLLOWUP_TIMEOUT_MS = 60000L  // 再生成フォローアップの打ち切り
    private var watchdogReloadDoneInEpisode = false    // このエピソードで reload を実際に実施したか
    private var sessionRecreateDoneInEpisode = false   // このエピソードで再生成段を処理済みか（発火/見送り共通）
    private var lastSessionRecreateTime = 0L           // 最後にセッション再生成を実施した時刻（クールダウン判定）
    private var sessionRecreatePendingTime = 0L        // 再生成を実施し復帰フォローアップ中の時刻（0=フォローアップなし）
    private var lastPageSignalTime = 0L                // content.js から通知が最後に届いた時刻（ページ生存判定用）

    // ★ ユーザーの再生意思: アプリ経由の明示操作で最後に送ったコマンドから保持する。
    //   isPlayingState はページ要素の paused で毎秒上書きされ、Gecko 側都合のポーズと
    //   ユーザーのポーズを区別できないため、セッション再生成の発火条件にはこちらを使う。
    //   明示ポーズで false、明示 play/next/prev で true。ページ UI 上のポーズは
    //   isPlayingState 経由でエピソード自体を閉じるので、そちらでも守られる。
    private var userWantsPlayback = true

    private var currentQueueSize = 0
    private var currentLatencyMs = 0

    private var lastCpuSampleTime = 0L
    private var lastAppCpuTime = 0L
    private var currentCpuUsagePercent = 0
    private var currentRamUsageMb = 0

    private val uiSpectrumBands = FloatArray(32) { -60f }

    private val uiHandler = Handler(Looper.getMainLooper())
    private val uiUpdateRunnable = object : Runnable {
        override fun run() {
            val now = System.currentTimeMillis()
            if (now - lastPcmTime > 400L || !isPlayingState) {
                peakDbL = -60f
                peakDbR = -60f
                bitActivityMask = 0
                currentQueueSize = 0
                currentLatencyMs = 0
                walkmanLevelMeter?.setLevels(-60f, -60f)
            }

            checkPlaybackStall(now)
            checkRecreateFollowUp(now)

            if (isPlayingState && appPrefs.isSpectrumEnabled) {
                NativeAudioEngine.nativeGetSpectrum(uiSpectrumBands)
                activePlayerDialog?.setSpectrumLevels(uiSpectrumBands)
            }

            if (appPrefs.isSysMonitorEnabled) {
                sampleSystemResources()
            }

            updateStatus()
            updateDialogPlayerUi()
            uiHandler.postDelayed(this, 30)
        }
    }

    // ★ play_pause 判定: 通常は isPlayingState で切り替えるが、ストール中（再生状態だが
    //   PCM が途絶）は pause ではなく play を送り、復旧を試みる。
    private fun isPcmFlowing(): Boolean =
        lastPcmTime != 0L && System.currentTimeMillis() - lastPcmTime <= STALL_THRESHOLD_MS

    private fun resolvePlayPauseCommand(): String =
        if (isPlayingState && isPcmFlowing()) "pause" else "play"

    // ★ PCM ストール・ウォッチドッグ: 再生中なのに PCM が途絶していたら、resume_audio →
    //   play → ページ reload → GeckoSession 再生成 の順に段階的に復旧を試みる。
    //   PCM が復帰するか一時停止されればエピソードはリセットされ、reload は 60 秒に
    //   1 回まで、セッション再生成は 5 分に 1 回までしか行わない。
    //   ステップ 1〜3 の発火条件・タイミングは従来どおりで、ステップ 4（再生成）だけが
    //   追加の発火条件（reload 実施済み + ユーザーの再生意思）を持つ。
    private fun checkPlaybackStall(now: Long) {
        if (!isPlayingState || lastPcmTime == 0L) {
            resetStallEpisode()
            return
        }
        val pcmGap = now - lastPcmTime
        if (pcmGap <= STALL_THRESHOLD_MS) {
            resetStallEpisode()
            return
        }
        // 再生中なのに PCM が途絶している（ページ側ストールの疑い）→ 段階的に復旧を試みる
        if (stallRecoveryStep == 0) {
            stallRecoveryStep = 1
            lastStallActionTime = now
            Log.w("MainActivity", "★ PCM stall detected (${pcmGap}ms), sending resume_audio")
            geckoController.sendCommand("resume_audio")
        } else if (now - lastStallActionTime >= STALL_STEP_INTERVAL_MS) {
            lastStallActionTime = now
            when (stallRecoveryStep) {
                1 -> {
                    stallRecoveryStep = 2
                    Log.w("MainActivity", "★ PCM stall persists, sending play")
                    geckoController.sendCommand("play")
                }
                2 -> {
                    stallRecoveryStep = 3
                    if (now - lastWatchdogReloadTime >= WATCHDOG_RELOAD_COOLDOWN_MS) {
                        lastWatchdogReloadTime = now
                        watchdogReloadDoneInEpisode = true
                        Log.w("MainActivity", "★ PCM stall persists, reloading page")
                        geckoController.reload()
                    } else {
                        Log.w("MainActivity", "★ PCM stall persists, reload skipped (cooldown)")
                    }
                }
                3 -> trySessionRecreate(now)
                else -> { /* step 4 以降は自動復旧しない（ユーザー操作に委ねる） */ }
            }
        }
    }

    private fun resetStallEpisode() {
        stallRecoveryStep = 0
        watchdogReloadDoneInEpisode = false
        sessionRecreateDoneInEpisode = false
    }

    // ★ 最終段: ページ reload でも PCM が戻らない場合に限り、GeckoSession 自体を
    //   作り直す。発火条件は全て満たすこと:
    //   - このエピソードで reload を実際に実施済みで、実施から
    //     SESSION_RECREATE_DELAY_MS 経っても PCM が戻っていない
    //     （この関数自体が PCM 途絶中のステップ 3 からしか呼ばれない）
    //   - ユーザーが再生意思を持っている（明示ポーズが記録されていない）
    //   - セッション再生成のクールダウン（5 分）を過ぎている
    //   発火・見送りいずれの場合も 1 エピソードに 1 回だけ判定し、以後は繰り返さない。
    private fun trySessionRecreate(now: Long) {
        if (sessionRecreateDoneInEpisode) return
        if (!watchdogReloadDoneInEpisode) return
        if (now - lastWatchdogReloadTime < SESSION_RECREATE_DELAY_MS) return
        sessionRecreateDoneInEpisode = true

        if (!userWantsPlayback) {
            Log.w("MainActivity", "★ Session recreate skipped (user pause intent)")
            return
        }
        if (now - lastSessionRecreateTime < SESSION_RECREATE_COOLDOWN_MS) {
            Log.w("MainActivity", "★ Session recreate skipped (cooldown)")
            return
        }

        stallRecoveryStep = 4
        lastSessionRecreateTime = now
        Log.w("MainActivity", "★ PCM stall persists after page reload, recreating Gecko session")
        playbackService?.resetBuffer("session")
        if (geckoController.recreateSession()) {
            // 復帰フォローアップへ: 新ページの生存が確認できたら checkRecreateFollowUp が play を 1 回送る
            sessionRecreatePendingTime = now
        } else {
            Log.w("MainActivity", "★ Session recreate failed, giving up this episode")
        }
    }

    // ★ セッション再生成後の復帰フォローアップ（uiUpdateRunnable から周期的に呼ばれる）。
    //   新セッションのページから通知（progress/meta/state = lastPageSignalTime）が届き、
    //   再生成から RECREATE_PLAY_DELAY_MS 以上経っても PCM が戻っていなければ、
    //   ユーザーが再生中だった場合に限り play を 1 回送って復帰を試みる。
    //   "play" は content.js 側でキャプチャの再アタッチも兼ねるため、ページ側の
    //   自動再生状態に依存せず復帰を試みられる。PCM が復帰したら成功として終了し、
    //   ページの生存が確認できないまま RECREATE_FOLLOWUP_TIMEOUT_MS を過ぎたら打ち切る。
    //   このフォローアップはストール・エピソードの状態（stallRecoveryStep）とは独立に
    //   動く: 再生成直後の新ページは「停止中」と報告し得て、エピソード側は閉じても
    //   play の送出まではこちらで担保するため。
    private fun checkRecreateFollowUp(now: Long) {
        if (sessionRecreatePendingTime == 0L) return
        if (lastPcmTime > sessionRecreatePendingTime) {
            Log.i("MainActivity", "★ PCM recovered after session recreate")
            sessionRecreatePendingTime = 0L
            return
        }
        if (!userWantsPlayback) {
            // ユーザーが再生を止めた → play は送らずフォローアップを終了する
            sessionRecreatePendingTime = 0L
            return
        }
        val pageAlive = lastPageSignalTime > sessionRecreatePendingTime
        if (pageAlive && now - sessionRecreatePendingTime >= RECREATE_PLAY_DELAY_MS) {
            Log.w("MainActivity", "★ Sending play after session recreate")
            geckoController.sendCommand("play")
            sessionRecreatePendingTime = 0L
        } else if (now - sessionRecreatePendingTime > RECREATE_FOLLOWUP_TIMEOUT_MS) {
            Log.w("MainActivity", "★ Session recreate follow-up timed out (no page signal)")
            sessionRecreatePendingTime = 0L
        }
    }

    // ★ ユーザーの明示操作による再生/ポーズ意思の記録（セッション再生成の発火条件用）。
    //   追跡経路はアプリ経由の操作だけ:
    //   - ダイアログのプレイヤーボタン（play_pause は解決後の実際のコマンドで記録）
    //   - 通知/メディアセッション経由（Service の onCommandListener。ただし呼び出し側で
    //     "pause" は渡されない: DAC 切断時の自動ポーズも同じ経路で流れ、区別できないため。
    //     通知ポーズはページ側 paused の伝播で isPlayingState が落ち、エピソード自体が
    //     成立しなくなるので、記録しなくても意図せぬ再生成にはつながらない）
    //   - Vol ロック時の音量キー曲送り
    //   ウォッチドッグ自身の送信（resume_audio/play）はここを通らないため影響しない。
    private fun noteUserPlaybackIntent(command: String) {
        when (command) {
            "pause" -> userWantsPlayback = false
            "play", "next", "prev" -> userWantsPlayback = true
        }
    }

    private fun sampleSystemResources() {
        val now = SystemClock.elapsedRealtime()
        val timeDiff = now - lastCpuSampleTime
        if (timeDiff >= 500L) {
            try {
                val stat = java.io.File("/proc/self/stat").readText().split(" ")
                val utime = stat[13].toLong()
                val stime = stat[14].toLong()
                val appCpuTime = utime + stime

                if (lastCpuSampleTime > 0L && timeDiff > 0L) {
                    val cpuDiff = appCpuTime - lastAppCpuTime
                    val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
                    val usage = ((cpuDiff * 1000.0 / timeDiff) / cores).toInt().coerceIn(0, 100)
                    currentCpuUsagePercent = usage
                }
                lastCpuSampleTime = now
                lastAppCpuTime = appCpuTime
            } catch (e: Exception) {
                currentCpuUsagePercent = 0
            }

            try {
                val runtime = Runtime.getRuntime()
                val usedMem = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)
                currentRamUsageMb = usedMem.toInt().coerceAtLeast(1)
            } catch (e: Exception) {
                currentRamUsageMb = 0
            }
        }
    }

    private val deviceDetectRunnable = Runnable {
        detectAudioOutputDevice()
        playbackService?.setOutputDevice(activeOutputDevice)
        btCodecTracker.fetchCurrentCodec()

        if (activeOutputDevice != null && isPlayingState) {
            geckoController.sendCommand("resume_audio")
        }
    }

    private val requestMultiplePermissionsLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            if (permissions[Manifest.permission.BLUETOOTH_CONNECT] == true) {
                btCodecTracker.fetchCurrentCodec()
                updateStatus()
            }
        }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as BitPerfectPlaybackService.LocalBinder
            playbackService = binder.getService()
            isServiceBound = true
            detectAudioOutputDevice()

            playbackService?.isVolumeLocked = isVolLockOn
            playbackService?.requestedBitMode = currentBitMode
            playbackService?.setOutputDevice(activeOutputDevice)

            playbackService?.upsampleFactor = upsampleFactor
            playbackService?.setDirectSourceMode(isDirectSource)

            NativeAudioEngine.nativeSetPerformanceMode(appPrefs.selectedPerfMode)
            NativeAudioEngine.nativeSetRichHarmonics(appPrefs.isRichHarmonicsEnabled)
            NativeAudioEngine.nativeSetTruePeakGuard(appPrefs.isTruePeakGuardEnabled, appPrefs.truePeakCeilingDb())
            NativeAudioEngine.nativeSetCascadeFir(appPrefs.isCascadeFir)
            NativeAudioEngine.nativeSetDitherMode(appPrefs.selectedDitherMode)
            NativeAudioEngine.nativeSetLrIndependentDither(appPrefs.isLrIndependentDither)
            NativeAudioEngine.nativeSetDcPhaseType(appPrefs.selectedDcPhaseType)
            
            FreqPresetManager.applyCurrentPresetToNative()
            val eqGains = appPrefs.getAllEqGains()
            NativeAudioEngine.nativeSetEqualizer(appPrefs.isEqEnabled, eqGains)

            // ★ Phase 4: 実績通知は表示専用。要求値（currentBitMode）と Prefs へは
            //   絶対に書き戻さない（DAC フォールバック時に保存設定が劣化値へ書き換わる
            //   バグの修正）。表示は updateStatus がスナップショットの実績値を読む。
            playbackService?.onActualBitModeChanged = {
                runOnUiThread { updateStatus() }
            }

            playbackService?.onPeakListener = { dbL, dbR, mask, queueSize, latencyMs ->
                lastPcmTime = System.currentTimeMillis()
                peakDbL = dbL
                peakDbR = dbR
                bitActivityMask = bitActivityMask or mask
                currentQueueSize = queueSize
                currentLatencyMs = latencyMs
                walkmanLevelMeter?.setLevels(dbL, dbR)
            }

            playbackService?.onCommandListener = { cmd ->
                // ★ 再生意思の記録: 通知/メディアセッションの明示操作（play/next/prev）のみ。
                //   "pause" は DAC 切断時の自動ポーズも同じ経路で流れるため記録しない
                //   （noteUserPlaybackIntent のコメント参照）。
                if (cmd != "pause") noteUserPlaybackIntent(cmd)
                geckoController.sendCommand(cmd)
            }
            playbackService?.onSeekListener = { pos -> geckoController.sendSeek(pos) }
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            playbackService = null
            isServiceBound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        AppPreferences.init(this)

        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        appWakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PerfectBitRate::MainActivityWakeLock")
        appWakeLock?.acquire()

        mainRootLayout = findViewById(R.id.mainRootLayout)
        topInfoPanel = findViewById(R.id.topInfoPanel)
        panelDividerLine = findViewById(R.id.panelDividerLine)
        badgeDirect = findViewById(R.id.badgeDirect)
        textDacName = findViewById(R.id.textDacName)
        textRateBits = findViewById(R.id.textRateBits)
        textCodec = findViewById(R.id.textCodec)
        textTransfer = findViewById(R.id.textTransfer)
        textPeak = findViewById(R.id.textPeak)
        textBufferLatency = findViewById(R.id.textBufferLatency)
        textBitDepth = findViewById(R.id.textBitDepth)
        textCpuRamMonitor = findViewById(R.id.textCpuRamMonitor)
        walkmanLevelMeter = findViewById(R.id.walkmanLevelMeter)

        btnReload = findViewById(R.id.btnReload)
        btnDspSettings = findViewById(R.id.btnDspSettings)
        btnUiSettings = findViewById(R.id.btnUiSettings)

        isDirectSource = appPrefs.isDirectSource
        currentThemeMode = appPrefs.uiThemeMode
        // ★ Vol Lock は起動時は常に OFF で開始する（安全対策として PR #38 の復元仕様を変更）。
        // 前回 ON のまま終了していた場合にその状態を復元すると、Vol Lock 非対応の機器を
        // 繋いだ起動でも音量が最大側に固定され、爆音になる恐れがある。
        // 保存値も OFF に揃えて実状態と一致させる（状態変更時は両方を書く本アプリの規約に合わせる）。
        // セッション中にユーザーが ON にする分は従来どおり有効で、次回起動時にまた OFF から始まる。
        isVolLockOn = false
        appPrefs.isVolLockEnabled = false
        currentBitMode = appPrefs.selectedBitMode
        upsampleFactor = appPrefs.selectedUpsampleFactor

        // ★ 項目9: プリセット本体を復元してから選択 index を反映する
        FreqPresetManager.restorePresets()
        FreqPresetManager.setInitialPresetIndex(appPrefs.selectedPresetIndex)
        FreqPresetManager.onPresetChangedListener = { pos, _ ->
            appPrefs.selectedPresetIndex = pos
            activeDspDialog?.updatePerfModeState(upsampleFactor >= 2 && !isDirectSource)
        }

        val eqGains = appPrefs.getAllEqGains()
        NativeAudioEngine.nativeSetEqualizer(appPrefs.isEqEnabled, eqGains)

        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        checkAndRequestPermissions()

        btCodecTracker = BluetoothCodecTracker(this) { codec ->
            currentBtCodecName = codec
            updateStatus()
        }
        btCodecTracker.start()

        geckoController = GeckoSessionController(
            activity = this,
            geckoView = findViewById(R.id.geckoview),
            listener = object : GeckoSessionController.Listener {
                override fun onFlush() {
                    playbackService?.resetBuffer("page")
                }
                override fun onPcm(pcmBytes: ByteArray, inBitMode: String) {
                    pcmPacketCount += pcmBytes.size
                    isPlayingState = true
                    lastPcmTime = System.currentTimeMillis()
                    playbackService?.pushPcm(pcmBytes, baseSampleRate, inBitMode)
                }
                override fun onCodec(codec: String, rate: Int) {
                    currentCodec = codec
                    if (rate > 0 && rate != baseSampleRate) {
                        playbackService?.resetBuffer("codec")
                        baseSampleRate = rate
                        playbackService?.updateBaseSampleRate(rate)
                    }
                    playbackService?.updateCodec(codec)
                    updateStatus()
                }
                override fun onMetadata(title: String, artist: String, artworkUrl: String) {
                    lastPageSignalTime = System.currentTimeMillis()
                    currentTitle = title
                    currentArtist = artist
                    playbackService?.updateMetadata(title, artist, artworkUrl)
                    if (artworkUrl.isNotEmpty()) {
                        imageExecutor.execute {
                            try {
                                val stream = URL(artworkUrl).openStream()
                                val bmp = BitmapFactory.decodeStream(stream)
                                runOnUiThread {
                                    currentArtworkBitmap = bmp
                                    updateDialogPlayerUi()
                                }
                            } catch (e: Exception) { Log.e("MainActivity", "Image decode error", e) }
                        }
                    }
                    updateDialogPlayerUi()
                }
                override fun onProgress(currentMs: Long, durationMs: Long, isPlaying: Boolean) {
                    lastPageSignalTime = System.currentTimeMillis()
                    currentPosition = currentMs
                    currentDuration = durationMs
                    isPlayingState = isPlaying
                    playbackService?.updateProgress(currentMs, durationMs, isPlaying)
                    updateDialogPlayerUi()
                }
                override fun onState(isPlaying: Boolean) {
                    lastPageSignalTime = System.currentTimeMillis()
                    isPlayingState = isPlaying
                    if (!isPlaying) {
                        peakDbL = -60f
                        peakDbR = -60f
                        bitActivityMask = 0
                        currentQueueSize = 0
                        currentLatencyMs = 0
                        walkmanLevelMeter?.reset()
                    }
                    playbackService?.updatePlaybackState(isPlaying)
                    updateDialogPlayerUi()
                }
                override fun isDarkTheme(): Boolean = isDarkThemeActive()
                override fun isAdBlockEnabled(): Boolean = appPrefs.isAdBlockEnabled
            }
        )
        geckoController.init()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (geckoController.canGoBack) {
                    geckoController.goBack()
                    return
                }

                val now = System.currentTimeMillis()
                if (now - lastBackPressTime < 2000L) {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                } else {
                    lastBackPressTime = now
                    Toast.makeText(this@MainActivity, "もう一度戻るを押すとアプリを終了します", Toast.LENGTH_SHORT).show()
                }
            }
        })

        val serviceIntent = Intent(this, BitPerfectPlaybackService::class.java)
        ContextCompat.startForegroundService(this, serviceIntent)
        bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE)

        registerAudioDeviceCallback()
        applyThemeUi(currentThemeMode)

        btnReload.setOnClickListener {
            it.hapticTick(appPrefs)
            reloadDirectStream()
        }
        btnDspSettings.setOnClickListener {
            it.hapticTick(appPrefs)
            showDspSettingsDialog()
        }
        btnDspSettings.setOnLongClickListener {
            Toast.makeText(this, "DEVELOPER PRESET TUNER", Toast.LENGTH_SHORT).show()
            showDevPresetsDialog()
            true
        }
        btnUiSettings.setOnClickListener {
            it.hapticTick(appPrefs)
            showUiSettingsDialog()
        }

        uiHandler.post(uiUpdateRunnable)
    }

    private fun isDarkThemeActive(): Boolean {
        return when (currentThemeMode) {
            "light" -> false
            "auto" -> (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
            else -> true
        }
    }

    private fun applyThemeUi(themeMode: String) {
        currentThemeMode = themeMode
        val isDark = isDarkThemeActive()
        walkmanLevelMeter?.isLightMode = !isDark
        geckoController.sendWebTheme(isDark)

        if (isDark) {
            mainRootLayout.setBackgroundColor(Color.parseColor("#000000"))
            topInfoPanel?.setBackgroundColor(Color.parseColor("#0D0D0D"))
            panelDividerLine?.setBackgroundColor(Color.parseColor("#1C1C1C"))
            textDacName.setTextColor(Color.parseColor("#F0F0F0"))
            textRateBits.setTextColor(Color.WHITE)
            textCodec.setTextColor(Color.parseColor("#A0A0A0"))
            textTransfer.setTextColor(Color.parseColor("#666666"))
            textPeak.setTextColor(Color.parseColor("#B0B0B0"))
            textBufferLatency.setTextColor(Color.parseColor("#888888"))
            textCpuRamMonitor.setTextColor(Color.parseColor("#888888"))
            btnReload.setBackgroundResource(R.drawable.bg_btn_icon)
            btnDspSettings.setBackgroundResource(R.drawable.bg_btn_icon)
            btnUiSettings.setBackgroundResource(R.drawable.bg_btn_icon)
            btnReload.setColorFilter(Color.parseColor("#CCCCCC"))
            btnDspSettings.setColorFilter(Color.parseColor("#E5A93C"))
            btnUiSettings.setColorFilter(Color.parseColor("#E5A93C"))
        } else {
            mainRootLayout.setBackgroundColor(Color.parseColor("#FFFFFF"))
            topInfoPanel?.setBackgroundColor(Color.parseColor("#F5F5F7"))
            panelDividerLine?.setBackgroundColor(Color.parseColor("#E0E0E5"))
            textDacName.setTextColor(Color.parseColor("#1C1C1E"))
            textRateBits.setTextColor(Color.parseColor("#1C1C1E"))
            textCodec.setTextColor(Color.parseColor("#636366"))
            textTransfer.setTextColor(Color.parseColor("#636366"))
            textPeak.setTextColor(Color.parseColor("#48484A"))
            textBufferLatency.setTextColor(Color.parseColor("#636366"))
            textCpuRamMonitor.setTextColor(Color.parseColor("#636366"))
            btnReload.setBackgroundResource(R.drawable.bg_btn_icon_light)
            btnDspSettings.setBackgroundResource(R.drawable.bg_btn_icon_light)
            btnUiSettings.setBackgroundResource(R.drawable.bg_btn_icon_light)
            btnReload.setColorFilter(Color.parseColor("#1C1C1E"))
            btnDspSettings.setColorFilter(Color.parseColor("#D49B28"))
            btnUiSettings.setColorFilter(Color.parseColor("#D49B28"))
        }
    }

    private fun isUsbDevice(device: AudioDeviceInfo?): Boolean {
        if (device == null) return false
        return device.type == AudioDeviceInfo.TYPE_USB_DEVICE || device.type == AudioDeviceInfo.TYPE_USB_HEADSET
    }

    private fun updateDialogPlayerUi() {
        activePlayerDialog?.updatePlayerState(
            title = currentTitle,
            artist = currentArtist,
            artwork = currentArtworkBitmap,
            isPlaying = isPlayingState,
            currentPositionMs = currentPosition,
            durationMs = currentDuration
        )
    }

    private fun showDspSettingsDialog() {
        val dspDialog = DspSettingsDialog(
            activity = this,
            isDarkTheme = isDarkThemeActive(),
            topPanelHeight = topInfoPanel?.height ?: 0,
            activeOutputDevice = activeOutputDevice,
            isVolumeLocked = isVolLockOn,
            onVolumeLockChanged = { isLocked ->
                isVolLockOn = isLocked
                appPrefs.isVolLockEnabled = isLocked
                playbackService?.isVolumeLocked = isLocked
                if (isLocked) playbackService?.lockSystemVolumeToMax()
                updateStatus()
            },
            onBitModeChanged = { newMode ->
                currentBitMode = newMode
                playbackService?.requestedBitMode = newMode
                // ★ Phase 1: フィールドへ先に書くことが要求伝達そのもの（initAudioTrack は自フィールドを読む）
                playbackService?.initAudioTrack()
                bitActivityMask = 0
                peakDbL = -60f
                peakDbR = -60f
                walkmanLevelMeter?.reset()
                updateStatus()
            },
            onUpsampleFactorChanged = { newFactor ->
                upsampleFactor = newFactor
                appPrefs.selectedUpsampleFactor = newFactor
                playbackService?.setUpsampling(newFactor)
                updateStatus()
            },
            onDirectSourceChanged = { isDirect ->
                isDirectSource = isDirect
                playbackService?.setDirectSourceMode(isDirect)
                updateStatus()
            },
            onPlayerCommand = { cmd ->
                val resolved = if (cmd == "play_pause") resolvePlayPauseCommand() else cmd
                noteUserPlaybackIntent(resolved)
                geckoController.sendCommand(resolved)
            },
            onSeekTo = { pos -> geckoController.sendSeek(pos) },
            onDismiss = {
                activePlayerDialog = null
                activeDspDialog = null
            }
        )
        activePlayerDialog = dspDialog
        activeDspDialog = dspDialog
        dspDialog.show()
    }

    private fun showUiSettingsDialog() {
        val uiDialog = UiSettingsDialog(
            activity = this,
            isDarkTheme = isDarkThemeActive(),
            topPanelHeight = topInfoPanel?.height ?: 0,
            onThemeChanged = { newTheme ->
                applyThemeUi(newTheme)
                showUiSettingsDialog()
            },
            onAdBlockChanged = { isEnabled -> geckoController.sendAdBlock(isEnabled) },
            onSysMonitorChanged = { isEnabled ->
                // ★ スイッチ ON/OFF で追加項目 (CPU/RAM, BUF/LAT) を一括表示/非表示切り替え
                textCpuRamMonitor.visibility = if (isEnabled) View.VISIBLE else View.GONE
                textBufferLatency.visibility = if (isEnabled) View.VISIBLE else View.GONE
                updateStatus()
            },
            onPlayerCommand = { cmd ->
                val resolved = if (cmd == "play_pause") resolvePlayPauseCommand() else cmd
                noteUserPlaybackIntent(resolved)
                geckoController.sendCommand(resolved)
            },
            onSeekTo = { pos -> geckoController.sendSeek(pos) },
            onDismiss = { activePlayerDialog = null }
        )
        activePlayerDialog = uiDialog
        uiDialog.show()
    }

    private fun showDevPresetsDialog() {
        DevPresetsDialog(this).show()
    }

    private fun checkAndRequestPermissions() {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        if (permissions.isNotEmpty()) {
            requestMultiplePermissionsLauncher.launch(permissions.toTypedArray())
        }
    }

    private fun reloadDirectStream() {
        runOnUiThread {
            pcmPacketCount = 0L
            bitActivityMask = 0
            peakDbL = -60f
            peakDbR = -60f
            currentLatencyMs = 0
            walkmanLevelMeter?.reset()
            playbackService?.resetBuffer("direct")
            playbackService?.setUpsampling(upsampleFactor)
            // ★ Phase 1: 引数で渡していた構成は全て Service の自フィールドが正（initAudioTrack は引数なし）
            playbackService?.initAudioTrack()
            geckoController.reload()
            updateStatus()
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (isVolLockOn && (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP)) {
            if (isUsbDevice(activeOutputDevice)) {
                playbackService?.lockSystemVolumeToMax()
                if (event?.repeatCount == 0) {
                    if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
                        noteUserPlaybackIntent("next")
                        geckoController.sendCommand("next")
                    } else if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
                        noteUserPlaybackIntent("prev")
                        geckoController.sendCommand("prev")
                    }
                    // 曲送りが発火した押下でのみ1回鳴らす（長押しリピートでは鳴らさない）
                    mainRootLayout.hapticTick(appPrefs)
                }
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        if (isVolLockOn && (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP)) {
            if (isUsbDevice(activeOutputDevice)) return true
        }
        return super.onKeyUp(keyCode, event)
    }

    private fun handleEmergencyUsbDacCutoff() {
        if (isHandlingDisconnect) return
        isHandlingDisconnect = true

        try {
            geckoController.sendCommand("pause")
            isPlayingState = false

            isVolLockOn = false
            appPrefs.isVolLockEnabled = false
            playbackService?.handleBecomingNoisyOrDisconnected()

            peakDbL = -60f
            peakDbR = -60f
            bitActivityMask = 0
            currentLatencyMs = 0
            walkmanLevelMeter?.reset()

            activeOutputDevice = null
            outputDeviceName = "内蔵スピーカー"
            updateStatus()
            updateDialogPlayerUi()

            uiHandler.postDelayed({
                playbackService?.forceResetSpeakerVolume()
                try {
                    audioManager?.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
                } catch (e: Exception) {}
            }, 150)
            uiHandler.postDelayed({
                try {
                    audioManager?.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
                } catch (e: Exception) {}
            }, 350)
        } finally {
            uiHandler.postDelayed({ isHandlingDisconnect = false }, 500)
        }
    }

    private fun registerAudioDeviceCallback() {
        audioManager?.registerAudioDeviceCallback(object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
                uiHandler.removeCallbacks(deviceDetectRunnable)
                uiHandler.postDelayed(deviceDetectRunnable, 250)
            }
            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
                val wasUsbActive = isUsbDevice(activeOutputDevice)
                val isCurrentRemoved = removedDevices?.any { it.id == activeOutputDevice?.id || isUsbDevice(it) } == true

                if (wasUsbActive && isCurrentRemoved) {
                    handleEmergencyUsbDacCutoff()
                }

                uiHandler.removeCallbacks(deviceDetectRunnable)
                uiHandler.postDelayed(deviceDetectRunnable, 100)
            }
        }, Handler(Looper.getMainLooper()))
    }

    private fun detectAudioOutputDevice() {
        val devices = audioManager?.getDevices(AudioManager.GET_DEVICES_OUTPUTS) ?: return
        var usbDevice: AudioDeviceInfo? = null
        var btDevice: AudioDeviceInfo? = null

        for (device in devices) {
            if (device.type == AudioDeviceInfo.TYPE_USB_DEVICE || device.type == AudioDeviceInfo.TYPE_USB_HEADSET) {
                usbDevice = device
                break
            } else if (device.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                       (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && (
                           device.type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
                           device.type == AudioDeviceInfo.TYPE_BLE_SPEAKER
                       )) ||
                       device.type == AudioDeviceInfo.TYPE_HEARING_AID) {
                if (btDevice == null) btDevice = device
            }
        }

        if (usbDevice != null) {
            activeOutputDevice = usbDevice
            outputDeviceName = usbDevice.productName.toString().replace("USB-Audio - ", "")
            playbackService?.restoreVolumeForDevice(usbDevice)
        } else if (btDevice != null) {
            activeOutputDevice = btDevice
            val rawName = btDevice.productName.toString()
            outputDeviceName = if (rawName.isNotEmpty()) rawName else "Bluetooth Audio"
        } else {
            activeOutputDevice = null
            outputDeviceName = "内蔵スピーカー"
            playbackService?.isVolumeLocked = false
            playbackService?.forceResetSpeakerVolume()
        }

        updateStatus()
    }

    private fun updateStatus() {
        val mb = pcmPacketCount / (1024.0 * 1024.0)
        val dev = activeOutputDevice
        val isUsb = isUsbDevice(dev)
        
        // ★ Phase 0: バインド済みの間は Service のスナップショットを正として表示する
        val stateSnapshot = playbackService?.getPlaybackStateSnapshot()
        val activeFactor = stateSnapshot?.effectiveFactor ?: (if (isDirectSource) 1 else upsampleFactor)
        val dspTag = if (isDirectSource) " [DIRECT]" else (if (activeFactor > 1) " [DSP ${activeFactor}x]" else "")

        if (dev != null) {
            if (isUsb) {
                badgeDirect.text = if (isDirectSource) "DIRECT SOURCE" else "DIRECT STREAM$dspTag"
                badgeDirect.setBackgroundResource(R.drawable.bg_badge_direct)
                badgeDirect.setTextColor(Color.BLACK)
                textCodec.text = currentCodec.uppercase()
            } else {
                val btCodecBadge = if (currentBtCodecName.isNotEmpty()) "BT [$currentBtCodecName]$dspTag" else "BLUETOOTH$dspTag"
                badgeDirect.text = btCodecBadge
                badgeDirect.setBackgroundResource(R.drawable.bg_badge_bluetooth)
                badgeDirect.setTextColor(Color.BLACK)
                textCodec.text = if (currentBtCodecName.isNotEmpty()) "$currentBtCodecName | ${currentCodec.uppercase()}" else currentCodec.uppercase()
            }
        } else {
            badgeDirect.text = "STANDARD MIX$dspTag"
            badgeDirect.setBackgroundResource(R.drawable.bg_badge_normal)
            badgeDirect.setTextColor(Color.LTGRAY)
            textCodec.text = currentCodec.uppercase()
        }
        textDacName.text = outputDeviceName

        // ★ Phase 4: ステータス表示は実績値（スナップショット）を読む。
        //   未バインド時のみ要求値（currentBitMode）で代替する。
        val displayBitMode = stateSnapshot?.actualBitMode ?: currentBitMode
        val bitLabel = when (displayBitMode) {
            "32bit" -> "32 bit"
            "24bit" -> "24 bit"
            else -> "16 bit"
        }

        val effectiveRate = stateSnapshot?.effectiveSampleRate ?: (baseSampleRate * activeFactor)
        val rateStr = String.format(java.util.Locale.US, "%.1f", effectiveRate / 1000.0)
        textRateBits.text = "$rateStr kHz / $bitLabel"
        
        // 転送量 (MB) をクリーンに表示
        textTransfer.text = String.format(java.util.Locale.US, "%.1f MB", mb)

        // ★ SW連動: ONなら全追加テレメトリ (CPU/RAM, BUF, LAT) を表示、OFFなら完全に非表示
        if (appPrefs.isSysMonitorEnabled) {
            textCpuRamMonitor.visibility = View.VISIBLE
            textCpuRamMonitor.text = "CPU:${currentCpuUsagePercent}%  RAM:${currentRamUsageMb}M"

            textBufferLatency.visibility = View.VISIBLE
            val bufPercent = if (isPlayingState) ((currentQueueSize * 100) / 64).coerceIn(0, 100) else 100
            val latStr = if (isPlayingState && currentLatencyMs > 0) "${currentLatencyMs}ms" else "--"
            // ★ バッファ破棄の計測カウンタ（Service 起動以来の累計）を併記する
            val svc = playbackService
            val drop = svc?.bufferDropCount?.get() ?: 0L
            val dropMs = svc?.bufferDropMs?.get() ?: 0L
            // ★ FL / UND は表示から外した（行の折り返し対策で表示のみ削除）。
            //   Service 側の計測カウンタと logcat 出力は従来どおり残る。
            // ★ True Peak ガードの現在低減量（Service の表示専用キャッシュから読む）。
            //   Direct 時・未バインド時・ガード OFF 時はガードが働かないため "-" 表示にする。
            //   OFF 時は native 側の統計が最後の低減量を保持し続けるため、表示側で Prefs の
            //   ON/OFF を見て "-" に倒す（キャッシュや Prefs への書き戻しはしない）。
            val tpCurrent = svc?.truePeakCurrentReductionDb ?: 0f
            val tpStr = if (svc != null && !svc.isDirectSource && appPrefs.isTruePeakGuardEnabled) String.format(java.util.Locale.US, "-%.1f", tpCurrent) else "-"
            textBufferLatency.text = "BUF:${bufPercent}% LAT:${latStr} DROP:${drop}(${dropMs}ms) TP:${tpStr}dB"
            textBufferLatency.setTextColor(if (isDarkThemeActive()) Color.parseColor("#888888") else Color.parseColor("#636366"))
        } else {
            textCpuRamMonitor.visibility = View.GONE
            textBufferLatency.visibility = View.GONE
        }

        val peakTextL = if (peakDbL > -50f && isPlayingState) String.format(java.util.Locale.US, "%.1f", peakDbL) else "-inf"
        val peakTextR = if (peakDbR > -50f && isPlayingState) String.format(java.util.Locale.US, "%.1f", peakDbR) else "-inf"
        textPeak.text = "PEAK  L: ${peakTextL} / R: ${peakTextR} dB"

        val maxBits = if (displayBitMode == "32bit") 32 else (if (displayBitMode == "24bit") 24 else 16)
        val activeBits = if (!isPlayingState || (peakDbL <= -50f && peakDbR <= -50f)) 0 else Integer.bitCount(bitActivityMask).coerceIn(0, maxBits)
        textBitDepth.text = "BIT: $activeBits/$maxBits ACTIVE"
        textBitDepth.setTextColor(Color.parseColor("#E5A93C"))

        val now = System.currentTimeMillis()
        if (now - lastBitResetTime > 250L) {
            bitActivityMask = (bitActivityMask ushr 2) or (bitActivityMask and 0x01)
            lastBitResetTime = now
        }
    }

    override fun onPause() {
        super.onPause()
        geckoController.onPause()
    }

    override fun onStop() {
        super.onStop()
        geckoController.onStop()
    }

    override fun onDestroy() {
        super.onDestroy()
        uiHandler.removeCallbacks(uiUpdateRunnable)
        uiHandler.removeCallbacks(deviceDetectRunnable)
        if (appWakeLock?.isHeld == true) appWakeLock?.release()
        btCodecTracker.stop()
        geckoController.onDestroy()
        if (isServiceBound) {
            unbindService(serviceConnection)
            isServiceBound = false
        }
    }
}