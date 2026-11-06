package com.example.perfectbitrate

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.util.LruCache
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import org.mozilla.geckoview.GeckoView
import java.net.URL
import java.util.Locale
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

    private lateinit var geckoView: GeckoView
    private lateinit var localPlaceholder: View
    private lateinit var textLocalTitle: TextView
    private lateinit var textLocalMessage: TextView
    private lateinit var textModeSwitch: TextView
    private lateinit var localTrackList: ListView
    private lateinit var btnLocalAddFolder: TextView
    private lateinit var btnLocalRescan: TextView
    private lateinit var localScanProgress: View
    private lateinit var progressLocalScan: ProgressBar
    private lateinit var textLocalScanStatus: TextView
    private lateinit var textLocalScanCurrent: TextView
    private lateinit var localScanUpdating: View
    private lateinit var textLocalUpdating: TextView
    // ★ ライブラリブラウザのクローム（Phase 2-1: タブ / 検索 / ソート /
    //   ドリルダウン用ヘッダー）
    private lateinit var tabLocalNowPlaying: TextView
    private lateinit var tabLocalAll: TextView
    private lateinit var tabLocalAlbum: TextView
    private lateinit var tabLocalArtist: TextView
    private lateinit var tabLocalFolder: TextView
    private lateinit var editLocalSearch: EditText
    private lateinit var btnLocalSort: TextView
    private lateinit var localGroupHeader: View
    private lateinit var btnLocalBack: TextView
    private lateinit var textLocalGroupTitle: TextView
    // ★ 再生ページ（「再生」タブの Now Playing）とブラウザページ。
    //   再生画面は全画面オーバーレイではなく Local コンテンツ領域内の
    //   ページとして表示する（上部ステータスヘッダーは常時表示）。
    private lateinit var localBrowserPage: View
    private lateinit var localNowPlaying: View
    private lateinit var localNpContent: View
    private lateinit var textNpEmpty: TextView
    private lateinit var textNpQueuePosition: TextView
    private lateinit var imgNpArtwork: ImageView
    private lateinit var textNpTitle: TextView
    private lateinit var textNpArtist: TextView
    private lateinit var textNpAlbum: TextView
    private lateinit var textNpFormat: TextView
    private lateinit var seekNpProgress: SeekBar
    private lateinit var textNpCurrentTime: TextView
    private lateinit var textNpTotalTime: TextView
    private lateinit var btnNpShuffle: ImageButton
    private lateinit var btnNpPrev: ImageButton
    private lateinit var btnNpPlayPause: ImageButton
    private lateinit var btnNpNext: ImageButton
    private lateinit var btnNpRepeat: ImageButton

    // ★ 再生画面のシークバーのドラッグ中フラグ
    //   （ドラッグ中は進捗ポーリングで表示位置を上書きしない）。
    private var isNpSeeking = false

    // ★ Local Music の供給係と楽曲インデックス（仕様書 §4.1 / §6）
    private lateinit var libraryRepository: LibraryRepository
    private lateinit var localPlayer: LocalPlayer
    private var localTracks: List<LibraryRepository.Track> = emptyList()
    private var localTrackAdapter: LocalTrackAdapter? = null
    private var lastLocalProgressPushTime = 0L
    // ★ ライブラリ表示まわり: 一覧（キャッシュ含む）を表示済みかと、
    //   スキャンの多重実行ガード（仕様書 §6.2 の永続化キャッシュ表示用）
    private var localLibraryShown = false
    private var localScanRunning = false

    // ★ ライブラリブラウザの状態（Phase 2-1、下部タブバー）。
    //   localTab: 表示中のタブ（NOW_PLAYING = 再生ページ、それ以外は
    //   ブラウザページ）。localGroupKey: ドリルダウン中のグループ
    //   キー（null = 全曲リスト or グループ一覧を表示中）。
    //   localDisplayedTracks: いま曲リストとして表示している並び
    //   （＝ LocalPlayer へ渡しているキューと一致させる）。
    private enum class LocalTab { NOW_PLAYING, ALL, ALBUM, ARTIST, FOLDER }

    private data class LocalGroup(
        val key: String,
        val label: String,
        val tracks: List<LibraryRepository.Track>,
        // グループ内のアーティストが1種類だけならその名前（アルバム一覧の
        // 補助表示用）。混在・全て空なら null。
        val uniformArtist: String?
    )

    private var localTab = LocalTab.ALL
    private var localGroupKey: String? = null
    private var localSearchQuery: String = ""
    private var localDisplayedTracks: List<LibraryRepository.Track> = emptyList()
    private var localGroupAdapter: LocalGroupAdapter? = null

    private val unknownAlbumLabel = "不明なアルバム"
    private val unknownArtistLabel = "不明なアーティスト"
    private val unknownFolderLabel = "その他のフォルダ"

    // サムネイルのメモリキャッシュ（URI 文字列 → 取得済み Bitmap、約 8MB）
    private val localThumbCache = object : LruCache<String, Bitmap>(8 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

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
    // ★ 再生モード（仕様書 §3）: 正は AppPreferences.playbackMode で、このフィールドは
    //   現在動作中のモードのキャッシュ。変更は switchPlaybackMode 経由でのみ行う。
    private var currentPlaybackMode: PlaybackMode = PlaybackMode.YT_MUSIC
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

    // ★ 最終段: GeckoSession 再生成（ステップ 3 でも PCM が戻らない場合のみ）。
    //   実機で「通知系（progress/meta/flush）は生きているのに PCM だけ全途絶し、
    //   スキップでもページ reload でも直らず、アプリ再起動でのみ復活する」事象を
    //   2 回観測したための段。ページ内操作では届かない、セッション寿命に紐づく
    //   捕捉経路の死を想定し、セッション自体を作り直す。
    private val SESSION_RECREATE_DELAY_MS = 10000L     // ステップ 3 到達後、再生成段の判定まで待つ時間
    private val SESSION_RECREATE_COOLDOWN_MS = 300000L // セッション再生成の最小間隔（5分）
    private val RECREATE_PLAY_DELAY_MS = 5000L         // 再生成後、play を送るまでの最小待ち時間
    private val RECREATE_FOLLOWUP_TIMEOUT_MS = 60000L  // 再生成フォローアップの打ち切り
    private var stallStep3Since = 0L                   // ステップ 3（reload 段）に到達した時刻（再生成段の遅延判定の起点）
    private var sessionRecreateDoneInEpisode = false   // このエピソードで再生成段を処理済みか（発火/見送り共通）
    private var lastSessionRecreateTime = 0L           // 最後にセッション再生成を実施した時刻（クールダウン判定）
    private var sessionRecreatePendingTime = 0L        // 再生成を実施し復帰フォローアップ中の時刻（0=フォローアップなし）
    private var lastPageSignalTime = 0L                // content.js から通知が最後に届いた時刻（ページ生存判定用）

    // ★ ユーザーの再生意思: ウォッチドッグ全体の発火条件はこちらを主軸にする。
    //   isPlayingState はページ要素の paused で毎秒上書きされ、Gecko 側都合の
    //   強制ポーズとユーザーのポーズを区別できないため、ゲートには使わない。
    //   記録経路は2つで、最後の操作が勝つ:
    //   - アプリ経由の明示操作（noteUserPlaybackIntent: ポーズで false、
    //     play/next/prev で true）
    //   - content.js の state 通知に含まれるページ側の意思（onPlaybackIntent）。
    //     ページ UI のポーズも pause() フック経由で意思 false として届く。一方、
    //     要素の 'pause' イベントだけでは意思は落ちない（強制ポーズでも発火する
    //     ため、content.js 側でそう実装されている）ので、強制ポーズ時は意思が
    //     true のまま残り、ウォッチドッグの段階復旧が届く。
    private var userWantsPlayback = true

    // ★ コンテンツプロセスのクラッシュ/キル検知からの再生成（ContentDelegate 経由）用。
    //   ウォッチドッグの時間ベース検知とは独立のイベント駆動経路だが、再生成の
    //   総量抑制のため lastSessionRecreateTime はウォッチドッグ側と共有する。
    private val CRASH_RECREATE_COOLDOWN_MS = 60000L // クラッシュ由来の再生成の最小間隔
    private var lastCrashRecreateTime = 0L           // 最後にクラッシュ由来で再生成した時刻

    // ★ ネットワーク切替検知（ConnectivityManager.NetworkCallback）用。
    //   デフォルトネットワークの切替を購読し、切替イベントを UND 総数付きで記録する
    //   （症状3「回線切替時にくるくる」とアンダーランの相関を後から追えるように）。
    //   新ネットワークが使えるようになったら、デバウンス後に再生中のみ resume_audio を
    //   1 回先回りで送る。ACCESS_NETWORK_STATE 権限は宣言済み（追加権限なし）。
    private val NETWORK_NUDGE_DEBOUNCE_MS = 500L      // 切替直後のイベントを1回にまとめる待ち
    private val NETWORK_NUDGE_MIN_INTERVAL_MS = 10000L // nudge の最小間隔（連発の間引き）
    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var networkInitialAvailablePending = false // 登録時に既存ネットワークで即時呼ばれる onAvailable を切替と誤認しない
    private var lastNetworkNudgeTime = 0L
    private val networkNudgeRunnable = Runnable { nudgePlaybackAfterNetworkChange() }

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

            // ★ Local モードの進捗ポーリング（仕様書 §4.3: 250ms 周期で通知）。
            //   デコード位置は LocalPlayer が保持し、ここで Service / ダイアログへ流す。
            if (currentPlaybackMode == PlaybackMode.LOCAL &&
                ::localPlayer.isInitialized && localPlayer.isPlaying &&
                now - lastLocalProgressPushTime >= 250L
            ) {
                lastLocalProgressPushTime = now
                val (pos, dur) = localPlayer.positionSnapshot()
                if (pos != currentPosition || dur != currentDuration) {
                    currentPosition = pos
                    currentDuration = dur
                    playbackService?.updateProgress(pos, dur, true)
                    updateDialogPlayerUi()
                    updateLocalNowPlayingProgress()
                }
            }

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

    // ★ PCM ストール・ウォッチドッグ: ユーザーが再生を望んでいるのに PCM が途絶
    //   していたら、resume_audio → play → ページ reload → GeckoSession 再生成の
    //   順に段階的に復旧を試みる。ゲートは「再生意思（userWantsPlayback）があり、
    //   PCM が途絶している」ことなので、メディア要素が paused に倒れる型の
    //   ストールでも全段が届く。逆に、意図的な一時停止中（再生意思なし）は
    //   絶対に発火しない。
    //   PCM が復帰するか再生意思がなくなればエピソードはリセットされ、reload は
    //   60 秒に 1 回まで、セッション再生成は 5 分に 1 回までしか行わない。
    private fun checkPlaybackStall(now: Long) {
        if (currentPlaybackMode != PlaybackMode.YT_MUSIC) {
            // ★ Local モードでは Gecko 向け復旧段は動かさない（仕様書 §3.3）。
            //   Local 側のストール検知は LocalPlayer 実装時に追加する。
            resetStallEpisode()
            return
        }
        if (!userWantsPlayback || lastPcmTime == 0L) {
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
                    stallStep3Since = now
                    if (now - lastWatchdogReloadTime >= WATCHDOG_RELOAD_COOLDOWN_MS) {
                        lastWatchdogReloadTime = now
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
        stallStep3Since = 0L
        sessionRecreateDoneInEpisode = false
    }

    // ★ 最終段: ステップ 3（reload 段）でも PCM が戻らない場合に限り、GeckoSession
    //   自体を作り直す。発火条件は全て満たすこと:
    //   - ステップ 3 に到達してから SESSION_RECREATE_DELAY_MS 経っても PCM が
    //     戻っていない（この関数自体が PCM 途絶中のステップ 3 からしか呼ばれない）。
    //     reload がクールダウンでスキップされたエピソードでも、reload の実施有無に
    //     依存せずここへ到達する
    //   - ユーザーが再生意思を持っている（checkPlaybackStall のゲートでも
    //     保証されるが、ここでも再確認する）
    //   - セッション再生成のクールダウン（5 分）を過ぎている
    //   発火・見送りいずれの場合も 1 エピソードに 1 回だけ判定し、以後は繰り返さない。
    private fun trySessionRecreate(now: Long) {
        if (sessionRecreateDoneInEpisode) return
        if (now - stallStep3Since < SESSION_RECREATE_DELAY_MS) return
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
        Log.w("MainActivity", "★ PCM stall persists in step 3, recreating Gecko session")
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
        if (currentPlaybackMode != PlaybackMode.YT_MUSIC) {
            // ★ Local モード中は再生成フォローアップの play を送らない
            sessionRecreatePendingTime = 0L
            return
        }
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

    // ★ ユーザーの明示操作による再生/ポーズ意思の記録（ウォッチドッグの発火条件用）。
    //   記録経路のうちアプリ経由分がこちら（ページ側分は onPlaybackIntent）:
    //   - ダイアログのプレイヤーボタン（play_pause は解決後の実際のコマンドで記録）
    //   - 通知/メディアセッション経由（Service の onCommandListener。ただし呼び出し側で
    //     "pause" は渡されない: DAC 切断時の自動ポーズも同じ経路で流れ、区別できないため。
    //     通知ポーズは 'pause' コマンドを受けた content.js が意思 false を state 通知で
    //     返し、onPlaybackIntent 経由で記録されるので、ここで記録しなくても意図せぬ
    //     復旧・再生成にはつながらない）
    //   - Vol ロック時の音量キー曲送り
    //   ウォッチドッグ自身の送信（resume_audio/play）はここを通らないため影響しない。
    private fun noteUserPlaybackIntent(command: String) {
        when (command) {
            "pause" -> userWantsPlayback = false
            "play", "next", "prev" -> userWantsPlayback = true
        }
    }

    // ★ LocalPlayer からの通知の受け口。GeckoSessionController.Listener と
    //   同じ形で MainActivity / Service へ流し、上部ステータス表示が Local の
    //   曲データに追従するようにする（仕様書 §4.1 / §7.1）。
    //   コールバックはデコードスレッド等から届くため、表示系は UI スレッドへ寄せる。
    private val localPlayerListener = object : LocalPlayer.Listener {
        override fun onPcm(pcmBytes: ByteArray, sampleRate: Int) {
            pcmPacketCount += pcmBytes.size
            isPlayingState = true
            lastPcmTime = System.currentTimeMillis()
            playbackService?.pushPcm(pcmBytes, sampleRate, "float32")
        }

        override fun onCodec(codec: String, rate: Int) {
            currentCodec = codec
            if (rate > 0 && rate != baseSampleRate) {
                playbackService?.resetBuffer("codec")
                baseSampleRate = rate
                playbackService?.updateBaseSampleRate(rate)
            }
            playbackService?.updateCodec(codec)
            runOnUiThread { updateStatus() }
        }

        override fun onFlush() {
            playbackService?.resetBuffer("local")
        }

        override fun onMetadata(title: String, artist: String, artwork: Bitmap?) {
            currentTitle = title
            currentArtist = artist
            currentArtworkBitmap = artwork
            playbackService?.updateMetadataWithBitmap(title, artist, artwork)
            runOnUiThread {
                updateDialogPlayerUi()
                // ★ アートワーク到着も含め、再生画面へ反映する
                updateLocalPlayerChrome()
            }
        }

        override fun onProgress(currentMs: Long, durationMs: Long, isPlaying: Boolean) {
            currentPosition = currentMs
            currentDuration = durationMs
            isPlayingState = isPlaying
            playbackService?.updateProgress(currentMs, durationMs, isPlaying)
            runOnUiThread {
                updateDialogPlayerUi()
                updateLocalNowPlayingProgress()
            }
        }

        override fun onState(isPlaying: Boolean) {
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
            runOnUiThread {
                updateDialogPlayerUi()
                updateLocalPlayerChrome()
                localTrackAdapter?.notifyDataSetChanged()
            }
        }

        override fun onTrackChanged(index: Int, track: LibraryRepository.Track) {
            runOnUiThread {
                updateLocalPlayerChrome()
                localTrackAdapter?.notifyDataSetChanged()
            }
        }
    }

    // ★ モード切替（仕様書 §3.2）: 必ず再生を停止し PCM を flush してから切り替える
    //   （両ソースの PCM が混ざる瞬間を作らない）。
    //   YT → Local ではページをポーズするだけで、Gecko セッションは破棄せず保持し、
    //   戻ったときに再ログイン・再読み込みなしで再表示できるようにする。あわせて
    //   再生意思も落とし、YT に戻った直後にウォッチドッグが勝手に再生再開しない
    //   ようにする。Local → YT は LocalPlayer を停止しデコーダを解放する。
    private fun switchPlaybackMode(newMode: PlaybackMode) {
        if (newMode == currentPlaybackMode) return
        // ★ 再生ページは Local コンテンツ領域内の一ページのため、
        //   モードを離れると localPlaceholder ごと隠れる。選択中の
        //   タブは保持され、Local へ戻れば同じページが復元される。
        if (currentPlaybackMode == PlaybackMode.YT_MUSIC) {
            geckoController.sendCommand("pause")
            userWantsPlayback = false
        } else {
            localPlayer.stop()
        }
        playbackService?.resetBuffer("mode")
        currentPlaybackMode = newMode
        appPrefs.playbackMode = newMode
        isPlayingState = false
        applyPlaybackModeVisibility()
        updateModeSwitchUi()
        if (newMode == PlaybackMode.LOCAL) {
            // ★ 上部ステータスは前のソース（YT）の表示を残さず、Local の初期
            //   表示へ倒す（仕様書 §7.1 のステータス追従）。曲が始まれば
            //   LocalPlayer の onCodec / onMetadata が実データで上書きする。
            currentTitle = "Local Music"
            currentArtist = ""
            currentArtworkBitmap = null
            currentCodec = "LOCAL"
            currentPosition = 0L
            currentDuration = 0L
            playbackService?.updateMetadataWithBitmap(currentTitle, currentArtist, null)
            playbackService?.updateProgress(0L, 0L, false)
            ensureLocalLibrary()
        } else {
            // ★ YT へ戻す際は、Local 側が書き換えたソース依存のストリーム
            //   状態を YT の既定へ戻す。特に baseSampleRate: Local の
            //   onCodec がファイルのレート（例 44.1kHz）に更新したままだと、
            //   保持された Gecko ページは codec 通知を再送しない
            //   （content.js は検出コーデック/レートが変わったときだけ
            //   送る）ため、48kHz の YT の PCM が 44.1kHz 前提のチェーンで
            //   処理されて再生が遅くなる。Local 開始時は LocalPlayer が
            //   毎曲 onCodec を送るため、ここで既定へ戻しても対称性は
            //   崩れない。あわせてステータス表示も前ソースの値を残さない。
            baseSampleRate = 48000
            playbackService?.updateBaseSampleRate(48000)
            currentTitle = "YouTube Music"
            currentArtist = ""
            currentArtworkBitmap = null
            currentCodec = "OPUS 160kbps (48k)"
            currentPosition = 0L
            currentDuration = 0L
            playbackService?.updateCodec(currentCodec)
            playbackService?.updateMetadataWithBitmap(currentTitle, currentArtist, null)
            playbackService?.updateProgress(0L, 0L, false)
        }
        // ★ 再生ページの表示もモードと現在曲に合わせておく（Local へ
        //   戻ったときに前回曲の情報が再生中表示のまま残らないように）
        updateLocalPlayerChrome()
        updateStatus()
        updateDialogPlayerUi()
        Toast.makeText(this, "モード: ${newMode.displayName}", Toast.LENGTH_SHORT).show()
    }

    // ★ Local モードのライブラリ初期化: FFmpeg の有無 → 権限 → 読み込みの順に
    //   案内し、問題があればプレースホルダのメッセージで状態を示す。
    private fun ensureLocalLibrary() {
        if (!FfmpegDecoder.isAvailable) {
            textLocalMessage.text = "FFmpeg ライブラリが見つかりません（jniLibs への .so 配置が必要です）"
            textLocalMessage.visibility = View.VISIBLE
            return
        }
        val needsPermission = appPrefs.useMediaStore &&
            appPrefs.safFolderUris.isEmpty() &&
            !libraryRepository.hasAudioPermission()
        if (needsPermission) {
            textLocalMessage.text = "端末内の曲を表示するには、音声ファイルへのアクセス許可が必要です"
            textLocalMessage.visibility = View.VISIBLE
            requestAudioPermission()
            return
        }
        reloadLocalLibrary()
    }

    // ★ ライブラリ読み込みの入口（仕様書 §6.2 の永続化キャッシュ対応）:
    //   永続化済みキャッシュがあればまずそれを即表示し、その裏で差分
    //   スキャンを走らせる。一覧を既に表示している場合は差分スキャンだけ。
    //   キャッシュが無い初回は進捗ブロックを見せながらスキャンする。
    private fun reloadLocalLibrary() {
        if (!FfmpegDecoder.isAvailable) {
            ensureLocalLibrary()
            return
        }
        if (localLibraryShown) {
            startLocalRefresh(forceFull = false)
            return
        }
        libraryRepository.loadCachedTracks { cached ->
            runOnUiThread {
                if (currentPlaybackMode != PlaybackMode.LOCAL) return@runOnUiThread
                if (cached.isNotEmpty()) {
                    applyLocalTracks(cached)
                }
                startLocalRefresh(forceFull = false)
            }
        }
    }

    // ★ スキャンの実行（差分 or 強制フル）と進捗表示の制御。
    //   進捗表示は2段構えにする:
    //   - 一覧がまだ無い / 強制フル: プログレスバーの進捗ブロック
    //     （走査中は「検索中… n 件」、取得中は「読み込み中 n/N 件」＋対象名）
    //   - 一覧表示中の裏側差分: 小さな「更新中 n/N 件」インジケータのみ
    private fun startLocalRefresh(forceFull: Boolean) {
        if (localScanRunning) return
        if (!FfmpegDecoder.isAvailable) {
            ensureLocalLibrary()
            return
        }
        // ★ 設定画面で「ライブラリの再スキャン」が予約されていたら、この
        //   スキャンを強制フルリフレッシュとして実行し、予約を消費する
        //   （予約の消費はスキャンを実際に開始できる場合に限る）。
        val rescanRequested = appPrefs.localRescanRequested
        if (rescanRequested) {
            appPrefs.localRescanRequested = false
        }
        val effectiveForceFull = forceFull || rescanRequested
        localScanRunning = true
        val foreground = effectiveForceFull || !localLibraryShown
        if (foreground) {
            textLocalMessage.visibility = View.GONE
            localScanUpdating.visibility = View.GONE
            progressLocalScan.isIndeterminate = true
            textLocalScanStatus.text = "ライブラリを検索中…"
            textLocalScanCurrent.text = ""
            localScanProgress.visibility = View.VISIBLE
        } else {
            localScanProgress.visibility = View.GONE
            textLocalUpdating.text = "ライブラリを更新中…"
            localScanUpdating.visibility = View.VISIBLE
        }
        libraryRepository.refreshLibrary(
            forceFull = effectiveForceFull,
            onProgress = { progress ->
                runOnUiThread { updateLocalScanProgress(progress, foreground) }
            }
        ) { tracks ->
            runOnUiThread {
                localScanRunning = false
                localScanProgress.visibility = View.GONE
                localScanUpdating.visibility = View.GONE
                if (currentPlaybackMode != PlaybackMode.LOCAL) return@runOnUiThread
                applyLocalTracks(tracks)
            }
        }
    }

    private fun updateLocalScanProgress(
        progress: LibraryRepository.ScanProgress,
        foreground: Boolean
    ) {
        if (currentPlaybackMode != PlaybackMode.LOCAL) return
        if (foreground) {
            when (progress.phase) {
                LibraryRepository.ScanProgress.Phase.SCANNING -> {
                    progressLocalScan.isIndeterminate = true
                    textLocalScanStatus.text = "端末内の曲を検索中… ${progress.processed} 件"
                }
                LibraryRepository.ScanProgress.Phase.PROBING -> {
                    progressLocalScan.isIndeterminate = false
                    progressLocalScan.max = progress.total
                    progressLocalScan.progress = progress.processed
                    textLocalScanStatus.text =
                        "読み込み中 ${progress.processed}/${progress.total} 件"
                }
            }
            textLocalScanCurrent.text = progress.currentName
        } else if (progress.phase == LibraryRepository.ScanProgress.Phase.PROBING &&
            progress.total > 0
        ) {
            // 控えめ表示でも件数だけは更新し、進んでいることを示す
            textLocalUpdating.text =
                "ライブラリを更新中… ${progress.processed}/${progress.total} 件"
        }
    }

    // ★ 一覧の反映（キャッシュ表示・スキャン結果で共通）。
    //   実際の表示（タブ / 検索 / ソート / ドリルダウンの適用と、表示中
    //   リスト＝再生キューの同期）は refreshLocalBrowser に集約する。
    private fun applyLocalTracks(tracks: List<LibraryRepository.Track>) {
        localTracks = tracks
        localLibraryShown = tracks.isNotEmpty()
        refreshLocalBrowser()
    }

    // ── ライブラリブラウザ（Phase 2-1） ──
    //
    // タブ（全曲 / アルバム / アーティスト / フォルダ）× 検索フィルタ ×
    // ソートから表示内容を導出する単一の入口。表示が「曲リスト」の間は
    // その並びが LocalPlayer のキューになる（タップ再生・次曲送り・
    // 自動進行がすべて表示順と一致する）。表示が「グループリスト」の
    // 間はキューに触らない。

    // ★ Local コンテンツ領域内のページ出し分け: 「再生」タブなら
    //   再生ページ、それ以外はブラウザページ（検索 / 一覧）を出す。
    //   上部ステータスヘッダー（topInfoPanel）はモード共通で常時
    //   表示される領域のため、ここでは一切触らない。選択中タブは
    //   localTab が唯一の正で、再描画のたびにここから復元される。
    private fun updateLocalPageVisibility() {
        if (!::localNowPlaying.isInitialized) return
        val showNowPlaying = localTab == LocalTab.NOW_PLAYING
        localNowPlaying.visibility = if (showNowPlaying) View.VISIBLE else View.GONE
        localBrowserPage.visibility = if (showNowPlaying) View.GONE else View.VISIBLE
        if (showNowPlaying) {
            if (::localPlayer.isInitialized) {
                updateLocalNowPlaying(localPlayer.currentTrack)
            }
            // ブラウザ側の空表示メッセージは再生ページでは出さない
            textLocalMessage.visibility = View.GONE
        }
    }

    private fun refreshLocalBrowser() {
        if (!::localTrackList.isInitialized) return
        // ★ 「再生」タブ表示中はブラウザ側（一覧の導出・キューの
        //   同期）には触らず、ページの出し分けだけを行う。
        if (localTab == LocalTab.NOW_PLAYING) {
            updateLocalPageVisibility()
            updateLocalTabStyles()
            return
        }
        updateLocalPageVisibility()
        val groupKey = localGroupKey
        if (groupKey != null && localTab != LocalTab.ALL) {
            // ドリルダウン中: 選択中グループの曲一覧（検索フィルタ適用、
            // 並びはタイトル順で固定。グループ内の曲順はソート対象外）
            val groupTracks = localTracks
                .filter { localGroupKeyOf(localTab, it) == groupKey }
                .filter { localTrackMatchesQuery(it, localSearchQuery) }
            showLocalTrackPage(sortLocalTracks(groupTracks, AppPreferences.LOCAL_SORT_TITLE))
            textLocalGroupTitle.text = localGroupLabel(localTab, groupKey)
            localGroupHeader.visibility = View.VISIBLE
        } else {
            localGroupHeader.visibility = View.GONE
            when (localTab) {
                LocalTab.ALL -> {
                    val filtered = localTracks
                        .filter { localTrackMatchesQuery(it, localSearchQuery) }
                    showLocalTrackPage(sortLocalTracks(filtered, appPrefs.localSortMode))
                }
                LocalTab.ALBUM, LocalTab.ARTIST, LocalTab.FOLDER -> {
                    val groups = localGroupsFor(localTab, localSearchQuery)
                    localDisplayedTracks = emptyList()
                    val adapter = LocalGroupAdapter(groups)
                    localGroupAdapter = adapter
                    localTrackList.adapter = adapter
                    updateLocalEmptyMessage(groups.size, isTrackPage = false)
                }
            }
        }
        updateLocalTabStyles()
    }

    // 曲リストを表示し、その並びを再生キューとして LocalPlayer へ渡す。
    private fun showLocalTrackPage(tracks: List<LibraryRepository.Track>) {
        localDisplayedTracks = tracks
        val adapter = LocalTrackAdapter(tracks)
        localTrackAdapter = adapter
        localTrackList.adapter = adapter
        if (::localPlayer.isInitialized) localPlayer.setQueue(tracks)
        updateLocalEmptyMessage(tracks.size, isTrackPage = true)
    }

    private fun updateLocalEmptyMessage(displayedCount: Int, isTrackPage: Boolean) {
        if (localTracks.isEmpty()) {
            textLocalMessage.text = if (!libraryRepository.hasAudioPermission() && appPrefs.safFolderUris.isEmpty()) {
                "曲が見つかりませんでした。音声ファイルへのアクセスを許可するか、「フォルダ追加」からフォルダを選んでください"
            } else {
                "再生できる曲が見つかりませんでした"
            }
            textLocalMessage.visibility = View.VISIBLE
        } else if (displayedCount == 0) {
            textLocalMessage.text = when {
                localSearchQuery.isNotBlank() ->
                    "「${localSearchQuery}」に一致する${if (isTrackPage) "曲" else "項目"}が見つかりません"
                isTrackPage -> "表示できる曲がありません"
                else -> "表示できる項目がありません"
            }
            textLocalMessage.visibility = View.VISIBLE
        } else {
            textLocalMessage.visibility = View.GONE
        }
    }

    private fun selectLocalTab(tab: LocalTab) {
        localTab = tab
        localGroupKey = null
        refreshLocalBrowser()
    }

    // 一覧のタップ振り分け: グループ一覧ならドリルダウン、曲リストなら
    // 表示中リストをキューとしてその曲から再生する。
    private fun onLocalListItemClicked(position: Int) {
        // 再生ページ表示中は一覧が見えていないため到達しないが、保険として弾く
        if (localTab == LocalTab.NOW_PLAYING) return
        if (localGroupKey == null && localTab != LocalTab.ALL) {
            val group = (localTrackList.adapter as? LocalGroupAdapter)
                ?.getItem(position) ?: return
            localGroupKey = group.key
            refreshLocalBrowser()
            return
        }
        val tracks = localDisplayedTracks
        if (position !in tracks.indices) return
        localPlayer.setQueue(tracks)
        localPlayer.playAt(position)
    }

    private fun updateLocalTabStyles() {
        if (!::tabLocalAll.isInitialized) return
        val isDark = isDarkThemeActive()
        val colorSelected = Color.parseColor(if (isDark) "#E5A93C" else "#D49B28")
        val colorNormal = Color.parseColor(if (isDark) "#888888" else "#636366")
        val tabs = listOf(
            tabLocalNowPlaying to LocalTab.NOW_PLAYING,
            tabLocalAll to LocalTab.ALL,
            tabLocalAlbum to LocalTab.ALBUM,
            tabLocalArtist to LocalTab.ARTIST,
            tabLocalFolder to LocalTab.FOLDER
        )
        for ((view, tab) in tabs) {
            val selected = tab == localTab
            view.setTextColor(if (selected) colorSelected else colorNormal)
            view.paint.isFakeBoldText = selected
        }
        btnLocalSort.text = localSortLabel(appPrefs.localSortMode)
    }

    private fun localSortLabel(sortMode: Int): String {
        return when (sortMode) {
            AppPreferences.LOCAL_SORT_ARTIST -> "アーティスト順"
            AppPreferences.LOCAL_SORT_ALBUM -> "アルバム順"
            else -> "タイトル順"
        }
    }

    private fun showLocalSortDialog() {
        val labels = arrayOf<CharSequence>("タイトル順", "アーティスト順", "アルバム順")
        AlertDialog.Builder(this)
            .setTitle("並び替え（全曲）")
            .setSingleChoiceItems(labels, appPrefs.localSortMode) { dialog, which ->
                appPrefs.localSortMode = which
                dialog.dismiss()
                refreshLocalBrowser()
            }
            .setNegativeButton("キャンセル", null)
            .show()
    }

    // 検索フィルタ: タイトル / アーティスト / アルバム（タグが無い曲でも
    // 拾えるようファイル名も対象）への部分一致（大文字小文字は不問）。
    private fun localTrackMatchesQuery(track: LibraryRepository.Track, query: String): Boolean {
        if (query.isBlank()) return true
        val q = query.lowercase(Locale.ROOT)
        return track.title.lowercase(Locale.ROOT).contains(q) ||
            track.artist.lowercase(Locale.ROOT).contains(q) ||
            track.album.lowercase(Locale.ROOT).contains(q) ||
            track.displayName.lowercase(Locale.ROOT).contains(q)
    }

    private fun sortLocalTracks(
        tracks: List<LibraryRepository.Track>,
        sortMode: Int
    ): List<LibraryRepository.Track> {
        return when (sortMode) {
            AppPreferences.LOCAL_SORT_ARTIST -> tracks.sortedWith(
                compareBy<LibraryRepository.Track> { it.artist.isBlank() }
                    .thenBy { it.artist.lowercase(Locale.ROOT) }
                    .thenBy { it.album.lowercase(Locale.ROOT) }
                    .thenBy { it.title.lowercase(Locale.ROOT) }
                    .thenBy { it.displayName.lowercase(Locale.ROOT) }
            )
            AppPreferences.LOCAL_SORT_ALBUM -> tracks.sortedWith(
                compareBy<LibraryRepository.Track> { it.album.isBlank() }
                    .thenBy { it.album.lowercase(Locale.ROOT) }
                    .thenBy { it.title.lowercase(Locale.ROOT) }
                    .thenBy { it.displayName.lowercase(Locale.ROOT) }
            )
            else -> tracks.sortedWith(
                compareBy<LibraryRepository.Track> { it.title.lowercase(Locale.ROOT) }
                    .thenBy { it.artist.lowercase(Locale.ROOT) }
                    .thenBy { it.displayName.lowercase(Locale.ROOT) }
            )
        }
    }

    // グループ一覧の導出（タブ別）。検索中は、グループ名に一致するか、
    // グループ内に検索に一致する曲を含むグループを残す（ドリルダウン先で
    // その曲がフィルタ表示される）。
    private fun localGroupsFor(tab: LocalTab, query: String): List<LocalGroup> {
        val grouped = LinkedHashMap<String, MutableList<LibraryRepository.Track>>()
        for (track in localTracks) {
            grouped.getOrPut(localGroupKeyOf(tab, track)) { mutableListOf() } += track
        }
        val q = query.lowercase(Locale.ROOT)
        return grouped.map { (key, tracks) ->
            LocalGroup(
                key = key,
                label = localGroupLabel(tab, key),
                tracks = tracks,
                uniformArtist = tracks.map { it.artist }.distinct().singleOrNull()
                    ?.takeIf { it.isNotBlank() }
            )
        }.filter { group ->
            q.isBlank() ||
                group.label.lowercase(Locale.ROOT).contains(q) ||
                group.tracks.any { localTrackMatchesQuery(it, query) }
        }.sortedWith(
            compareBy<LocalGroup> { it.label.lowercase(Locale.ROOT) }
        )
    }

    private fun localGroupKeyOf(tab: LocalTab, track: LibraryRepository.Track): String {
        return when (tab) {
            LocalTab.ALBUM -> track.album.ifBlank { unknownAlbumLabel }
            LocalTab.ARTIST -> track.artist.ifBlank { unknownArtistLabel }
            LocalTab.FOLDER -> localFolderKeyOf(track)
            LocalTab.ALL, LocalTab.NOW_PLAYING -> ""
        }
    }

    private fun localGroupLabel(tab: LocalTab, key: String): String {
        return if (tab == LocalTab.FOLDER) localFolderLabel(key) else key
    }

    // フォルダの同一性キー: 実ファイルキー（"ボリューム:相対パス"）の
    // ディレクトリ部分。fileKey が取れていない曲は「その他のフォルダ」
    // に畳む。ボリューム直下のファイルは "ボリューム:" をキーにする。
    private fun localFolderKeyOf(track: LibraryRepository.Track): String {
        val fileKey = track.fileKey ?: return unknownFolderLabel
        val volume = fileKey.substringBefore(':', "")
        val path = fileKey.substringAfter(':', "")
        if (volume.isBlank() || path.isBlank()) return unknownFolderLabel
        val dir = path.substringBeforeLast('/', "")
        return if (dir.isBlank()) "$volume:" else "$volume:$dir"
    }

    private fun localFolderLabel(key: String): String {
        if (key == unknownFolderLabel) return unknownFolderLabel
        val relative = key.substringAfter(':', "")
        return relative.ifBlank { "ルート" }
    }

    private fun requestAudioPermission() {
        requestAudioPermissionLauncher.launch(Manifest.permission.READ_MEDIA_AUDIO)
    }

    private val requestAudioPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (currentPlaybackMode != PlaybackMode.LOCAL) return@registerForActivityResult
            if (granted) {
                reloadLocalLibrary()
            } else {
                textLocalMessage.text = "アクセスが許可されませんでした。「フォルダ追加」からフォルダを選ぶ方法でも曲を追加できます"
                textLocalMessage.visibility = View.VISIBLE
            }
        }

    // ★ SAF フォルダ追加: 永続化許可を取って prefs へ保存し、ライブラリを再読込する。
    //   取得範囲の管理（追加/削除）は設定画面にも同じ導線がある（prefs を共有）。
    private val safFolderPickerLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri == null) return@registerForActivityResult
            try {
                contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
                appPrefs.safFolderUris = appPrefs.safFolderUris + uri.toString()
                Toast.makeText(this, "フォルダを追加しました", Toast.LENGTH_SHORT).show()
                if (currentPlaybackMode == PlaybackMode.LOCAL) {
                    reloadLocalLibrary()
                }
            } catch (e: Exception) {
                Log.w("MainActivity", "SAF folder add failed", e)
                Toast.makeText(this, "フォルダを追加できませんでした", Toast.LENGTH_SHORT).show()
            }
        }

    private fun applyPlaybackModeVisibility() {
        val isLocal = currentPlaybackMode == PlaybackMode.LOCAL
        geckoView.visibility = if (isLocal) View.GONE else View.VISIBLE
        localPlaceholder.visibility = if (isLocal) View.VISIBLE else View.GONE
    }

    private fun updateModeSwitchUi() {
        textModeSwitch.text = when (currentPlaybackMode) {
            PlaybackMode.YT_MUSIC -> "YT MUSIC"
            PlaybackMode.LOCAL -> "LOCAL"
        }
    }

    // ★ 設定画面を開く直前の広告カット設定。戻ったときに差分があれば
    //   Gecko へ反映するために保持する（settingsLauncher 参照）。
    private var adBlockEnabledBeforeSettings: Boolean? = null

    private fun openSettings() {
        adBlockEnabledBeforeSettings = appPrefs.isAdBlockEnabled
        settingsLauncher.launch(Intent(this, SettingsActivity::class.java))
    }

    // ★ コンテンツプロセスのクラッシュ/キル検知（ContentDelegate）への対応。
    //   発火時点でセッションは GeckoView 側で閉じられているため、再生意思がある場合に
    //   限り既存の再生成経路で即時復旧を試みる。再生成後の play フォローアップは
    //   ウォッチドッグ側と同じ checkRecreateFollowUp が担う（sessionRecreatePendingTime）。
    //   クラッシュが短時間に繰り返す場合の暴走防止に専用クールダウン（60 秒）を設け、
    //   ウォッチドッグ側の再生成直後にも発火しないよう lastSessionRecreateTime を共有する。
    private fun handleContentProcessGone(kind: String) {
        val now = System.currentTimeMillis()
        Log.e("MainActivity", "★ Content process gone ($kind), userWantsPlayback=$userWantsPlayback")
        if (!userWantsPlayback) {
            Log.w("MainActivity", "★ Session recreate skipped after $kind (user pause intent)")
            return
        }
        if (now - lastCrashRecreateTime < CRASH_RECREATE_COOLDOWN_MS ||
            now - lastSessionRecreateTime < CRASH_RECREATE_COOLDOWN_MS
        ) {
            Log.w("MainActivity", "★ Session recreate skipped after $kind (crash cooldown)")
            return
        }
        lastCrashRecreateTime = now
        lastSessionRecreateTime = now
        Log.w("MainActivity", "★ Recreating Gecko session after content process $kind")
        playbackService?.resetBuffer("session")
        if (geckoController.recreateSession()) {
            sessionRecreatePendingTime = now
        } else {
            Log.w("MainActivity", "★ Session recreate failed after content process $kind")
        }
    }

    // ★ デフォルトネットワークの切替検知を登録する。コールバックは uiHandler の
    //   スレッド（メインスレッド）で呼ばれるようハンドラ付きで登録する。
    //   registerDefaultNetworkCallback は登録時点でデフォルトネットワークが存在すると
    //   onAvailable を即時にも呼ぶため、その初回だけは「切替」と誤認しないよう
    //   フラグで見分ける（起動直後に不要な nudge を送らない）。
    private fun registerNetworkCallback() {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        connectivityManager = cm
        networkInitialAvailablePending = cm.activeNetwork != null
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                val und = playbackService?.bufferUnderrunCount?.get() ?: -1L
                Log.i(
                    "MainActivity",
                    "★ Network available (transport=${networkTransportName(network)}, und=$und)"
                )
                if (networkInitialAvailablePending) {
                    networkInitialAvailablePending = false
                    return
                }
                // 切替時は onLost と対で来ることが多いので、デバウンスで1回にまとめる
                uiHandler.removeCallbacks(networkNudgeRunnable)
                uiHandler.postDelayed(networkNudgeRunnable, NETWORK_NUDGE_DEBOUNCE_MS)
            }

            override fun onLost(network: Network) {
                val und = playbackService?.bufferUnderrunCount?.get() ?: -1L
                Log.i("MainActivity", "★ Network lost (und=$und)")
            }
        }
        networkCallback = callback
        try {
            cm.registerDefaultNetworkCallback(callback, uiHandler)
        } catch (e: Exception) {
            Log.w("MainActivity", "Network callback registration failed", e)
        }
    }

    private fun networkTransportName(network: Network): String {
        val caps = try {
            connectivityManager?.getNetworkCapabilities(network)
        } catch (e: Exception) {
            null
        } ?: return "unknown"
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WIFI"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "CELLULAR"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ETHERNET"
            else -> "other"
        }
    }

    // ★ 回線の再確立を検知した後の先回り nudge: 再生意思があり実際に再生中の場合に
    //   限り、resume_audio を 1 回送ってページ側の復帰（再アタッチ + AudioContext の
    //   resume）を促す。ウォッチドッグの 3 秒検知を待たずにストールを短くする狙いで、
    //   resume_audio 自体は既存の冪等な復旧コマンドのため空振りしても害はない。
    //   送信条件・段階エスカレーションなどウォッチドッグ側の仕様には触れない。
    private fun nudgePlaybackAfterNetworkChange() {
        val now = System.currentTimeMillis()
        if (now - lastNetworkNudgeTime < NETWORK_NUDGE_MIN_INTERVAL_MS) return
        if (currentPlaybackMode != PlaybackMode.YT_MUSIC) return
        if (!userWantsPlayback || !isPlayingState) return
        lastNetworkNudgeTime = now
        val und = playbackService?.bufferUnderrunCount?.get() ?: -1L
        Log.i("MainActivity", "★ Network changed, sending resume_audio nudge (und=$und)")
        geckoController.sendCommand("resume_audio")
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

        // resume_audio は Gecko 側の復帰コマンドなので YT モード時のみ送る
        if (activeOutputDevice != null && isPlayingState &&
            currentPlaybackMode == PlaybackMode.YT_MUSIC
        ) {
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

    // ★ 設定画面（SettingsActivity）からの戻り: 永続化された値を正として
    //   同期する（設定画面は永続化までを担当、実際の反映はここで一元化する）。
    //   - テーマ: 変わっていれば applyThemeUi で表示へ適用する。
    //   - 広告カット: 開く前の値と変わっていれば Gecko へ送信する（接続中
    //     セッションへの即時反映。未接続時は次回接続時に
    //     GeckoSessionController が prefs の現在値を送る）。
    //   - 再生モード: 切替（再生停止＋flush＋表示切替）を実行する。
    //   - システムモニター: 表示側（updateStatus / UI ループ）が prefs を
    //     都度参照するため、ここでの処理は不要。
    //   モードが変わっていない Local 表示中は、取得範囲（MediaStore
    //   ON/OFF・SAF フォルダ）の変更を一覧へ反映するため再読込する
    //   （仕様書 §6.1: 取得範囲を変えたら再スキャンして一覧を更新する）。
    private val settingsLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { _ ->
            val savedTheme = appPrefs.uiThemeMode
            if (savedTheme != currentThemeMode) {
                applyThemeUi(savedTheme)
            }
            val beforeAdBlock = adBlockEnabledBeforeSettings
            if (beforeAdBlock != null && appPrefs.isAdBlockEnabled != beforeAdBlock) {
                geckoController.sendAdBlock(appPrefs.isAdBlockEnabled)
            }
            adBlockEnabledBeforeSettings = null
            val savedMode = appPrefs.playbackMode
            val modeChanged = savedMode != currentPlaybackMode
            if (modeChanged) {
                switchPlaybackMode(savedMode)
            }
            if (!modeChanged && currentPlaybackMode == PlaybackMode.LOCAL) {
                reloadLocalLibrary()
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
                // ★ 送り先は現在のモードの供給係（仕様書 §8）。Local モードで
                //   Gecko へ送ると、隠れたページが裏で再生を始める事故になる。
                when (currentPlaybackMode) {
                    PlaybackMode.YT_MUSIC -> geckoController.sendCommand(cmd)
                    PlaybackMode.LOCAL -> localPlayer.handleCommand(cmd)
                }
            }
            playbackService?.onSeekListener = { pos ->
                when (currentPlaybackMode) {
                    PlaybackMode.YT_MUSIC -> geckoController.sendSeek(pos)
                    PlaybackMode.LOCAL -> localPlayer.seekTo(pos)
                }
            }
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

        geckoView = findViewById(R.id.geckoview)
        localPlaceholder = findViewById(R.id.localPlaceholder)
        textLocalTitle = findViewById(R.id.textLocalTitle)
        textLocalMessage = findViewById(R.id.textLocalMessage)
        textModeSwitch = findViewById(R.id.textModeSwitch)
        localTrackList = findViewById(R.id.localTrackList)
        btnLocalAddFolder = findViewById(R.id.btnLocalAddFolder)
        btnLocalRescan = findViewById(R.id.btnLocalRescan)
        localScanProgress = findViewById(R.id.localScanProgress)
        progressLocalScan = findViewById(R.id.progressLocalScan)
        textLocalScanStatus = findViewById(R.id.textLocalScanStatus)
        textLocalScanCurrent = findViewById(R.id.textLocalScanCurrent)
        localScanUpdating = findViewById(R.id.localScanUpdating)
        textLocalUpdating = findViewById(R.id.textLocalUpdating)
        tabLocalNowPlaying = findViewById(R.id.tabLocalNowPlaying)
        tabLocalAll = findViewById(R.id.tabLocalAll)
        tabLocalAlbum = findViewById(R.id.tabLocalAlbum)
        tabLocalArtist = findViewById(R.id.tabLocalArtist)
        tabLocalFolder = findViewById(R.id.tabLocalFolder)
        editLocalSearch = findViewById(R.id.editLocalSearch)
        btnLocalSort = findViewById(R.id.btnLocalSort)
        localGroupHeader = findViewById(R.id.localGroupHeader)
        btnLocalBack = findViewById(R.id.btnLocalBack)
        textLocalGroupTitle = findViewById(R.id.textLocalGroupTitle)
        localBrowserPage = findViewById(R.id.localBrowserPage)
        localNowPlaying = findViewById(R.id.localNowPlaying)
        localNpContent = findViewById(R.id.localNpContent)
        textNpEmpty = findViewById(R.id.textNpEmpty)
        textNpQueuePosition = findViewById(R.id.textNpQueuePosition)
        imgNpArtwork = findViewById(R.id.imgNpArtwork)
        textNpTitle = findViewById(R.id.textNpTitle)
        textNpArtist = findViewById(R.id.textNpArtist)
        textNpAlbum = findViewById(R.id.textNpAlbum)
        textNpFormat = findViewById(R.id.textNpFormat)
        seekNpProgress = findViewById(R.id.seekNpProgress)
        textNpCurrentTime = findViewById(R.id.textNpCurrentTime)
        textNpTotalTime = findViewById(R.id.textNpTotalTime)
        btnNpShuffle = findViewById(R.id.btnNpShuffle)
        btnNpPrev = findViewById(R.id.btnNpPrev)
        btnNpPlayPause = findViewById(R.id.btnNpPlayPause)
        btnNpNext = findViewById(R.id.btnNpNext)
        btnNpRepeat = findViewById(R.id.btnNpRepeat)

        libraryRepository = LibraryRepository(this)
        localPlayer = LocalPlayer(this, libraryRepository, localPlayerListener)
        // ★ LocalPlayer のフロー制御用に Service の PCM キュー残量を渡す。
        //   未バインド時は 0（容量 0 = フロー制御なし）として扱われる。
        localPlayer.queueSizeProvider = { playbackService?.pcmQueueSize ?: 0 }
        localPlayer.queueCapacityProvider = { playbackService?.pcmQueueCapacity ?: 0 }

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
        currentPlaybackMode = appPrefs.playbackMode
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
            geckoView = geckoView,
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
                override fun onPlaybackIntent(wantsPlaying: Boolean) {
                    // ページ側の再生意思をアプリ側の記録へ反映する（ページ UI の
                    // 操作を含む）。アプリ経由の記録（noteUserPlaybackIntent）と
                    // 最後の操作が勝つ。強制ポーズでは意思は落ちてこない
                    // （content.js 側の仕様）。
                    userWantsPlayback = wantsPlaying
                }
                override fun onContentProcessGone(kind: String) {
                    handleContentProcessGone(kind)
                }
                override fun isDarkTheme(): Boolean = isDarkThemeActive()
                override fun isAdBlockEnabled(): Boolean = appPrefs.isAdBlockEnabled
            }
        )
        geckoController.init()

        // ★ 起動時のモード反映（仕様書 §3.1: 前回のモードで立ち上げる）。
        //   Gecko セッションはモードによらず生成・保持し、表示だけを切り替える。
        applyPlaybackModeVisibility()
        updateModeSwitchUi()
        if (currentPlaybackMode == PlaybackMode.LOCAL) {
            ensureLocalLibrary()
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // ★ 戻る操作の優先順位: Local のドリルダウンを戻る →
                //   Gecko の履歴を戻る → 終了確認。
                // ★ Local のドリルダウン中は、まずグループ一覧へ戻る
                if (currentPlaybackMode == PlaybackMode.LOCAL && localGroupKey != null) {
                    localGroupKey = null
                    refreshLocalBrowser()
                    return
                }
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
        registerNetworkCallback()
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
            // ★ 設定画面（リスト形式）への導線は単押しに統合する（ユーザー判断）。
            //   UI 設定の項目は設定画面の「画面と UI」カテゴリに実項目として
            //   統合済み（旧 UI 設定ダイアログは廃止）。
            openSettings()
        }
        btnUiSettings.setOnLongClickListener {
            // ★ 長押しは裏設定（開発者プリセット）専用に統一する
            //   （DSP ボタンの長押しと同じ）。
            Toast.makeText(this, "DEVELOPER PRESET TUNER", Toast.LENGTH_SHORT).show()
            showDevPresetsDialog()
            true
        }
        textModeSwitch.setOnClickListener {
            it.hapticTick(appPrefs)
            // ★ 1 タップ即切替（仕様書 §7.2）。確認ダイアログは出さない。
            switchPlaybackMode(
                if (currentPlaybackMode == PlaybackMode.YT_MUSIC) PlaybackMode.LOCAL else PlaybackMode.YT_MUSIC
            )
        }
        localTrackList.setOnItemClickListener { _, view, position, _ ->
            view?.hapticTick(appPrefs)
            // ★ 曲リストなら表示中リスト順のキューで即再生、グループ
            //   一覧ならそのグループへドリルダウン（Phase 2-1）
            onLocalListItemClicked(position)
        }
        tabLocalNowPlaying.setOnClickListener {
            it.hapticTick(appPrefs)
            selectLocalTab(LocalTab.NOW_PLAYING)
        }
        tabLocalAll.setOnClickListener {
            it.hapticTick(appPrefs)
            selectLocalTab(LocalTab.ALL)
        }
        tabLocalAlbum.setOnClickListener {
            it.hapticTick(appPrefs)
            selectLocalTab(LocalTab.ALBUM)
        }
        tabLocalArtist.setOnClickListener {
            it.hapticTick(appPrefs)
            selectLocalTab(LocalTab.ARTIST)
        }
        tabLocalFolder.setOnClickListener {
            it.hapticTick(appPrefs)
            selectLocalTab(LocalTab.FOLDER)
        }
        btnLocalBack.setOnClickListener {
            it.hapticTick(appPrefs)
            localGroupKey = null
            refreshLocalBrowser()
        }
        btnLocalSort.setOnClickListener {
            it.hapticTick(appPrefs)
            showLocalSortDialog()
        }
        editLocalSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val query = s?.toString()?.trim().orEmpty()
                if (query != localSearchQuery) {
                    localSearchQuery = query
                    refreshLocalBrowser()
                }
            }
        })
        btnLocalRescan.setOnClickListener {
            it.hapticTick(appPrefs)
            // ★「再スキャン」は強制フルリフレッシュ（全件再プローブ＋進捗表示）
            if (!FfmpegDecoder.isAvailable) {
                ensureLocalLibrary()
            } else {
                startLocalRefresh(forceFull = true)
            }
        }
        btnLocalAddFolder.setOnClickListener {
            it.hapticTick(appPrefs)
            safFolderPickerLauncher.launch(null)
        }
        // ★ 再生ページ（Now Playing）の操作系
        btnNpPlayPause.setOnClickListener {
            it.hapticTick(appPrefs)
            val cmd = resolvePlayPauseCommand()
            noteUserPlaybackIntent(cmd)
            localPlayer.handleCommand(cmd)
        }
        btnNpNext.setOnClickListener {
            it.hapticTick(appPrefs)
            noteUserPlaybackIntent("next")
            localPlayer.next()
        }
        btnNpPrev.setOnClickListener {
            it.hapticTick(appPrefs)
            noteUserPlaybackIntent("prev")
            localPlayer.prev()
        }
        btnNpShuffle.setOnClickListener {
            it.hapticTick(appPrefs)
            localPlayer.setShuffleEnabled(!localPlayer.shuffleEnabled)
            updateNpToggleStates()
        }
        btnNpRepeat.setOnClickListener {
            it.hapticTick(appPrefs)
            localPlayer.cycleRepeatMode()
            updateNpToggleStates()
        }
        seekNpProgress.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                // ドラッグ中は経過時刻の表示だけ追従させる（シーク自体は
                // 指を離したタイミングで1回だけ発行する）
                if (fromUser && currentDuration > 0) {
                    textNpCurrentTime.text =
                        formatTrackDuration(currentDuration * progress / 1000L)
                }
            }

            override fun onStartTrackingTouch(sb: SeekBar?) {
                isNpSeeking = true
            }

            override fun onStopTrackingTouch(sb: SeekBar?) {
                isNpSeeking = false
                if (currentDuration > 0) {
                    localPlayer.seekTo(currentDuration * (sb?.progress ?: 0) / 1000L)
                }
                updateLocalNowPlayingProgress()
            }
        })

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
            textModeSwitch.setBackgroundResource(R.drawable.bg_btn_icon)
            textModeSwitch.setTextColor(Color.parseColor("#E5A93C"))
            localPlaceholder.setBackgroundColor(Color.parseColor("#000000"))
            textLocalTitle.setTextColor(Color.parseColor("#F0F0F0"))
            textLocalMessage.setTextColor(Color.parseColor("#888888"))
            btnLocalAddFolder.setBackgroundResource(R.drawable.bg_btn_icon)
            btnLocalAddFolder.setTextColor(Color.parseColor("#E5A93C"))
            btnLocalRescan.setBackgroundResource(R.drawable.bg_btn_icon)
            btnLocalRescan.setTextColor(Color.parseColor("#E5A93C"))
            localTrackList.divider = ColorDrawable(Color.parseColor("#1C1C1C"))
            editLocalSearch.setBackgroundResource(R.drawable.bg_local_search)
            editLocalSearch.setTextColor(Color.parseColor("#F0F0F0"))
            editLocalSearch.setHintTextColor(Color.parseColor("#777777"))
            btnLocalSort.setBackgroundResource(R.drawable.bg_btn_icon)
            btnLocalSort.setTextColor(Color.parseColor("#E5A93C"))
            btnLocalBack.setBackgroundResource(R.drawable.bg_btn_icon)
            btnLocalBack.setTextColor(Color.parseColor("#E5A93C"))
            textLocalGroupTitle.setTextColor(Color.parseColor("#F0F0F0"))
            localNowPlaying.setBackgroundColor(Color.parseColor("#000000"))
            textNpEmpty.setTextColor(Color.parseColor("#888888"))
            textNpQueuePosition.setTextColor(Color.parseColor("#888888"))
            textNpTitle.setTextColor(Color.parseColor("#F0F0F0"))
            textNpArtist.setTextColor(Color.parseColor("#BBBBBB"))
            textNpAlbum.setTextColor(Color.parseColor("#888888"))
            textNpFormat.setTextColor(Color.parseColor("#888888"))
            textNpCurrentTime.setTextColor(Color.parseColor("#888888"))
            textNpTotalTime.setTextColor(Color.parseColor("#888888"))
            seekNpProgress.progressTintList =
                ColorStateList.valueOf(Color.parseColor("#E5A93C"))
            seekNpProgress.thumbTintList = ColorStateList.valueOf(Color.WHITE)
            btnNpPrev.setBackgroundResource(R.drawable.bg_btn_walkman_circle_small)
            btnNpNext.setBackgroundResource(R.drawable.bg_btn_walkman_circle_small)
            btnNpShuffle.setBackgroundResource(R.drawable.bg_btn_walkman_circle_small)
            btnNpRepeat.setBackgroundResource(R.drawable.bg_btn_walkman_circle_small)
            btnNpPlayPause.setBackgroundResource(R.drawable.bg_btn_walkman_circle_large)
            btnNpPrev.clearColorFilter()
            btnNpNext.clearColorFilter()
            btnNpPlayPause.clearColorFilter()
            updateNpToggleStates()
            updateLocalTabStyles()
            localTrackAdapter?.notifyDataSetChanged()
            localGroupAdapter?.notifyDataSetChanged()
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
            textModeSwitch.setBackgroundResource(R.drawable.bg_btn_icon_light)
            textModeSwitch.setTextColor(Color.parseColor("#D49B28"))
            localPlaceholder.setBackgroundColor(Color.parseColor("#FFFFFF"))
            textLocalTitle.setTextColor(Color.parseColor("#1C1C1E"))
            textLocalMessage.setTextColor(Color.parseColor("#636366"))
            btnLocalAddFolder.setBackgroundResource(R.drawable.bg_btn_icon_light)
            btnLocalAddFolder.setTextColor(Color.parseColor("#D49B28"))
            btnLocalRescan.setBackgroundResource(R.drawable.bg_btn_icon_light)
            btnLocalRescan.setTextColor(Color.parseColor("#D49B28"))
            localTrackList.divider = ColorDrawable(Color.parseColor("#E0E0E5"))
            editLocalSearch.setBackgroundResource(R.drawable.bg_local_search_light)
            editLocalSearch.setTextColor(Color.parseColor("#1C1C1E"))
            editLocalSearch.setHintTextColor(Color.parseColor("#9A9AA0"))
            btnLocalSort.setBackgroundResource(R.drawable.bg_btn_icon_light)
            btnLocalSort.setTextColor(Color.parseColor("#D49B28"))
            btnLocalBack.setBackgroundResource(R.drawable.bg_btn_icon_light)
            btnLocalBack.setTextColor(Color.parseColor("#D49B28"))
            textLocalGroupTitle.setTextColor(Color.parseColor("#1C1C1E"))
            localNowPlaying.setBackgroundColor(Color.parseColor("#FFFFFF"))
            textNpEmpty.setTextColor(Color.parseColor("#636366"))
            textNpQueuePosition.setTextColor(Color.parseColor("#636366"))
            textNpTitle.setTextColor(Color.parseColor("#1C1C1E"))
            textNpArtist.setTextColor(Color.parseColor("#48484A"))
            textNpAlbum.setTextColor(Color.parseColor("#636366"))
            textNpFormat.setTextColor(Color.parseColor("#636366"))
            textNpCurrentTime.setTextColor(Color.parseColor("#636366"))
            textNpTotalTime.setTextColor(Color.parseColor("#636366"))
            seekNpProgress.progressTintList =
                ColorStateList.valueOf(Color.parseColor("#D49B28"))
            seekNpProgress.thumbTintList =
                ColorStateList.valueOf(Color.parseColor("#1C1C1E"))
            btnNpPrev.setBackgroundResource(R.drawable.bg_btn_walkman_circle_small_light)
            btnNpNext.setBackgroundResource(R.drawable.bg_btn_walkman_circle_small_light)
            btnNpShuffle.setBackgroundResource(R.drawable.bg_btn_walkman_circle_small_light)
            btnNpRepeat.setBackgroundResource(R.drawable.bg_btn_walkman_circle_small_light)
            btnNpPlayPause.setBackgroundResource(R.drawable.bg_btn_walkman_circle_large_light)
            btnNpPrev.setColorFilter(Color.parseColor("#1C1C1E"))
            btnNpNext.setColorFilter(Color.parseColor("#1C1C1E"))
            btnNpPlayPause.setColorFilter(Color.parseColor("#1C1C1E"))
            updateNpToggleStates()
            updateLocalTabStyles()
            localTrackAdapter?.notifyDataSetChanged()
            localGroupAdapter?.notifyDataSetChanged()
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

    // ── Local 再生画面（「再生」タブの Now Playing ページ） ──

    // ★ 再生ページの曲情報を LocalPlayer の現在曲へ同期する。
    //   曲の変化・再生状態の変化・メタデータ（アートワーク到着）の
    //   たびに localPlayerListener から呼ばれる。ページ自体の
    //   表示 / 非表示は選択中タブで決まる（updateLocalPageVisibility）。
    private fun updateLocalPlayerChrome() {
        if (!::localPlayer.isInitialized || !::localNowPlaying.isInitialized) return
        updateLocalNowPlaying(localPlayer.currentTrack)
    }

    // 再生画面の曲情報ブロックを現在曲で描き直す（シーク位置は
    // updateLocalNowPlayingProgress が別途担う）。現在曲が無ければ
    // コンテンツを隠して空状態の表示に切り替える。
    private fun updateLocalNowPlaying(track: LibraryRepository.Track?) {
        if (track == null) {
            localNpContent.visibility = View.GONE
            textNpEmpty.visibility = View.VISIBLE
            return
        }
        localNpContent.visibility = View.VISIBLE
        textNpEmpty.visibility = View.GONE
        textNpTitle.text = track.title
        textNpArtist.text = track.artist
        textNpArtist.visibility = if (track.artist.isBlank()) View.GONE else View.VISIBLE
        textNpAlbum.text = track.album
        textNpAlbum.visibility = if (track.album.isBlank()) View.GONE else View.VISIBLE
        textNpFormat.text = buildLocalFormatLabel(track)
        val queueSize = localPlayer.queueSize()
        val index = localPlayer.currentIndex()
        textNpQueuePosition.text =
            if (queueSize > 0 && index in 0 until queueSize) "${index + 1} / $queueSize" else ""
        val artwork = currentArtworkBitmap
        if (artwork != null) {
            imgNpArtwork.setImageBitmap(artwork)
        } else {
            imgNpArtwork.setImageResource(R.drawable.ic_local_artwork_placeholder)
        }
        btnNpPlayPause.setImageResource(
            if (localPlayer.isPlaying) R.drawable.ic_pause else R.drawable.ic_play
        )
        updateNpToggleStates()
    }

    // フォーマット情報の小行: コーデック / サンプリングレート /
    // ビット深度 / ビットレート。デコーダの確定値（LocalPlayer の
    // currentInfo）を優先し、無ければライブラリのタグ値で補う。
    private fun buildLocalFormatLabel(track: LibraryRepository.Track): String {
        val info = localPlayer.currentInfo
        val codec = (info?.codecName ?: track.codecName).uppercase()
        val rate = info?.sampleRate?.takeIf { it > 0 } ?: track.sampleRate
        val bits = info?.bitDepth?.takeIf { it > 0 } ?: track.bitDepth
        val bitRate = info?.bitRate ?: 0L
        val parts = mutableListOf<String>()
        if (codec.isNotBlank()) parts += codec
        if (rate > 0) parts += String.format(Locale.US, "%.1f kHz", rate / 1000.0)
        if (bits > 0) parts += "$bits bit"
        if (bitRate > 0) parts += "${bitRate / 1000} kbps"
        if (track.isDsd || info?.isDsd == true) {
            parts += "DSD → PCM ${appPrefs.dsdPcmRateHz / 1000.0} kHz"
        }
        return parts.joinToString(" • ")
    }

    // シャッフル / リピートトグルの見た目を LocalPlayer（= prefs）の
    // 状態へ同期する。有効な側はアクセント色、無効な側はグレーで示す。
    private fun updateNpToggleStates() {
        if (!::btnNpShuffle.isInitialized || !::localPlayer.isInitialized) return
        val isDark = isDarkThemeActive()
        val colorAccent = Color.parseColor(if (isDark) "#E5A93C" else "#D49B28")
        val colorOff = Color.parseColor(if (isDark) "#777777" else "#9A9AA0")
        btnNpShuffle.setColorFilter(
            if (localPlayer.shuffleEnabled) colorAccent else colorOff
        )
        when (localPlayer.repeatMode) {
            AppPreferences.LOCAL_REPEAT_ALL -> {
                btnNpRepeat.setImageResource(R.drawable.ic_repeat)
                btnNpRepeat.setColorFilter(colorAccent)
            }
            AppPreferences.LOCAL_REPEAT_ONE -> {
                btnNpRepeat.setImageResource(R.drawable.ic_repeat_one)
                btnNpRepeat.setColorFilter(colorAccent)
            }
            else -> {
                btnNpRepeat.setImageResource(R.drawable.ic_repeat)
                btnNpRepeat.setColorFilter(colorOff)
            }
        }
    }

    // 再生画面のシークバーと時刻表示を現在の再生位置へ同期する。
    // 呼び出しは Local の進捗ポーリング（250ms）と LocalPlayer の
    // onProgress からのみ。ドラッグ中は表示を上書きしない。
    private fun updateLocalNowPlayingProgress() {
        if (localTab != LocalTab.NOW_PLAYING || isNpSeeking) return
        seekNpProgress.progress = if (currentDuration > 0) {
            ((currentPosition.toDouble() / currentDuration.toDouble()) * 1000)
                .toInt().coerceIn(0, 1000)
        } else {
            0
        }
        textNpCurrentTime.text = formatTrackDuration(currentPosition)
        textNpTotalTime.text = formatTrackDuration(currentDuration)
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
                when (currentPlaybackMode) {
                    PlaybackMode.YT_MUSIC -> geckoController.sendCommand(resolved)
                    PlaybackMode.LOCAL -> localPlayer.handleCommand(resolved)
                }
            },
            onSeekTo = { pos ->
                when (currentPlaybackMode) {
                    PlaybackMode.YT_MUSIC -> geckoController.sendSeek(pos)
                    PlaybackMode.LOCAL -> localPlayer.seekTo(pos)
                }
            },
            onDismiss = {
                activePlayerDialog = null
                activeDspDialog = null
            }
        )
        activePlayerDialog = dspDialog
        activeDspDialog = dspDialog
        dspDialog.show()
    }

    private fun showDevPresetsDialog() {
        DevPresetsDialog(this).show()
    }

    // ★ Local 全曲リストのアダプタ。行はコードで組み立て、テーマ色は
    //   SettingsActivity と同じパレットを使う。再生中の行はアクセント色で示す。
    private inner class LocalTrackAdapter(
        private val tracks: List<LibraryRepository.Track>
    ) : BaseAdapter() {

        override fun getCount(): Int = tracks.size
        override fun getItem(position: Int): LibraryRepository.Track = tracks[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val track = tracks[position]
            val isDark = isDarkThemeActive()
            val colorTitle = Color.parseColor(if (isDark) "#F0F0F0" else "#1C1C1E")
            val colorValue = Color.parseColor(if (isDark) "#888888" else "#636366")
            val colorAccent = Color.parseColor(if (isDark) "#E5A93C" else "#D49B28")
            // ★ 再生中ハイライトは位置ではなく曲そのもの（URI）で判定する。
            //   表示リストとキューの並びが一致しない瞬間（再スキャン直後等）
            //   でも別の曲を光らせないため。
            val isCurrent = currentPlaybackMode == PlaybackMode.LOCAL &&
                ::localPlayer.isInitialized &&
                localPlayer.currentTrack?.uri == track.uri

            val root = (convertView as? LinearLayout) ?: buildRow()
            val thumbView = root.getChildAt(0) as ImageView
            val titleView = root.getChildAt(1).let { (it as LinearLayout).getChildAt(0) as TextView }
            val subView = root.getChildAt(1).let { (it as LinearLayout).getChildAt(1) as TextView }
            val durationView = root.getChildAt(2) as TextView

            titleView.text = track.title
            titleView.setTextColor(if (isCurrent) colorAccent else colorTitle)
            val subParts = mutableListOf<String>()
            if (track.artist.isNotBlank()) subParts += track.artist
            if (track.album.isNotBlank()) subParts += track.album
            if (subParts.isEmpty()) subParts += track.displayName
            if (track.codecName.isNotBlank()) {
                subParts += if (track.sampleRate > 0) {
                    "${track.codecName.uppercase()} ${(track.sampleRate / 1000.0)}kHz"
                } else {
                    track.codecName.uppercase()
                }
            }
            subView.text = subParts.joinToString(" • ")
            subView.setTextColor(colorValue)
            durationView.text = formatTrackDuration(track.durationMs)
            durationView.setTextColor(colorValue)
            bindThumbnail(thumbView, track, isDark)
            return root
        }

        // ★ サムネイルの遅延読み込み（Phase 2-1）。
        //   メモリキャッシュにあれば即表示。無ければプレースホルダを出し、
        //   リポジトリ経由で非同期に取得する（MediaStore のアルバムアート
        //   → FFmpeg 抽出の順）。行の使い回しで別曲の絵が混ざらないよう
        //   ImageView の tag に URI を持たせ、一致したときだけ差し替える。
        private fun bindThumbnail(thumbView: ImageView, track: LibraryRepository.Track, isDark: Boolean) {
            val cacheKey = track.uri.toString()
            thumbView.setBackgroundColor(Color.parseColor(if (isDark) "#1C1C1C" else "#E9E9EC"))
            thumbView.tag = cacheKey
            val cached = localThumbCache.get(cacheKey)
            if (cached != null) {
                thumbView.setImageBitmap(cached)
                return
            }
            thumbView.setImageResource(R.drawable.ic_local_artwork_placeholder)
            if (!::libraryRepository.isInitialized) return
            libraryRepository.loadThumbnail(track) { bitmap ->
                if (bitmap == null) return@loadThumbnail
                localThumbCache.put(cacheKey, bitmap)
                runOnUiThread {
                    if (thumbView.tag == cacheKey) thumbView.setImageBitmap(bitmap)
                }
            }
        }

        private fun buildRow(): LinearLayout {
            val density = resources.displayMetrics.density
            fun dp(v: Int): Int = (v * density).toInt()
            val root = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(dp(4), dp(7), dp(4), dp(7))
                layoutParams = android.widget.AbsListView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }
            val thumbView = ImageView(this@MainActivity).apply {
                layoutParams = LinearLayout.LayoutParams(dp(44), dp(44)).apply {
                    marginEnd = dp(10)
                }
                scaleType = ImageView.ScaleType.CENTER_CROP
            }
            val texts = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            val titleView = TextView(this@MainActivity).apply {
                textSize = 14.5f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            }
            val subView = TextView(this@MainActivity).apply {
                textSize = 11.5f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(0, dp(2), 0, 0)
            }
            texts.addView(titleView)
            texts.addView(subView)
            val durationView = TextView(this@MainActivity).apply {
                textSize = 11.5f
                setPadding(dp(10), 0, 0, 0)
            }
            root.addView(thumbView)
            root.addView(texts)
            root.addView(durationView)
            return root
        }
    }

    // ★ グループ行（アルバム / アーティスト / フォルダの一覧用）。
    //   行レイアウトは曲行と同じ作りで、右端は長さの代わりに遷移マーク。
    private inner class LocalGroupAdapter(
        private val groups: List<LocalGroup>
    ) : BaseAdapter() {

        override fun getCount(): Int = groups.size
        override fun getItem(position: Int): LocalGroup = groups[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val group = groups[position]
            val isDark = isDarkThemeActive()
            val colorTitle = Color.parseColor(if (isDark) "#F0F0F0" else "#1C1C1E")
            val colorValue = Color.parseColor(if (isDark) "#888888" else "#636366")

            val root = (convertView as? LinearLayout) ?: buildRow()
            val nameView = root.getChildAt(0).let { (it as LinearLayout).getChildAt(0) as TextView }
            val subView = root.getChildAt(0).let { (it as LinearLayout).getChildAt(1) as TextView }
            val chevronView = root.getChildAt(1) as TextView

            nameView.text = group.label
            nameView.setTextColor(colorTitle)
            val subParts = mutableListOf<String>()
            // アルバム一覧では、アーティストが揃っている場合だけ補助表示する
            if (localTab == LocalTab.ALBUM) {
                group.uniformArtist?.let { subParts += it }
            }
            subParts += "${group.tracks.size} 曲"
            subView.text = subParts.joinToString(" • ")
            subView.setTextColor(colorValue)
            chevronView.setTextColor(colorValue)
            return root
        }

        private fun buildRow(): LinearLayout {
            val density = resources.displayMetrics.density
            fun dp(v: Int): Int = (v * density).toInt()
            val root = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(dp(4), dp(9), dp(4), dp(9))
                layoutParams = android.widget.AbsListView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }
            val texts = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            val nameView = TextView(this@MainActivity).apply {
                textSize = 14.5f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            }
            val subView = TextView(this@MainActivity).apply {
                textSize = 11.5f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(0, dp(2), 0, 0)
            }
            texts.addView(nameView)
            texts.addView(subView)
            val chevronView = TextView(this@MainActivity).apply {
                text = "›"
                textSize = 16f
                setPadding(dp(10), 0, dp(2), 0)
            }
            root.addView(texts)
            root.addView(chevronView)
            return root
        }
    }

    private fun formatTrackDuration(durationMs: Long): String {
        if (durationMs <= 0L) return "--:--"
        val totalSeconds = durationMs / 1000
        return String.format(java.util.Locale.US, "%d:%02d", totalSeconds / 60, totalSeconds % 60)
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
            if (currentPlaybackMode == PlaybackMode.YT_MUSIC) {
                geckoController.reload()
            }
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
                        when (currentPlaybackMode) {
                            PlaybackMode.YT_MUSIC -> geckoController.sendCommand("next")
                            PlaybackMode.LOCAL -> localPlayer.next()
                        }
                    } else if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
                        noteUserPlaybackIntent("prev")
                        when (currentPlaybackMode) {
                            PlaybackMode.YT_MUSIC -> geckoController.sendCommand("prev")
                            PlaybackMode.LOCAL -> localPlayer.prev()
                        }
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
            // ★ 切断時のポーズも現在の供給係へ（Local 中は LocalPlayer を止める）
            when (currentPlaybackMode) {
                PlaybackMode.YT_MUSIC -> geckoController.sendCommand("pause")
                PlaybackMode.LOCAL -> localPlayer.pause()
            }
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

    override fun onResume() {
        super.onResume()
        // システム設定で許可が後から与えられた場合など、Local で一覧が空の
        // ままなら再読み込みを試みる
        if (currentPlaybackMode == PlaybackMode.LOCAL &&
            ::libraryRepository.isInitialized &&
            libraryRepository.hasAudioPermission() &&
            localTracks.isEmpty()
        ) {
            reloadLocalLibrary()
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
        uiHandler.removeCallbacks(networkNudgeRunnable)
        networkCallback?.let { callback ->
            try {
                connectivityManager?.unregisterNetworkCallback(callback)
            } catch (e: Exception) { /* 登録に失敗していた場合は何もしない */ }
        }
        networkCallback = null
        if (appWakeLock?.isHeld == true) appWakeLock?.release()
        btCodecTracker.stop()
        if (::localPlayer.isInitialized) localPlayer.release()
        if (::libraryRepository.isInitialized) libraryRepository.shutdown()
        geckoController.onDestroy()
        if (isServiceBound) {
            unbindService(serviceConnection)
            isServiceBound = false
        }
    }
}