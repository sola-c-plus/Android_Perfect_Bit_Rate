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
import android.net.Uri
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
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewPropertyAnimator
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
import kotlin.math.abs

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
    private lateinit var textLocalMessage: TextView
    private lateinit var textModeSwitch: TextView
    private lateinit var localTrackList: ListView
    private lateinit var localScanProgress: View
    private lateinit var progressLocalScan: ProgressBar
    private lateinit var textLocalScanStatus: TextView
    private lateinit var textLocalScanCurrent: TextView
    private lateinit var localScanUpdating: View
    private lateinit var textLocalUpdating: TextView
    // ★ ライブラリブラウザのクローム（Phase 2-1: タブ / 検索 / ソート /
    //   ドリルダウン用ヘッダー）
    private lateinit var tabLocalQueue: TextView
    private lateinit var tabLocalNowPlaying: TextView
    // ★ ライブラリタブ（ライブラリトップページへの切替）。
    //   ラベルとアイコンは固定（LIBRARY）で、選択状態の配色だけを
    //   updateLocalTabStyles が切り替える。
    private lateinit var tabLocalLibrary: TextView
    private lateinit var localSearchRow: View
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
    // ★ キューページ（「キュー」タブ）: LocalPlayer の実効再生順
    //   （シャッフル時はシャッフル後の順序）をそのまま一覧表示する
    //   専用ページ。ブラウザページとは独立させ、検索やドリルダウンの
    //   状態と干渉しないようにする。
    private lateinit var localQueuePage: View
    private lateinit var localQueueList: ListView
    // Local 3ページの親コンテナ。スワイプ検出は dispatchTouchEvent で
    // この範囲内のタッチだけを対象にする（傍受のみ、消費はしない）。
    private lateinit var localPageContainer: View
    private lateinit var textQueueEmpty: TextView
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
    private lateinit var btnNpStar: ImageButton

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
    // ★ スキャン進捗クロームの「表示したいか」。実際の visibility は
    //   選択中タブとの組み合わせで updateLocalChromeVisibility が決める
    //   （「再生」タブ表示中は進捗表示を出さないため、スキャン側は
    //   visibility を直接触らず wanted だけを更新する）。
    private var localScanProgressWanted = false
    private var localScanUpdatingWanted = false

    // ★ ライブラリブラウザの状態（Phase 2-1、下部タブバー）。
    //   localTab: 表示中のタブ（QUEUE = キューページ、NOW_PLAYING =
    //   再生ページ、それ以外はブラウザページ）。localGroupKey:
    //   ドリルダウン中のグループキー（null = 全曲リスト or グループ
    //   一覧を表示中）。localDisplayedTracks: いま曲リストとして
    //   表示している並び（＝ LocalPlayer へ渡しているキューと
    //   一致させる。キューページは LocalPlayer 側の順序表から
    //   直接描くため、ここには入れない）。
    private enum class LocalTab { QUEUE, NOW_PLAYING, LIBRARY_TOP, ALL, ALBUM, ARTIST, PLAYLIST }

    private data class LocalGroup(
        val key: String,
        val label: String,
        val tracks: List<LibraryRepository.Track>,
        // グループ内のアーティストが1種類だけならその名前（アルバム一覧の
        // 補助表示用）。混在・全て空なら null。
        val uniformArtist: String?
    )

    private var localTab = LocalTab.ALL
    // ★ ライブラリスロット（下部タブの LIBRARY）の現在のページ。
    //   ライブラリトップ（LIBRARY_TOP）か、その配下のビュー
    //   （ALL / ALBUM / ARTIST / PLAYLIST）のうち最後に確定選択
    //   したもの。スワイプでライブラリスロットへ移動するときの
    //   移動先に使う（selectLocalTab で確定したときだけ更新
    //   する。ドラッグの覗き見では更新しない）。
    private var localLibraryTab = LocalTab.ALL
    private var localGroupKey: String? = null
    // ★ アーティスト配下のアルバムドリルダウンキー（null = アルバム
    //   一覧を表示中）。アーティスト → アルバム一覧 → アルバム内の
    //   曲、の2段ドリルダウンにするための追加状態。
    private var localArtistAlbumKey: String? = null
    private var localSearchQuery: String = ""
    private var localDisplayedTracks: List<LibraryRepository.Track> = emptyList()
    private var localGroupAdapter: LocalGroupAdapter? = null

    private val unknownAlbumLabel = "不明なアルバム"
    private val unknownArtistLabel = "不明なアーティスト"

    // ★ プレイリスト（フォルダタブを置き換え）。
    //   playlistStore: 永続化の正（別 DB、PlaylistStore 参照）。
    //   localPlaylistDrillId: プレイリストタブでドリルダウン中の
    //   プレイリスト id（null = プレイリスト一覧を表示中）。
    //   localTrackUriSet: 現在のライブラリに実在する曲の URI 集合。
    //   プレイリスト内の「ライブラリから消えた曲」の判定と、
    //   再生キューからの除外に使う（applyLocalTracks で更新）。
    private val playlistStore by lazy { PlaylistStore(applicationContext) }
    private var localPlaylistDrillId: Long? = null
    private var localTrackUriSet: Set<String> = emptySet()

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
    // ★ Local 再生画面専用のアートワーク保持。currentArtworkBitmap は
    //   両モードで共有され、Local 中も保持され続ける Gecko ページ側の
    //   メタデータ（画像取得の非同期完了含む）が書き込むと、Local の
    //   再生画面の再描画で YT のサムネイルが漏れてしまう。再生画面の
    //   描画は LocalPlayer の通知だけで更新されるこちらを正とする。
    private var localArtworkBitmap: Bitmap? = null
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
    private val uiPeakDb = FloatArray(2) { -60f }

    private val uiHandler = Handler(Looper.getMainLooper())
    private val uiUpdateRunnable = object : Runnable {
        override fun run() {
            val now = System.currentTimeMillis()

            // ★ レベルメーターは「今鳴っている位置」に整列したピークで駆動
            //   する。従来は再生ループのチャンク処理に同期した到着駆動
            //   （onPeakListener からの setLevels）で、Local のバースト供給
            //   では配送間隔が空いてメーターが凍結→ジャンプになっていた。
            //   スペクトラム（3ae6aee）と同方式で、この 30ms 周期に Service
            //   から再生ヘッド位置のチャンクのピークを取得して渡す。
            //   メーター側のピークホールド／減衰は従来どおり View が担う。
            val meterAligned = isPlayingState &&
                playbackService?.getPeakAlignedToPlayback(uiPeakDb) == true
            if (meterAligned) {
                walkmanLevelMeter?.setLevels(uiPeakDb[0], uiPeakDb[1])
            }

            if (now - lastPcmTime > 400L || !isPlayingState) {
                peakDbL = -60f
                peakDbR = -60f
                bitActivityMask = 0
                currentQueueSize = 0
                currentLatencyMs = 0
                // 整列取得できている間（書いた音がまだ鳴っている間）は
                // メーターを無音に倒さない。
                if (!meterAligned) {
                    walkmanLevelMeter?.setLevels(-60f, -60f)
                }
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
                // ★ スペクトラムは Service 側で「今鳴っている位置」に整列した
                //   スナップショットから取得する（Local のバースト処理でも
                //   表示が再生ペースで連続的に流れるように）。スナップ
                //   ショットがまだ無い場合（再生開始直後など）は、従来どおり
                //   native の最新値を直接読む。
                val service = playbackService
                if (service == null || !service.getSpectrumAlignedToPlayback(uiSpectrumBands)) {
                    NativeAudioEngine.nativeGetSpectrum(uiSpectrumBands)
                }
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

    private fun resolvePlayPauseCommand(): String {
        // ★ Local モードでは供給係（LocalPlayer）自身の状態を正とする。
        //   共有の isPlayingState は YT ページ側の通知と共用で、Local 中は
        //   保持ページの定期 progress（ポーズ済みのため playing=false、
        //   1 秒周期）に倒されうる。その状態で判定すると、再生中でも
        //   "play" に化けて LocalPlayer.play() が no-op になり（再生中は
        //   即 return）、ポーズが効かなくなる。
        if (currentPlaybackMode == PlaybackMode.LOCAL && ::localPlayer.isInitialized) {
            return if (localPlayer.isPlaying) "pause" else "play"
        }
        return if (isPlayingState && isPcmFlowing()) "pause" else "play"
    }

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
            // ★ アートワーク状態の所有権はモードで分ける: Local 側の
            //   通知は Local モードのときだけ表示状態へ反映する
            //   （YT モードへ切り替えた直後に遅れて届いた通知が、
            //   YT 側の表示を Local の絵で上書きしないように）。
            if (currentPlaybackMode != PlaybackMode.LOCAL) return
            currentTitle = title
            currentArtist = artist
            currentArtworkBitmap = artwork
            localArtworkBitmap = artwork
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
                // ★ キュータブ表示中は、曲の進行に合わせて一覧と
                //   ハイライトを追随させる。
                if (localTab == LocalTab.QUEUE) refreshQueueTab()
            }
        }

        override fun onPlaybackError(message: String) {
            // ★ Local 再生がエラーで止まった理由をユーザーへ見せる
            //   （従来は無言で止まっていた）。
            runOnUiThread {
                Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
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
        // ★ Service 側にも現在の供給元を伝える（ストリーム構成切替中の
        //   pushPcm の扱いが供給元で異なるため。BitPerfectPlaybackService
        //   の isLocalSourceActive 参照）。
        playbackService?.isLocalSourceActive = (newMode == PlaybackMode.LOCAL)
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
            localArtworkBitmap = null
            currentCodec = "LOCAL"
            currentPosition = 0L
            currentDuration = 0L
            playbackService?.updateMetadataWithBitmap(currentTitle, currentArtist, null)
            playbackService?.updateProgress(0L, 0L, false)
            ensureLocalLibrary()
            // ★ Local で保持中の曲（YT へ行く前に再生していた曲）が
            //   ある場合は、その曲の表示を復元する。タイトル /
            //   アーティストは LocalPlayer の現在曲から戻し、
            //   アートワークは再取得して適用する。切替のたびに
            //   currentArtworkBitmap を null へ倒しているため、
            //   再取得しないと再生画面のアートワークが音符
            //   プレースホルダのままになる（LocalPlayer は曲開始
            //   時にしかアートワークを送らない）。
            if (::localPlayer.isInitialized) {
                localPlayer.currentTrack?.let { held ->
                    currentTitle = held.title
                    currentArtist = held.artist
                    playbackService?.updateMetadataWithBitmap(
                        currentTitle, currentArtist, null
                    )
                    localPlayer.refreshCurrentArtwork()
                }
            }
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
            localArtworkBitmap = null
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
            localScanUpdatingWanted = false
            progressLocalScan.isIndeterminate = true
            textLocalScanStatus.text = "ライブラリを検索中…"
            textLocalScanCurrent.text = ""
            localScanProgressWanted = true
        } else {
            localScanProgressWanted = false
            textLocalUpdating.text = "ライブラリを更新中…"
            localScanUpdatingWanted = true
        }
        updateLocalChromeVisibility()
        libraryRepository.refreshLibrary(
            forceFull = effectiveForceFull,
            onProgress = { progress ->
                runOnUiThread { updateLocalScanProgress(progress, foreground) }
            }
        ) { tracks ->
            runOnUiThread {
                localScanRunning = false
                localScanProgressWanted = false
                localScanUpdatingWanted = false
                updateLocalChromeVisibility()
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
    //   実際の表示（タブ / 検索 / ソート / ドリルダウンの適用）は
    //   refreshLocalBrowser に集約する。ライブラリが更新されても
    //   再生中キューには触らない（キューはタップ再生でのみ確定）。
    private fun applyLocalTracks(tracks: List<LibraryRepository.Track>) {
        localTracks = tracks
        localTrackUriSet = tracks.mapTo(HashSet()) { it.uri.toString() }
        localLibraryShown = tracks.isNotEmpty()
        refreshLocalBrowser()
    }

    // ── ライブラリブラウザ（Phase 2-1） ──
    //
    // タブ（全曲 / アルバム / アーティスト / プレイリスト）× 検索フィルタ ×
    // ソートから表示内容を導出する単一の入口。表示が「曲リスト」の間は
    // その並びが LocalPlayer のキューになる（タップ再生・次曲送り・
    // 自動進行がすべて表示順と一致する）。表示が「グループリスト」の
    // 間はキューに触らない。

    // ★ Local コンテンツ領域内のページ出し分け: 「再生」タブなら
    //   再生ページ、「キュー」タブならキューページ、それ以外は
    //   ブラウザページ（検索 / 一覧）を出す。
    //   上部ステータスヘッダー（topInfoPanel）はモード共通で常時
    //   表示される領域のため、ここでは一切触らない。選択中タブは
    //   localTab が唯一の正で、再描画のたびにここから復元される。
    private fun updateLocalPageVisibility() {
        if (!::localNowPlaying.isInitialized) return
        val showNowPlaying = localTab == LocalTab.NOW_PLAYING
        val showQueue = localTab == LocalTab.QUEUE
        localNowPlaying.visibility = if (showNowPlaying) View.VISIBLE else View.GONE
        localQueuePage.visibility = if (showQueue) View.VISIBLE else View.GONE
        localBrowserPage.visibility =
            if (showNowPlaying || showQueue) View.GONE else View.VISIBLE
        if (showNowPlaying) {
            if (::localPlayer.isInitialized) {
                updateLocalNowPlaying(localPlayer.currentTrack)
            }
            // ブラウザ側の空表示メッセージは再生ページでは出さない
            textLocalMessage.visibility = View.GONE
        }
        updateLocalChromeVisibility()
    }

    // ★ ブラウザ側クローム（スキャン進捗）の出し分け。「再生」
    //   タブは WALKMAN の再生画面のように情報を絞るため、進捗
    //   表示は出さない。ブラウザ系タブに戻れば、スキャン側が
    //   保持する wanted の状態どおりに復帰する。スキャン進捗の
    //   visibility はここだけが決める（スキャン側は wanted の
    //   更新のみ）。見出し行（LOCAL MUSIC＋フォルダ追加／再
    //   スキャン）はユーザー指定で廃止したため、ここで扱う
    //   クロームはスキャン進捗だけになった。
    private fun updateLocalChromeVisibility() {
        if (!::localScanProgress.isInitialized) return
        val showBrowserChrome = localTab != LocalTab.NOW_PLAYING
        localScanProgress.visibility =
            if (showBrowserChrome && localScanProgressWanted) View.VISIBLE else View.GONE
        localScanUpdating.visibility =
            if (showBrowserChrome && localScanUpdatingWanted) View.VISIBLE else View.GONE
    }

    private fun refreshLocalBrowser() {
        if (!::localTrackList.isInitialized) return
        // ★ 「再生」タブ表示中はブラウザ側（一覧の導出）には触らず、
        //   ページの出し分けだけを行う。
        if (localTab == LocalTab.NOW_PLAYING) {
            updateLocalPageVisibility()
            updateLocalTabStyles()
            return
        }
        updateLocalPageVisibility()
        // ライブラリトップでは検索行を出さない（WALKMAN の
        // ライブラリートップと同様、検索は各ビューの中で使う）。
        if (::localSearchRow.isInitialized) {
            localSearchRow.visibility =
                if (localTab == LocalTab.LIBRARY_TOP) View.GONE else View.VISIBLE
        }
        if (localTab == LocalTab.PLAYLIST) {
            // プレイリストはライブラリ由来のグループとは導出元が
            // 違う（DB が正）ため、専用経路で描く。
            refreshPlaylistTab()
            updateLocalTabStyles()
            return
        }
        if (localTab == LocalTab.QUEUE) {
            // キューは LocalPlayer の順序表が導出元のため、専用
            // 経路で描く（表示＝キューのため setQueue はしない）。
            refreshQueueTab()
            updateLocalTabStyles()
            return
        }
        if (localTab == LocalTab.LIBRARY_TOP) {
            // ライブラリトップは入口一覧を出す専用経路で描く。
            refreshLibraryTop()
            updateLocalTabStyles()
            return
        }
        val groupKey = localGroupKey
        if (groupKey != null && localTab == LocalTab.ARTIST && localArtistAlbumKey == null) {
            // ★ アーティストのドリルダウン1段目: そのアーティストの
            //   アルバム一覧を出す（先頭行は「全てのアルバムを再生」）。
            //   曲をアルバム混在のまま並べず、アルバム単位に分ける。
            val albums = localAlbumsForArtist(groupKey, localSearchQuery)
            val totalTracks = localTracks.count { artistGroupKeyOf(it) == groupKey }
            localDisplayedTracks = emptyList()
            localTrackList.adapter = LocalArtistAlbumsAdapter(albums, totalTracks)
            updateLocalEmptyMessage(albums.size, isTrackPage = false)
            textLocalGroupTitle.text = groupKey
            localGroupHeader.visibility = View.VISIBLE
        } else if (groupKey != null && localTab != LocalTab.ALL) {
            // ドリルダウン中: 選択中グループの曲一覧（検索フィルタ適用、
            // 並びはタイトル順で固定。グループ内の曲順はソート対象外）。
            // アーティストの2段目ではアルバムでも絞り込む。
            val albumKey = localArtistAlbumKey
            val groupTracks = localTracks
                .filter { localGroupKeyOf(localTab, it) == groupKey }
                .filter {
                    albumKey == null || localTab != LocalTab.ARTIST ||
                        albumKeyOf(it) == albumKey
                }
                .filter { localTrackMatchesQuery(it, localSearchQuery) }
            showLocalTrackPage(sortLocalTracks(groupTracks, AppPreferences.LOCAL_SORT_TITLE))
            textLocalGroupTitle.text =
                if (localTab == LocalTab.ARTIST && albumKey != null) {
                    "$groupKey › $albumKey"
                } else {
                    groupKey
                }
            localGroupHeader.visibility = View.VISIBLE
        } else {
            // ライブラリ配下ビューのトップでは、ライブラリトップへ
            // 戻るためのヘッダー（戻るボタン＋ビュー名）を出す
            // （従来はドリルダウン中だけ出していた）。
            textLocalGroupTitle.text = localLibraryChildTitle(localTab)
            localGroupHeader.visibility = View.VISIBLE
            when (localTab) {
                LocalTab.ALL -> {
                    val filtered = localTracks
                        .filter { localTrackMatchesQuery(it, localSearchQuery) }
                    showLocalTrackPage(sortLocalTracks(filtered, appPrefs.localSortMode))
                }
                LocalTab.ALBUM, LocalTab.ARTIST -> {
                    val groups = localGroupsFor(localTab, localSearchQuery)
                    localDisplayedTracks = emptyList()
                    val adapter = LocalGroupAdapter(groups)
                    localGroupAdapter = adapter
                    localTrackList.adapter = adapter
                    updateLocalEmptyMessage(groups.size, isTrackPage = false)
                }
                LocalTab.PLAYLIST, LocalTab.NOW_PLAYING, LocalTab.QUEUE,
                LocalTab.LIBRARY_TOP -> {
                    // 到達しない分岐（網羅性のため）: PLAYLIST / QUEUE /
                    // LIBRARY_TOP は冒頭の専用経路、NOW_PLAYING は関数
                    // 冒頭の早期 return でそれぞれ処理済みのため、
                    // ここでは何もしない。
                }
            }
        }
        updateLocalTabStyles()
    }

    // ★ プレイリストタブの描画。ドリルダウンしていない間は
    //   「＋ 新規作成」行＋プレイリスト一覧、ドリルダウン中は
    //   そのプレイリストの曲一覧（追加順）を出す。曲一覧では
    //   ライブラリから消えた曲も、追加時点のスナップショットで
    //   行自体は残してグレー表示にする（行を消すと長押しでの
    //   「プレイリストから削除」もできなくなるため）。表示は
    //   専用で、再生キューはタップ時（onLocalListItemClicked）に
    //   実在する曲だけへ絞って確定させる。
    private fun refreshPlaylistTab() {
        val drillId = localPlaylistDrillId
        if (drillId == null) {
            // プレイリスト一覧もライブラリ配下のビューのため、
            // ライブラリトップへ戻るヘッダーを出す。
            textLocalGroupTitle.text = localLibraryChildTitle(LocalTab.PLAYLIST)
            localGroupHeader.visibility = View.VISIBLE
            val query = localSearchQuery.trim().lowercase(Locale.ROOT)
            val playlists = playlistStore.listPlaylists().filter {
                query.isBlank() || it.name.lowercase(Locale.ROOT).contains(query)
            }
            localDisplayedTracks = emptyList()
            localTrackList.adapter = LocalPlaylistAdapter(playlists)
            // 一覧には常に「＋ 新規作成」行があるため、空表示
            // メッセージは出さない。
            textLocalMessage.visibility = View.GONE
            return
        }
        val playlist = playlistStore.getPlaylist(drillId)
        if (playlist == null) {
            // 表示中に削除された場合は一覧へ戻る
            localPlaylistDrillId = null
            refreshPlaylistTab()
            return
        }
        textLocalGroupTitle.text = playlist.name
        localGroupHeader.visibility = View.VISIBLE
        val byUri = localTracks.associateBy { it.uri.toString() }
        val display = playlistStore.items(drillId)
            .map { item -> byUri[item.uri] ?: missingPlaylistTrack(item) }
            .filter { localTrackMatchesQuery(it, localSearchQuery) }
        showLocalTrackPage(display)
        if (display.isEmpty()) {
            textLocalMessage.text = if (localSearchQuery.isNotBlank()) {
                "「${localSearchQuery}」に一致する曲が見つかりません"
            } else {
                "このプレイリストには曲がまだありません"
            }
            textLocalMessage.visibility = View.VISIBLE
        } else {
            // 行が出ている間は、ライブラリ側の空表示メッセージ
            // （ライブラリ未読込時の案内など）を重ねない。
            textLocalMessage.visibility = View.GONE
        }
    }

    // ★ キュータブの描画。LocalPlayer の実効再生順（シャッフル ON
    //   ならシャッフル後の順序、OFF なら元の並び）をそのまま一覧に
    //   する。行の見た目と再生中ハイライトはライブラリの曲行と共通
    //   （LocalTrackAdapter、ハイライトは URI 判定）。表示リスト
    //   自体が再生キューのため、ここでは setQueue しない。キューが
    //   無ければ空状態を出す。開いたときは現在曲の位置へスクロール
    //   しておく。
    private fun refreshQueueTab() {
        if (!::localQueueList.isInitialized) return
        val tracks = if (::localPlayer.isInitialized) {
            localPlayer.effectiveOrderTracks()
        } else {
            emptyList()
        }
        localQueueList.adapter = LocalTrackAdapter(tracks)
        val hasQueue = tracks.isNotEmpty()
        localQueueList.visibility = if (hasQueue) View.VISIBLE else View.GONE
        textQueueEmpty.visibility = if (hasQueue) View.GONE else View.VISIBLE
        if (hasQueue && ::localPlayer.isInitialized) {
            val pos = localPlayer.effectivePosition()
            if (pos in tracks.indices) {
                localQueueList.post { localQueueList.setSelection(pos) }
            }
        }
    }

    // ★ ライブラリ配下ビューの見出し（グループヘッダーの
    //   タイトル）。ドリルダウンしていない各ビューのトップで、
    //   ライブラリトップへ戻る導線とあわせて表示する。
    private fun localLibraryChildTitle(tab: LocalTab): String {
        return when (tab) {
            LocalTab.ALL -> "全曲"
            LocalTab.ALBUM -> "アルバム"
            LocalTab.ARTIST -> "アーティスト"
            LocalTab.PLAYLIST -> "プレイリスト"
            else -> ""
        }
    }

    // ライブラリトップの入口（タップで入るビュー、表示ラベル、
    // 補助表示＝件数、アイコン）。
    private data class LocalLibraryEntry(
        val tab: LocalTab,
        val label: String,
        val subLabel: String,
        val iconRes: Int
    )

    // ★ ライブラリトップの描画。WALKMAN OS のライブラリートップ
    //   のように、TRACKS / ALBUMS / ARTISTS / PLAYLISTS の入口を
    //   一覧で並べる。項目タップで各ビューへ入る（振り分けは
    //   onLocalListItemClicked）。検索行とグループヘッダーは
    //   出さない（呼び出し側とここで揃える）。
    private fun refreshLibraryTop() {
        localGroupHeader.visibility = View.GONE
        localDisplayedTracks = emptyList()
        val entries = listOf(
            LocalLibraryEntry(
                LocalTab.ALL, "TRACKS", "${localTracks.size} 曲",
                R.drawable.ic_tab_all
            ),
            LocalLibraryEntry(
                LocalTab.ALBUM, "ALBUMS",
                "${localGroupsFor(LocalTab.ALBUM, "").size} アルバム",
                R.drawable.ic_tab_album
            ),
            LocalLibraryEntry(
                LocalTab.ARTIST, "ARTISTS",
                "${localGroupsFor(LocalTab.ARTIST, "").size} アーティスト",
                R.drawable.ic_tab_artist
            ),
            LocalLibraryEntry(
                LocalTab.PLAYLIST, "PLAYLISTS",
                "${playlistStore.listPlaylists().size} 件",
                R.drawable.ic_tab_playlist
            )
        )
        localTrackList.adapter = LocalLibraryTopAdapter(entries)
        if (localTracks.isEmpty() && playlistStore.listPlaylists().isEmpty()) {
            // ライブラリが完全に空のときだけ案内を出す（入口自体は
            // 件数 0 で並べ、設定への導線として読めるようにする）。
            updateLocalEmptyMessage(0, isTrackPage = false)
        } else {
            textLocalMessage.visibility = View.GONE
        }
    }

    // ★ ライブラリに実在しないプレイリスト項目の表示用プレース
    //   ホルダ。追加時点のスナップショット（タイトル等）だけを持ち、
    //   再生キューには入らない（タップ時に実在する曲だけへ絞る
    //   onLocalListItemClicked の判定で除外される）。行側は URI が
    //   ライブラリに無いことで判別してグレー表示にする。
    private fun missingPlaylistTrack(item: PlaylistStore.Item): LibraryRepository.Track {
        return LibraryRepository.Track(
            uri = Uri.parse(item.uri),
            displayName = item.title,
            title = item.title.ifBlank { item.uri },
            artist = item.artist,
            album = item.album,
            durationMs = 0L,
            sizeBytes = 0L,
            modifiedMs = 0L,
            sampleRate = 0,
            bitDepth = 0,
            codecName = "",
            isDsd = false,
            source = LibraryRepository.Track.Source.MEDIASTORE,
            fileKey = null
        )
    }

    // 曲リストを表示する（表示専用）。再生キューはここでは一切
    // 触らない: キューが決まるのは、リストで曲をタップして再生する
    // 瞬間（onLocalListItemClicked の setQueue → playAt）だけ。
    // 以前は再描画のたびに「表示中リスト＝再生キュー」として
    // setQueue していたため、タブ切替・検索・ソート・ドリルダウン・
    // 再スキャン完了などの閲覧/更新操作が再生中キューを勝手に
    // 差し替えてしまうバグがあった。
    private fun showLocalTrackPage(tracks: List<LibraryRepository.Track>) {
        localDisplayedTracks = tracks
        val adapter = LocalTrackAdapter(tracks)
        localTrackAdapter = adapter
        localTrackList.adapter = adapter
        updateLocalEmptyMessage(tracks.size, isTrackPage = true)
    }

    private fun updateLocalEmptyMessage(displayedCount: Int, isTrackPage: Boolean) {
        if (localTracks.isEmpty()) {
            textLocalMessage.text = if (!libraryRepository.hasAudioPermission() && appPrefs.safFolderUris.isEmpty()) {
                "曲が見つかりませんでした。音声ファイルへのアクセスを許可するか、設定画面の「ライブラリ取得範囲」からフォルダを追加してください"
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
        // スワイプのスライドが進行中なら先に確定させる（退場中
        // ページの終了処理が、切替後の表示を巻き込まないため）。
        finishLocalPageSlide()
        // 統合ライブラリタブの指すビューは、確定した選択でのみ
        // 更新する（ドラッグの覗き見では更新しない）。
        if (isLocalLibraryTab(tab)) {
            localLibraryTab = tab
        }
        localTab = tab
        localGroupKey = null
        localArtistAlbumKey = null
        localPlaylistDrillId = null
        refreshLocalBrowser()
    }

    // ★ ドリルダウンを1段戻る。戻れたら true。アーティストの
    //   2段目（アルバム内）からはアルバム一覧へ、グループ／
    //   プレイリストのドリルダウンからは一覧へ戻り、ライブラリ
    //   配下ビューのトップからはライブラリトップへ戻る。戻る
    //   ボタン（ヘッダー）と Android の戻る操作で共用する。
    private fun localDrillBack(): Boolean {
        if (localArtistAlbumKey != null) {
            localArtistAlbumKey = null
            refreshLocalBrowser()
            return true
        }
        if (localGroupKey != null || localPlaylistDrillId != null) {
            localGroupKey = null
            localPlaylistDrillId = null
            refreshLocalBrowser()
            return true
        }
        if (isLocalLibraryTab(localTab) && localTab != LocalTab.LIBRARY_TOP) {
            // ライブラリ配下ビューのトップ → ライブラリトップへ。
            selectLocalTab(LocalTab.LIBRARY_TOP)
            return true
        }
        return false
    }

    // ★ ライブラリスロット配下のタブかどうか（ライブラリトップ
    //   自身と、その配下の全ビュー）。
    private fun isLocalLibraryTab(tab: LocalTab): Boolean =
        tab == LocalTab.LIBRARY_TOP || tab == LocalTab.ALL ||
            tab == LocalTab.ALBUM || tab == LocalTab.ARTIST ||
            tab == LocalTab.PLAYLIST

    // ★ 下部タブバーのスロット（左→右）: キュー / 再生 /
    //   ライブラリ の3つ。スワイプでの隣接移動は LocalTab 単位
    //   ではなくこのスロット単位で数える。ライブラリスロットの
    //   実体は localLibraryTab（ライブラリトップ or 最後に確定
    //   選択した配下ビュー）。
    private fun localTabSlot(tab: LocalTab): Int = when (tab) {
        LocalTab.QUEUE -> 0
        LocalTab.NOW_PLAYING -> 1
        LocalTab.LIBRARY_TOP, LocalTab.ALL, LocalTab.ALBUM,
        LocalTab.ARTIST, LocalTab.PLAYLIST -> 2
    }

    private fun localTabForSlot(slot: Int): LocalTab? = when (slot) {
        0 -> LocalTab.QUEUE
        1 -> LocalTab.NOW_PLAYING
        2 -> localLibraryTab
        else -> null
    }

    // スワイプ判定の閾値: 水平フリックとして明確な場合だけタブ切替に
    // 使うための距離（dp）と、水平優位の条件（|dx| が |dy| の何倍か）。
    private val LOCAL_TAB_SWIPE_MIN_DISTANCE_DP = 48f
    private val LOCAL_TAB_SWIPE_MIN_VELOCITY = 120f
    private val LOCAL_TAB_SWIPE_AXIS_RATIO = 2.0f
    // ドラッグ追従を開始する距離、確定に必要な移動割合と速度。
    // 開始までは通常のタップ／縦スクロールとして扱い、開始した
    // 時点からだけページを指の移動量へ追従させる。
    private val LOCAL_TAB_DRAG_START_DISTANCE_DP = 24f
    private val LOCAL_TAB_DRAG_COMMIT_DISTANCE_RATIO = 0.27f
    private val LOCAL_TAB_DRAG_COMMIT_VELOCITY = 500f
    // ジェスチャの鮮度判定: イベントの発生時刻（eventTime）から
    // 処理までにこれ以上空いたイベントは「古い」とみなす。曲タップ
    // 直後は LocalPlayer.startTrackLocked がメインスレッド上で前
    // デコーダの停止待ち（最大2秒）と FFmpeg のオープンを同期実行
    // するため、その間にユーザーが行ったスワイプの MotionEvent が
    // ルーパーに溜まり、カクツキが解消した瞬間に一括配送される。
    // 鮮度で弾くことで「溜まったスワイプが解消後にまとめて発火
    // してページがジャンプする」のを防ぐ。
    private val LOCAL_SWIPE_STALE_EVENT_MS = 250L

    // ★ スワイプでのタブ移動（指の動きに内容が付いてくる向き）:
    //   左スワイプ → 右隣のタブ、右スワイプ → 左隣のタブ（例:
    //   再生タブで左へスワイプすると全曲、右へスワイプすると
    //   キュー）。端のタブではそれ以上移動しない。切替の作法は
    //   タブのタップと同じ selectLocalTab に揃える（ドリルダウン
    //   中なら抜けて対象タブのトップ表示になる）が、スワイプ
    //   起因の切替だけはページのスライドアニメーションを付ける。
    private fun switchLocalTabBySwipe(toNext: Boolean) {
        val index = localTabSlot(localTab)
        val target = if (toNext) index + 1 else index - 1
        val targetTab = localTabForSlot(target) ?: return
        // 進行中のスライドがあれば先に確定させてから現在の
        // ページを取る（アニメーション中のページが両方見えて
        // いる状態で旧ページを取り違えないため）。
        finishLocalPageSlide()
        val oldPage = currentVisibleLocalPage()
        selectLocalTab(targetTab)
        animateLocalPageSlide(oldPage, currentVisibleLocalPage(), toNext)
    }

    // ★ スライド付きのタブ切替（プログラムからの切替用）。
    //   曲タップで再生が始まったときの「再生」タブへの自動切替
    //   で使い、スワイプ切替と同じスライドで遷移させる。方向は
    //   スロットの並びから決める（右隣のスロットへは次方向）。
    //   同じスロット内（ライブラリ配下同士など）ではスライド
    //   せず通常切替にする。
    private fun selectLocalTabAnimated(tab: LocalTab) {
        val fromSlot = localTabSlot(localTab)
        val toSlot = localTabSlot(tab)
        if (fromSlot == toSlot) {
            selectLocalTab(tab)
            return
        }
        finishLocalPageSlide()
        val oldPage = currentVisibleLocalPage()
        selectLocalTab(tab)
        animateLocalPageSlide(
            oldPage, currentVisibleLocalPage(), toNext = toSlot > fromSlot
        )
    }

    // Local 3ページのうち現在見えているもの（ページ出し分けの
    // 正は updateLocalPageVisibility 側で、ここは読むだけ）。
    private fun currentVisibleLocalPage(): View? {
        if (!::localPageContainer.isInitialized) return null
        return listOf(localNowPlaying, localQueuePage, localBrowserPage)
            .firstOrNull { it.visibility == View.VISIBLE }
    }

    private fun localPageForTab(tab: LocalTab): View = when (tab) {
        LocalTab.QUEUE -> localQueuePage
        LocalTab.NOW_PLAYING -> localNowPlaying
        LocalTab.LIBRARY_TOP, LocalTab.ALL, LocalTab.ALBUM, LocalTab.ARTIST, LocalTab.PLAYLIST ->
            localBrowserPage
    }

    private fun isLocalBrowserTab(tab: LocalTab): Boolean =
        localPageForTab(tab) === localBrowserPage

    // スワイプ切替時のスライド時間（ms）。ドラッグ確定／復帰の
    // 残り区間のアニメーションにも使い、距離に応じて短縮する。
    private val LOCAL_TAB_SLIDE_DURATION_MS = 280L

    // 進行中のスライドアニメーション。新しい切替や通常の再描画
    // と競合しないよう、開始前に必ず finishLocalPageSlide で
    // 確定させる。
    private val localSlideAnimators = mutableListOf<ViewPropertyAnimator>()

    // ★ 進行中のスライド／ドラッグプレビューを即座に確定させる。
    //   ViewPropertyAnimator の cancel() は withEndAction を同期的に
    //   実行する作法に合わせ、抜けた時点では表示ページは現在の
    //   localTab どおりに戻り、全ページの translationX は 0 になる。
    private fun finishLocalPageSlide() {
        if (localSwipeDragActive) {
            cancelLocalTabDragPreview()
        }
        if (localSlideAnimators.isNotEmpty()) {
            val running = localSlideAnimators.toList()
            localSlideAnimators.clear()
            running.forEach { it.cancel() }
        }
        if (pendingLocalDragRestoreState != null) {
            applyPendingLocalDragRestore()
        }
        if (::localPageContainer.isInitialized) {
            listOf(localNowPlaying, localQueuePage, localBrowserPage)
                .forEach { it.translationX = 0f }
            updateLocalPageVisibility()
        }
    }

    // ★ スワイプ切替のスライド: 次へ（右隣）なら新ページが右から
    //   入り旧ページが左へ出る、逆なら鏡像。旧ページと新ページが
    //   同じ View（ブラウザ系タブ同士）の場合は入場のみで表現する
    //   （内容は selectLocalTab で差し替わり済み）。startOffset は
    //   ドラッグで指に追従していた現在位置で、確定アニメーションを
    //   その位置から滑らかにつなぐために使う。
    private fun animateLocalPageSlide(
        oldPage: View?,
        newPage: View?,
        toNext: Boolean,
        startOffset: Float = 0f,
        durationMs: Long = LOCAL_TAB_SLIDE_DURATION_MS
    ) {
        if (newPage == null) return
        val width = localPageContainer.width
        if (width <= 0) return
        val enterFrom = if (toNext) width.toFloat() else -width.toFloat()
        val interpolator = android.view.animation.DecelerateInterpolator()
        if (oldPage != null && oldPage !== newPage) {
            oldPage.visibility = View.VISIBLE
            oldPage.translationX = startOffset
            lateinit var outAnimator: ViewPropertyAnimator
            outAnimator = oldPage.animate()
                .translationX(-enterFrom)
                .setDuration(durationMs)
                .setInterpolator(interpolator)
                .withEndAction {
                    oldPage.visibility = View.GONE
                    oldPage.translationX = 0f
                    localSlideAnimators.remove(outAnimator)
                }
            localSlideAnimators.add(outAnimator)
            outAnimator.start()
        }
        newPage.translationX = enterFrom + startOffset
        lateinit var inAnimator: ViewPropertyAnimator
        inAnimator = newPage.animate()
            .translationX(0f)
            .setDuration(durationMs)
            .setInterpolator(interpolator)
            .withEndAction {
                newPage.translationX = 0f
                localSlideAnimators.remove(inAnimator)
            }
        localSlideAnimators.add(inAnimator)
        inAnimator.start()
    }

    private data class LocalBrowserUiState(
        val tab: LocalTab,
        val groupKey: String?,
        val artistAlbumKey: String?,
        val playlistDrillId: Long?
    )

    private fun captureLocalBrowserUiState(): LocalBrowserUiState = LocalBrowserUiState(
        tab = localTab,
        groupKey = localGroupKey,
        artistAlbumKey = localArtistAlbumKey,
        playlistDrillId = localPlaylistDrillId
    )

    private fun restoreLocalBrowserUiState(state: LocalBrowserUiState) {
        localTab = state.tab
        localGroupKey = state.groupKey
        localArtistAlbumKey = state.artistAlbumKey
        localPlaylistDrillId = state.playlistDrillId
    }

    private fun applyPendingLocalDragRestore() {
        val state = pendingLocalDragRestoreState ?: return
        pendingLocalDragRestoreState = null
        restoreLocalBrowserUiState(state)
        refreshLocalBrowser()
        if (::localPageContainer.isInitialized) {
            listOf(localNowPlaying, localQueuePage, localBrowserPage)
                .forEach { it.translationX = 0f }
        }
    }

    private fun prepareLocalTabDragTarget(targetTab: LocalTab, toNext: Boolean): Boolean {
        val sourceTab = localSwipeDragSourceTab ?: return false
        val sourcePage = localSwipeDragSourcePage ?: return false
        val targetPage = localPageForTab(targetTab)

        if (isLocalBrowserTab(targetTab) && !isLocalBrowserTab(sourceTab)) {
            // 別ページからブラウザ系タブを覗く場合だけ、対象タブの
            // 内容を一時的に描く。戻す場合は保存した元のブラウザ
            // 状態を復元し、確定する場合は selectLocalTab 側の正規
            // 状態としてそのまま引き継ぐ。
            if (localSwipeDragSavedState == null) {
                localSwipeDragSavedState = captureLocalBrowserUiState()
            }
            localTab = targetTab
            localGroupKey = null
            localArtistAlbumKey = null
            localPlaylistDrillId = null
            refreshLocalBrowser()
        } else if (targetTab == LocalTab.QUEUE) {
            refreshQueueTab()
        } else if (targetTab == LocalTab.NOW_PLAYING && ::localPlayer.isInitialized) {
            updateLocalNowPlaying(localPlayer.currentTrack)
            updateLocalNowPlayingProgress()
        }

        localSwipeDragTargetTab = targetTab
        localSwipeDragTargetPage = targetPage
        localSwipeDragToNext = toNext
        sourcePage.visibility = View.VISIBLE
        targetPage.visibility = View.VISIBLE
        return true
    }

    private fun clearLocalTabDragTarget() {
        val savedState = localSwipeDragSavedState
        val targetPage = localSwipeDragTargetPage
        val sourcePage = localSwipeDragSourcePage
        localSwipeDragTargetTab = null
        localSwipeDragTargetPage = null
        localSwipeDragSavedState = null
        if (savedState != null) {
            restoreLocalBrowserUiState(savedState)
            refreshLocalBrowser()
        } else if (::localPageContainer.isInitialized) {
            updateLocalPageVisibility()
        }
        if (targetPage != null && targetPage !== sourcePage) {
            targetPage.translationX = 0f
        }
    }

    private fun ensureLocalTabDragTarget(dx: Float): Boolean {
        val sourceTab = localSwipeDragSourceTab ?: return false
        if (dx == 0f) return localSwipeDragTargetTab != null
        val toNext = dx < 0f
        val sourceIndex = localTabSlot(sourceTab)
        val targetIndex = if (toNext) sourceIndex + 1 else sourceIndex - 1
        val targetTab = localTabForSlot(targetIndex)
        if (targetTab == null) {
            if (localSwipeDragTargetTab != null) {
                clearLocalTabDragTarget()
            }
            localSwipeDragSourcePage?.translationX = 0f
            localSwipeDragLastDx = dx
            return false
        }
        if (localSwipeDragTargetTab == targetTab) {
            localSwipeDragToNext = toNext
            return true
        }
        if (localSwipeDragTargetTab != null) {
            clearLocalTabDragTarget()
        }
        return prepareLocalTabDragTarget(targetTab, toNext)
    }

    private fun applyLocalTabDragOffset(dx: Float) {
        localSwipeDragLastDx = dx
        val sourcePage = localSwipeDragSourcePage ?: return
        val targetPage = localSwipeDragTargetPage
        if (targetPage == null) {
            sourcePage.translationX = 0f
            return
        }
        if (targetPage === sourcePage) {
            sourcePage.translationX = dx
            return
        }
        val width = localPageContainer.width
        if (width <= 0) return
        val sideOffset = if (localSwipeDragToNext) width.toFloat() else -width.toFloat()
        sourcePage.translationX = dx
        targetPage.translationX = dx + sideOffset
    }

    private fun updateLocalTabDrag(ev: MotionEvent): Boolean {
        // ★ 古い MOVE（カクツキ中に溜まったイベント）からは
        //   ドラッグを開始しない。既に追従中のドラッグは最終
        //   位置の適用だけ行う（確定可否は UP 側の鮮度で決める）。
        val staleEvent = android.os.SystemClock.uptimeMillis() - ev.eventTime >
            LOCAL_SWIPE_STALE_EVENT_MS
        if (staleEvent && !localSwipeDragActive) return false
        val dx = ev.rawX - localSwipeStartX
        val dy = ev.rawY - localSwipeStartY
        val startDistance = LOCAL_TAB_DRAG_START_DISTANCE_DP *
            resources.displayMetrics.density
        if (!localSwipeDragActive) {
            // 縦方向が先に明確になったジェスチャはスクロールとして
            // 確定させ、このタッチでは横ドラッグへ移行しない。
            if (abs(dy) > startDistance &&
                abs(dy) > abs(dx) * LOCAL_TAB_SWIPE_AXIS_RATIO
            ) {
                localSwipeTracking = false
                return false
            }
            if (abs(dx) < startDistance ||
                abs(dx) < abs(dy) * LOCAL_TAB_SWIPE_AXIS_RATIO
            ) {
                return false
            }
            if (localPageContainer.width <= 0) return false
            localSwipeDragSourceTab = localTab
            localSwipeDragSourcePage = currentVisibleLocalPage() ?: return false
            if (!ensureLocalTabDragTarget(dx)) {
                localSwipeDragSourceTab = null
                localSwipeDragSourcePage = null
                return false
            }
            localSwipeDragActive = true
        } else {
            ensureLocalTabDragTarget(dx)
            if (localSwipeDragTargetTab == null) {
                localSwipeDragLastDx = dx
                return true
            }
        }
        applyLocalTabDragOffset(dx)
        return true
    }

    private fun cancelLocalTabDragPreview() {
        if (!localSwipeDragActive) return
        if (localSwipeDragTargetTab != null) {
            clearLocalTabDragTarget()
        }
        localSwipeDragActive = false
        localSwipeDragSourcePage?.translationX = 0f
        localSwipeDragSourceTab = null
        localSwipeDragSourcePage = null
        localSwipeDragLastDx = 0f
    }

    private fun rollbackLocalTabDrag() {
        val sourcePage = localSwipeDragSourcePage
        val targetPage = localSwipeDragTargetPage
        val targetTab = localSwipeDragTargetTab
        val toNext = localSwipeDragToNext
        val dx = localSwipeDragLastDx
        val savedState = localSwipeDragSavedState
        localSwipeDragActive = false
        localSwipeDragTargetTab = null
        localSwipeDragTargetPage = null
        localSwipeDragSavedState = null
        localSwipeDragSourceTab = null
        localSwipeDragSourcePage = null

        if (sourcePage == null || targetPage == null || targetTab == null) {
            sourcePage?.translationX = 0f
            if (savedState != null) {
                pendingLocalDragRestoreState = savedState
                applyPendingLocalDragRestore()
            }
            return
        }
        val width = localPageContainer.width
        if (width <= 0) {
            sourcePage.translationX = 0f
            targetPage.translationX = 0f
            if (savedState != null) {
                pendingLocalDragRestoreState = savedState
                applyPendingLocalDragRestore()
            } else {
                updateLocalPageVisibility()
            }
            return
        }

        pendingLocalDragRestoreState = savedState
        val duration = (abs(dx) / width * LOCAL_TAB_SLIDE_DURATION_MS)
            .toLong()
            .coerceIn(100L, LOCAL_TAB_SLIDE_DURATION_MS)
        val interpolator = android.view.animation.DecelerateInterpolator()
        if (targetPage === sourcePage) {
            lateinit var animator: ViewPropertyAnimator
            animator = sourcePage.animate()
                .translationX(0f)
                .setDuration(duration)
                .setInterpolator(interpolator)
                .withEndAction {
                    sourcePage.translationX = 0f
                    localSlideAnimators.remove(animator)
                }
            localSlideAnimators.add(animator)
            animator.start()
            return
        }

        val sideOffset = if (toNext) width.toFloat() else -width.toFloat()
        lateinit var sourceAnimator: ViewPropertyAnimator
        sourceAnimator = sourcePage.animate()
            .translationX(0f)
            .setDuration(duration)
            .setInterpolator(interpolator)
            .withEndAction {
                sourcePage.translationX = 0f
                localSlideAnimators.remove(sourceAnimator)
            }
        localSlideAnimators.add(sourceAnimator)
        sourceAnimator.start()

        lateinit var targetAnimator: ViewPropertyAnimator
        targetAnimator = targetPage.animate()
            .translationX(sideOffset)
            .setDuration(duration)
            .setInterpolator(interpolator)
            .withEndAction {
                targetPage.visibility = View.GONE
                targetPage.translationX = 0f
                localSlideAnimators.remove(targetAnimator)
                applyPendingLocalDragRestore()
            }
        localSlideAnimators.add(targetAnimator)
        targetAnimator.start()
    }

    private fun commitLocalTabDrag() {
        val sourcePage = localSwipeDragSourcePage
        val targetPage = localSwipeDragTargetPage
        val targetTab = localSwipeDragTargetTab
        val toNext = localSwipeDragToNext
        val dx = localSwipeDragLastDx
        localSwipeDragActive = false
        localSwipeDragTargetTab = null
        localSwipeDragTargetPage = null
        // 別ページからブラウザを覗いていた場合も、確定時は対象
        // タブを正規の選択状態として引き継ぐため復元しない。
        localSwipeDragSavedState = null
        localSwipeDragSourceTab = null
        localSwipeDragSourcePage = null
        if (sourcePage == null || targetPage == null || targetTab == null) return

        selectLocalTab(targetTab)
        val newPage = currentVisibleLocalPage() ?: return
        val width = localPageContainer.width
        if (width <= 0) {
            sourcePage.translationX = 0f
            newPage.translationX = 0f
            return
        }
        val remaining = (width - abs(dx).toInt()).coerceIn(0, width)
        val duration = (remaining.toFloat() / width * LOCAL_TAB_SLIDE_DURATION_MS)
            .toLong()
            .coerceIn(100L, LOCAL_TAB_SLIDE_DURATION_MS)
        animateLocalPageSlide(
            oldPage = sourcePage,
            newPage = newPage,
            toNext = toNext,
            startOffset = dx,
            durationMs = duration
        )
    }

    private fun settleLocalTabDrag(velocityX: Float) {
        val targetTab = localSwipeDragTargetTab
        if (targetTab == null) {
            localSwipeDragActive = false
            localSwipeDragSourcePage?.translationX = 0f
            localSwipeDragSourceTab = null
            localSwipeDragSourcePage = null
            return
        }
        val width = localPageContainer.width
        val dx = localSwipeDragLastDx
        val commitByDistance = width > 0 && if (localSwipeDragToNext) {
            dx <= -width * LOCAL_TAB_DRAG_COMMIT_DISTANCE_RATIO
        } else {
            dx >= width * LOCAL_TAB_DRAG_COMMIT_DISTANCE_RATIO
        }
        val commitByVelocity = if (localSwipeDragToNext) {
            velocityX <= -LOCAL_TAB_DRAG_COMMIT_VELOCITY
        } else {
            velocityX >= LOCAL_TAB_DRAG_COMMIT_VELOCITY
        }
        if (commitByDistance || commitByVelocity) {
            commitLocalTabDrag()
        } else {
            rollbackLocalTabDrag()
        }
    }

    private fun recycleLocalSwipeVelocityTracker() {
        localSwipeVelocityTracker?.recycle()
        localSwipeVelocityTracker = null
    }

    // ★ スワイプ追跡のリセット。曲の再生開始（リスト／キューの
    //   タップ起点）で呼び、追跡中のジェスチャとドラッグプレビュー
    //   を再生開始をまたいで持ち越さない。溜まった古いイベント
    //   自体は鮮度判定（LOCAL_SWIPE_STALE_EVENT_MS）で弾くのが
    //   本筋で、こちらは進行中ドラッグの掃除が目的。
    private fun resetLocalSwipeTracker() {
        localSwipeTracking = false
        recycleLocalSwipeVelocityTracker()
        if (localSwipeDragActive) {
            cancelLocalTabDragPreview()
        }
    }

    private fun dispatchCancelToLocalChildren(ev: MotionEvent) {
        val cancelEvent = MotionEvent.obtain(ev)
        cancelEvent.action = MotionEvent.ACTION_CANCEL
        super.dispatchTouchEvent(cancelEvent)
        cancelEvent.recycle()
    }

    // ★ スワイプ検出の追跡状態（dispatchTouchEvent で傍受する）。
    //   前実装（GestureDetector＋各ページへの非消費 OnTouchListener）は
    //   実機で全く反応しなかった: SimpleOnGestureListener は onDown が
    //   既定で false を返すため GestureDetector がジェスチャを破棄し、
    //   onFling が一度も呼ばれなかったのが主因（加えてリスト行などの
    //   子 View がタッチを掴むとページ側リスナへの配送も不安定）。
    //   そのため検出はアクティビティのタッチ配送の入り口で自前
    //   追跡する。水平ドラッグが成立するまではイベントを一切
    //   消費せず、通常のタッチ処理（縦スクロール・クリック・
    //   長押し・シーク）は無影響。成立後はページを指へ追従させ、
    //   以後の MOVE／UP を消費して行クリック等と競合させない。
    private var localSwipeTracking = false
    private var localSwipeStartX = 0f
    private var localSwipeStartY = 0f
    private var localSwipeStartTimeMs = 0L
    // ★ ドラッグ追従の状態。成立前は localSwipeTracking のみで
    //   観察し、成立した瞬間からページを指へ追従させる。source は
    //   ドラッグ開始時点のタブ／ページで、途中で localTab が
    //   プレビュー用に切り替わっても開始位置の基準を失わない。
    private var localSwipeDragActive = false
    private var localSwipeDragSourceTab: LocalTab? = null
    private var localSwipeDragSourcePage: View? = null
    private var localSwipeDragTargetTab: LocalTab? = null
    private var localSwipeDragTargetPage: View? = null
    private var localSwipeDragToNext = true
    private var localSwipeDragLastDx = 0f
    private var localSwipeDragSavedState: LocalBrowserUiState? = null
    private var pendingLocalDragRestoreState: LocalBrowserUiState? = null
    private var localSwipeVelocityTracker: android.view.VelocityTracker? = null

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        return if (trackLocalTabSwipe(ev)) {
            true
        } else {
            super.dispatchTouchEvent(ev)
        }
    }

    private fun trackLocalTabSwipe(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                recycleLocalSwipeVelocityTracker()
                localSwipeTracking = false
                if (currentPlaybackMode != PlaybackMode.LOCAL) return false
                if (isNpSeeking) return false
                if (!::localPageContainer.isInitialized) return false
                if (localPageContainer.visibility != View.VISIBLE) return false
                // 前のスライド／戻りアニメーションが残っていれば、
                // 新しいタッチの前に確定させてから次へ進む。
                finishLocalPageSlide()
                val rect = android.graphics.Rect()
                if (!localPageContainer.getGlobalVisibleRect(rect)) return false
                val x = ev.rawX.toInt()
                val y = ev.rawY.toInt()
                if (!rect.contains(x, y)) return false
                // シークバーと検索欄の上から始まるタッチは対象外
                // （ドラッグや文字入力と誤認しないため）。
                if (::seekNpProgress.isInitialized && isTouchInside(seekNpProgress, x, y)) return false
                if (::editLocalSearch.isInitialized && isTouchInside(editLocalSearch, x, y)) return false
                // ★ 鮮度判定: 発生から時間が空いた DOWN は、カクツキ
                //   中に溜まった古いジェスチャなので追跡しない
                //   （このジェスチャは通常のタッチとして流れる）。
                val nowMs = android.os.SystemClock.uptimeMillis()
                if (nowMs - ev.eventTime > LOCAL_SWIPE_STALE_EVENT_MS) return false
                localSwipeTracking = true
                localSwipeStartX = ev.rawX
                localSwipeStartY = ev.rawY
                // 開始時刻はイベントの発生時刻（eventTime）を正と
                // する。配送時刻で測ると、溜まったイベントの所要
                // 時間がほぼ 0 になり、フリック速度が異常に大きく
                // 算出されてしまう。
                localSwipeStartTimeMs = ev.eventTime
                localSwipeVelocityTracker = android.view.VelocityTracker.obtain()
                localSwipeVelocityTracker?.addMovement(ev)
                return false
            }
            MotionEvent.ACTION_MOVE -> {
                if (!localSwipeTracking) return localSwipeDragActive
                localSwipeVelocityTracker?.addMovement(ev)
                if (currentPlaybackMode != PlaybackMode.LOCAL || isNpSeeking) {
                    val wasActive = localSwipeDragActive
                    localSwipeTracking = false
                    recycleLocalSwipeVelocityTracker()
                    if (wasActive) rollbackLocalTabDrag()
                    return wasActive
                }
                val wasActive = localSwipeDragActive
                val activeNow = updateLocalTabDrag(ev)
                if (!localSwipeTracking && !wasActive) {
                    recycleLocalSwipeVelocityTracker()
                    return false
                }
                return activeNow
            }
            MotionEvent.ACTION_UP -> {
                if (!localSwipeTracking && !localSwipeDragActive) return false
                val wasTracking = localSwipeTracking
                localSwipeTracking = false
                localSwipeVelocityTracker?.addMovement(ev)
                localSwipeVelocityTracker?.computeCurrentVelocity(1000)
                val velocityX = localSwipeVelocityTracker?.xVelocity ?: 0f
                recycleLocalSwipeVelocityTracker()
                if (!wasTracking && !localSwipeDragActive) return false
                if (currentPlaybackMode != PlaybackMode.LOCAL || isNpSeeking) {
                    val wasActive = localSwipeDragActive
                    if (wasActive) rollbackLocalTabDrag()
                    return wasActive
                }

                // ★ 古い UP: 溜まったジェスチャの終端なので、タブ
                //   切替は発火させない（ドラッグ中だった場合は確定
                //   せず元のページへ戻す）。
                if (android.os.SystemClock.uptimeMillis() - ev.eventTime >
                    LOCAL_SWIPE_STALE_EVENT_MS
                ) {
                    val wasActive = localSwipeDragActive
                    if (wasActive) {
                        dispatchCancelToLocalChildren(ev)
                        rollbackLocalTabDrag()
                    }
                    return wasActive
                }

                // MOVE がほとんど配送されなかった短いフリックでも、
                // 最終位置でドラッグ成立を判定できるようにする。
                updateLocalTabDrag(ev)
                if (localSwipeDragActive) {
                    dispatchCancelToLocalChildren(ev)
                    settleLocalTabDrag(velocityX)
                    return true
                }

                val dx = ev.rawX - localSwipeStartX
                val dy = ev.rawY - localSwipeStartY
                // 所要時間はイベント発生時刻同士で測る（開始側も
                // eventTime に統一済み）。
                val dtMs = ev.eventTime - localSwipeStartTimeMs
                if (dtMs <= 0L) return false
                val minDistance = LOCAL_TAB_SWIPE_MIN_DISTANCE_DP *
                    resources.displayMetrics.density
                if (abs(dx) < minDistance) return false
                if (abs(dx) < abs(dy) * LOCAL_TAB_SWIPE_AXIS_RATIO) return false
                val averageVelocityX = abs(dx) / dtMs * 1000f
                if (averageVelocityX < LOCAL_TAB_SWIPE_MIN_VELOCITY) return false
                dispatchCancelToLocalChildren(ev)
                // 左スワイプ（指が左へ, dx < 0）→ 右隣のタブ
                switchLocalTabBySwipe(toNext = dx < 0)
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                val wasActive = localSwipeDragActive
                localSwipeTracking = false
                recycleLocalSwipeVelocityTracker()
                if (wasActive) rollbackLocalTabDrag()
                return false
            }
        }
        return false
    }

    private fun isTouchInside(view: View, rawX: Int, rawY: Int): Boolean {
        if (view.visibility != View.VISIBLE) return false
        val rect = android.graphics.Rect()
        if (!view.getGlobalVisibleRect(rect)) return false
        return rect.contains(rawX, rawY)
    }

    // 一覧のタップ振り分け: グループ一覧ならドリルダウン、曲リストなら
    // 表示中リストをキューとしてその曲から再生する。
    private fun onLocalListItemClicked(position: Int) {
        // 再生ページ表示中は一覧が見えていないため到達しないが、保険として弾く
        if (localTab == LocalTab.NOW_PLAYING) return
        if (localTab == LocalTab.LIBRARY_TOP) {
            // ライブラリトップ: 項目タップで各ビューへ入る。
            val entry = (localTrackList.adapter as? LocalLibraryTopAdapter)
                ?.getItem(position) ?: return
            selectLocalTab(entry.tab)
            return
        }
        if (localTab == LocalTab.PLAYLIST && localPlaylistDrillId == null) {
            // プレイリスト一覧: 先頭行は新規作成、それ以外はドリルダウン
            if (position == 0) {
                showCreatePlaylistDialog()
                return
            }
            val playlist = (localTrackList.adapter as? LocalPlaylistAdapter)
                ?.getItem(position) ?: return
            localPlaylistDrillId = playlist.id
            refreshLocalBrowser()
            return
        }
        if (localTab == LocalTab.ARTIST && localGroupKey != null && localArtistAlbumKey == null) {
            // ★ アーティストのアルバム一覧: 先頭行はそのアーティストの
            //   全曲再生、それ以外はアルバムへのドリルダウン。
            val artistKey = localGroupKey ?: return
            if (position == 0) {
                playAllArtistTracks(artistKey)
                return
            }
            val album = (localTrackList.adapter as? LocalArtistAlbumsAdapter)
                ?.albumGroupAt(position) ?: return
            localArtistAlbumKey = album.key
            refreshLocalBrowser()
            return
        }
        if (localGroupKey == null && (localTab == LocalTab.ALBUM || localTab == LocalTab.ARTIST)) {
            val group = (localTrackList.adapter as? LocalGroupAdapter)
                ?.getItem(position) ?: return
            localGroupKey = group.key
            refreshLocalBrowser()
            return
        }
        val tracks = localDisplayedTracks
        if (position !in tracks.indices) return
        val tapped = tracks[position]
        // ★ キューは「ライブラリに実在する曲だけ」で組み直し、タップ
        //   位置を表示リスト内ではなくキュー内の位置へ読み替える
        //   （プレイリスト内の消えた曲が間にあっても並びがずれない）。
        val queue = tracks.filter { it.uri.toString() in localTrackUriSet }
        val queueIndex = queue.indexOfFirst { it.uri == tapped.uri }
        if (queueIndex < 0) {
            Toast.makeText(this, "この曲はライブラリに見つかりません", Toast.LENGTH_SHORT).show()
            return
        }
        localPlayer.setQueue(queue)
        localPlayer.playAt(queueIndex)
        // ★ 明示的な曲タップで再生が始まったときだけ「再生」タブへ
        //   自動で切り替える（曲終わりの自動進行や通知・ウィジェット
        //   からの操作では切り替えない。ここは一覧タップの単一の
        //   起点なので、他の再生開始経路は通らない）。切替は
        //   スワイプ切替と同じスライドアニメーション付きで行う。
        //   あわせてスワイプ追跡をリセットする（再生開始直後の
        //   ジェスチャ状態を持ち越さないため。詳細は
        //   resetLocalSwipeTracker のコメント参照）。
        resetLocalSwipeTracker()
        selectLocalTabAnimated(LocalTab.NOW_PLAYING)
    }

    // 一覧の長押し振り分け: プレイリスト一覧では名前変更/削除、
    // プレイリスト内では「プレイリストから削除」、それ以外の
    // 曲リストではプレイリストへの追加ダイアログを開く。
    // グループ一覧では何もしないが、長押し後の誤タップ（ドリル
    // ダウン）を防ぐため消費だけする。
    private fun onLocalListItemLongClicked(position: Int): Boolean {
        if (localTab == LocalTab.NOW_PLAYING) return false
        if (localTab == LocalTab.LIBRARY_TOP) return true // トップの入口行は対象外（消費だけする）
        if (localTab == LocalTab.PLAYLIST && localPlaylistDrillId == null) {
            if (position == 0) return true // 新規作成行は対象外
            val playlist = (localTrackList.adapter as? LocalPlaylistAdapter)
                ?.getItem(position) ?: return true
            showPlaylistManageDialog(playlist)
            return true
        }
        if (localTab == LocalTab.ARTIST && localGroupKey != null && localArtistAlbumKey == null) {
            // アーティストのアルバム一覧では長押しは無効（消費だけする）
            return true
        }
        if (localGroupKey == null && (localTab == LocalTab.ALBUM || localTab == LocalTab.ARTIST)) {
            return true
        }
        val track = localDisplayedTracks.getOrNull(position) ?: return true
        val drillId = localPlaylistDrillId
        if (localTab == LocalTab.PLAYLIST && drillId != null) {
            showRemoveFromPlaylistDialog(drillId, track)
        } else {
            showAddToPlaylistDialog(track)
        }
        return true
    }

    // ★ アーティストの全曲再生（アルバム一覧の先頭行）。全アルバムを
    //   横断して、そのアーティストの全曲をアルバム順のキューにして
    //   先頭から再生し、「再生」タブへ切り替える（曲タップと同じ作法）。
    private fun playAllArtistTracks(artistKey: String) {
        val tracks = localTracks.filter { artistGroupKeyOf(it) == artistKey }
        if (tracks.isEmpty()) return
        val queue = sortLocalTracks(tracks, AppPreferences.LOCAL_SORT_ALBUM)
        localPlayer.setQueue(queue)
        localPlayer.playAt(0)
        resetLocalSwipeTracker()
        selectLocalTabAnimated(LocalTab.NOW_PLAYING)
    }

    // ── プレイリスト操作（ダイアログ群） ──

    // ★ 曲をプレイリストへ追加する共通処理。重複は追加せず通知する。
    private fun addTrackToPlaylist(track: LibraryRepository.Track, playlist: PlaylistStore.Playlist) {
        if (playlistStore.addItem(playlist.id, track)) {
            Toast.makeText(this, "「${playlist.name}」に追加しました", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "「${playlist.name}」には追加済みです", Toast.LENGTH_SHORT).show()
        }
        updateNpStarState()
        if (localTab == LocalTab.PLAYLIST) refreshLocalBrowser()
    }

    // ★ 曲をプレイリストから外す共通処理。外した後は ★ の表示も
    //   同期する（他のプレイリストに残っていても、追加先から外れて
    //   いれば ★ は輪郭に戻る現行ルールどおり）。
    private fun removeTrackFromPlaylist(track: LibraryRepository.Track, playlist: PlaylistStore.Playlist) {
        playlistStore.removeItem(playlist.id, track.uri.toString())
        Toast.makeText(this, "「${playlist.name}」から外しました", Toast.LENGTH_SHORT).show()
        updateNpStarState()
        if (localTab == LocalTab.PLAYLIST) refreshLocalBrowser()
    }

    // ★ 「どのプレイリストから外すか」の選択ダイアログ。
    //   ★ が塗りつぶし状態（追加先＝最後に作成したプレイリストに
    //   現在曲が入っている）のときに ★ タップで開く。選択肢は
    //   その曲が入っている全プレイリスト。
    private fun showRemoveFromPlaylistPickerDialog(track: LibraryRepository.Track) {
        val playlists = playlistStore.playlistsContaining(track.uri.toString())
        if (playlists.isEmpty()) {
            // 状態が変わっていた場合は追加側の作法に倒す
            addTrackToLatestPlaylist(track)
            return
        }
        val labels = playlists.map { it.name as CharSequence }
        AlertDialog.Builder(this)
            .setTitle("どのプレイリストから外しますか")
            .setItems(labels.toTypedArray()) { _, which ->
                removeTrackFromPlaylist(track, playlists[which])
            }
            .show()
    }

    // ★ ★タップ（輪郭状態）のクイック追加: 最後に作成した
    //   プレイリストへ現在曲を追加する。プレイリストが1つも
    //   無ければ新規作成へ誘導し、作成したリストへそのまま追加する。
    private fun addTrackToLatestPlaylist(track: LibraryRepository.Track) {
        val latest = playlistStore.latestPlaylist()
        if (latest == null) {
            showCreatePlaylistDialog { newId ->
                playlistStore.getPlaylist(newId)?.let { addTrackToPlaylist(track, it) }
            }
        } else {
            addTrackToPlaylist(track, latest)
        }
    }

    // ★ 追加先の選択ダイアログ（既存プレイリスト＋新規作成）。
    //   ★ の長押しと、曲リストの長押しで共用する。
    private fun showAddToPlaylistDialog(track: LibraryRepository.Track) {
        val playlists = playlistStore.listPlaylists()
        val labels = playlists.map { it.name as CharSequence } + "＋ 新規作成"
        AlertDialog.Builder(this)
            .setTitle("プレイリストに追加")
            .setItems(labels.toTypedArray()) { _, which ->
                if (which >= playlists.size) {
                    showCreatePlaylistDialog { newId ->
                        playlistStore.getPlaylist(newId)?.let { addTrackToPlaylist(track, it) }
                    }
                } else {
                    addTrackToPlaylist(track, playlists[which])
                }
            }
            .show()
    }

    // ★ 新規作成ダイアログ（名前入力、空名不可）。
    //   作成できたら onCreated に新しい id を渡す。表示中のタブが
    //   プレイリストなら一覧も更新する。
    private fun showCreatePlaylistDialog(onCreated: ((Long) -> Unit)? = null) {
        val input = EditText(this).apply {
            hint = "プレイリスト名"
            setSingleLine(true)
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            val pad = (20 * resources.displayMetrics.density).toInt()
            setPadding(pad, 0, pad, 0)
            addView(input, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("プレイリストを作成")
            .setView(container)
            .setPositiveButton("作成", null)
            .setNegativeButton("キャンセル", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = input.text.toString().trim()
                if (name.isEmpty()) {
                    Toast.makeText(this, "名前を入力してください", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                val id = playlistStore.createPlaylist(name)
                Toast.makeText(this, "「$name」を作成しました", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
                if (localTab == LocalTab.PLAYLIST) refreshLocalBrowser()
                onCreated?.invoke(id)
            }
        }
        dialog.show()
    }

    // ★ プレイリスト行の長押しメニュー（名前の変更／削除）。
    private fun showPlaylistManageDialog(playlist: PlaylistStore.Playlist) {
        val labels = arrayOf<CharSequence>("名前を変更", "削除")
        AlertDialog.Builder(this)
            .setTitle(playlist.name)
            .setItems(labels) { _, which ->
                when (which) {
                    0 -> showRenamePlaylistDialog(playlist)
                    1 -> {
                        AlertDialog.Builder(this)
                            .setTitle("プレイリストを削除")
                            .setMessage("「${playlist.name}」を削除しますか？（曲のファイル自体は消えません）")
                            .setPositiveButton("削除") { _, _ ->
                                playlistStore.deletePlaylist(playlist.id)
                                if (localPlaylistDrillId == playlist.id) {
                                    localPlaylistDrillId = null
                                }
                                Toast.makeText(this, "削除しました", Toast.LENGTH_SHORT).show()
                                refreshLocalBrowser()
                            }
                            .setNegativeButton("キャンセル", null)
                            .show()
                    }
                }
            }
            .show()
    }

    private fun showRenamePlaylistDialog(playlist: PlaylistStore.Playlist) {
        val input = EditText(this).apply {
            setText(playlist.name)
            setSingleLine(true)
            setSelection(text.length)
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            val pad = (20 * resources.displayMetrics.density).toInt()
            setPadding(pad, 0, pad, 0)
            addView(input, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("名前を変更")
            .setView(container)
            .setPositiveButton("変更", null)
            .setNegativeButton("キャンセル", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = input.text.toString().trim()
                if (name.isEmpty()) {
                    Toast.makeText(this, "名前を入力してください", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                playlistStore.renamePlaylist(playlist.id, name)
                dialog.dismiss()
                refreshLocalBrowser()
            }
        }
        dialog.show()
    }

    // ★ プレイリスト内の曲行の長押し: そのプレイリストから外す。
    private fun showRemoveFromPlaylistDialog(playlistId: Long, track: LibraryRepository.Track) {
        AlertDialog.Builder(this)
            .setTitle(track.title.ifBlank { track.displayName })
            .setItems(arrayOf<CharSequence>("プレイリストから削除")) { _, _ ->
                playlistStore.removeItem(playlistId, track.uri.toString())
                Toast.makeText(this, "プレイリストから削除しました", Toast.LENGTH_SHORT).show()
                updateNpStarState()
                refreshLocalBrowser()
            }
            .show()
    }

    // ★ 再生画面の ★ の見た目を同期する。塗りつぶしになるのは
    //   「最後に作成したプレイリスト」に現在曲が入っている場合
    //   のみ（★ タップの追加先と表示の対象を一致させる）。
    private fun updateNpStarState() {
        if (!::btnNpStar.isInitialized || !::localPlayer.isInitialized) return
        val track = localPlayer.currentTrack ?: return
        val isDark = isDarkThemeActive()
        val colorAccent = ContextCompat.getColor(this@MainActivity, if (isDark) R.color.accent else R.color.accent_light)
        val colorOff = ContextCompat.getColor(this@MainActivity, if (isDark) R.color.text_tertiary else R.color.text_tertiary_light)
        val latest = playlistStore.latestPlaylist()
        val isMember = latest != null &&
            playlistStore.contains(latest.id, track.uri.toString())
        btnNpStar.setImageResource(
            if (isMember) R.drawable.ic_star else R.drawable.ic_star_outline
        )
        btnNpStar.setColorFilter(if (isMember) colorAccent else colorOff)
    }

    private fun updateLocalTabStyles() {
        if (!::tabLocalLibrary.isInitialized) return
        val isDark = isDarkThemeActive()
        val colorSelected = ContextCompat.getColor(this@MainActivity, if (isDark) R.color.accent else R.color.accent_light)
        val colorNormal = ContextCompat.getColor(this@MainActivity, if (isDark) R.color.text_secondary else R.color.text_secondary_light)
        // ★ ライブラリタブは固定ラベル（LIBRARY）。選択状態は
        //   ライブラリトップ／配下ビューのいずれを表示中かで
        //   決まる（スロット単位の選択表示）。
        tabLocalLibrary.text = "LIBRARY"
        // (view, アイコンを決めるタブ, 選択中か) の組。
        val tabs = listOf(
            Triple(tabLocalQueue, LocalTab.QUEUE, localTab == LocalTab.QUEUE),
            Triple(
                tabLocalNowPlaying, LocalTab.NOW_PLAYING,
                localTab == LocalTab.NOW_PLAYING
            ),
            Triple(
                tabLocalLibrary, LocalTab.LIBRARY_TOP,
                isLocalLibraryTab(localTab)
            )
        )
        for ((view, tab, selected) in tabs) {
            val color = if (selected) colorSelected else colorNormal
            view.setTextColor(color)
            view.paint.isFakeBoldText = selected
            // ★ タブのアイコンもラベルと同じ色で着色する
            //   （選択中はアクセント色、通常時はグレー）。
            //   mutate してから tint し、他所の drawable と
            //   状態を共有しないようにする。
            val icon = ContextCompat.getDrawable(this, localTabIconRes(tab))?.mutate()
            icon?.setTint(color)
            view.setCompoundDrawablesWithIntrinsicBounds(null, icon, null, null)
        }
        btnLocalSort.text = localSortLabel(appPrefs.localSortMode)
        // ★ 並び替えボタンは TRACKS（全曲）のときだけ出す
        //   （ユーザー指定）。それ以外のビューでは検索欄が
        //   全幅を使う。
        btnLocalSort.visibility =
            if (localTab == LocalTab.ALL) View.VISIBLE else View.GONE
    }

    // ★ 下部タブバーの各タブに割り当てるアイコン（ic_tab_*）。
    //   when は LocalTab の全値を網羅すること。
    private fun localTabIconRes(tab: LocalTab): Int {
        return when (tab) {
            LocalTab.QUEUE -> R.drawable.ic_tab_queue
            LocalTab.NOW_PLAYING -> R.drawable.ic_tab_now_playing
            LocalTab.LIBRARY_TOP -> R.drawable.ic_tab_library
            LocalTab.ALL -> R.drawable.ic_tab_all
            LocalTab.ALBUM -> R.drawable.ic_tab_album
            LocalTab.ARTIST -> R.drawable.ic_tab_artist
            LocalTab.PLAYLIST -> R.drawable.ic_tab_playlist
        }
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
                label = key,
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
            LocalTab.ALBUM -> albumKeyOf(track)
            LocalTab.ARTIST -> artistGroupKeyOf(track)
            LocalTab.ALL, LocalTab.NOW_PLAYING, LocalTab.PLAYLIST, LocalTab.QUEUE,
            LocalTab.LIBRARY_TOP -> ""
        }
    }

    // アルバムのグループキー（アルバム名そのもの。空は不明ラベル）
    private fun albumKeyOf(track: LibraryRepository.Track): String {
        return track.album.ifBlank { unknownAlbumLabel }
    }

    // ★ アーティストのグループキー。設定「アーティストの分類」で
    //   切り替わる: アルバムアーティスト指定時はタグの albumArtist
    //   （無い曲は曲のアーティストで代替）、アーティスト指定時は
    //   曲のアーティストそのもの。空は不明ラベル。
    private fun artistGroupKeyOf(track: LibraryRepository.Track): String {
        val raw = if (appPrefs.localArtistGrouping ==
            AppPreferences.LOCAL_ARTIST_GROUPING_ARTIST
        ) {
            track.artist
        } else {
            track.albumArtist.ifBlank { track.artist }
        }
        return raw.ifBlank { unknownArtistLabel }
    }

    // ★ アーティスト配下のアルバム一覧の導出。検索中は、アルバム名に
    //   一致するか、そのアルバム内に検索に一致する曲を含むアルバムを
    //   残す（グループ一覧と同じ流儀）。
    private fun localAlbumsForArtist(artistKey: String, query: String): List<LocalGroup> {
        val grouped = LinkedHashMap<String, MutableList<LibraryRepository.Track>>()
        for (track in localTracks) {
            if (artistGroupKeyOf(track) != artistKey) continue
            grouped.getOrPut(albumKeyOf(track)) { mutableListOf() } += track
        }
        val q = query.lowercase(Locale.ROOT)
        return grouped.map { (key, tracks) ->
            LocalGroup(key = key, label = key, tracks = tracks, uniformArtist = null)
        }.filter { group ->
            q.isBlank() ||
                group.label.lowercase(Locale.ROOT).contains(q) ||
                group.tracks.any { localTrackMatchesQuery(it, query) }
        }.sortedWith(
            compareBy<LocalGroup> { it.label.lowercase(Locale.ROOT) }
        )
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
                textLocalMessage.text = "アクセスが許可されませんでした。設定画面の「ライブラリ取得範囲」からフォルダを追加する方法でも曲を追加できます"
                textLocalMessage.visibility = View.VISIBLE
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

    // ★ 設定画面を開く直前のアーティスト分類。変わって戻ってきたら、
    //   ドリルダウン中のキーが新しい分類では無効になるため、ドリル
    //   状態をリセットしてからアーティスト表示を組み替える。
    private var artistGroupingBeforeSettings: Int? = null

    private fun openSettings() {
        adBlockEnabledBeforeSettings = appPrefs.isAdBlockEnabled
        artistGroupingBeforeSettings = appPrefs.localArtistGrouping
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
        // ★ 出力先が変わったら Local のデコードレート上限は解除する
        //   （新しい経路では素のレートから試し直す）。
        if (::localPlayer.isInitialized) localPlayer.clearOutputRateCap()
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
            // ★ アーティスト分類が変わっていたら、ドリルダウン状態
            //   （旧分類のキー）をリセットする。分類自体は導出時に
            //   prefs を都度読むため、下の再読込で新しい分類の
            //   アーティスト表示に組み替わる。
            val beforeGrouping = artistGroupingBeforeSettings
            if (beforeGrouping != null && appPrefs.localArtistGrouping != beforeGrouping) {
                localGroupKey = null
                localArtistAlbumKey = null
                localPlaylistDrillId = null
            }
            artistGroupingBeforeSettings = null
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
            // ★ 供給元フラグの初期同期（起動時の保存モードに合わせる。
            //   以後の切替は switchPlaybackMode が同期する）。
            playbackService?.isLocalSourceActive =
                (currentPlaybackMode == PlaybackMode.LOCAL)
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

            // ★ ストリーム構成の結果通知（レートフォールバック発生・
            //   出力レート上限の要求・構成失敗の上限到達）。
            playbackService?.onStreamRateFallback = { requestedRate, actualRate ->
                runOnUiThread {
                    Toast.makeText(
                        this@MainActivity,
                        "出力レートを ${actualRate}Hz に切り替えて再生します（要求 ${requestedRate}Hz）",
                        Toast.LENGTH_SHORT
                    ).show()
                    updateStatus()
                }
            }
            playbackService?.onOutputRateCapNeeded = { capHz ->
                // ★ 出力側がソースレートの AudioTrack を作れなかった
                //   ため、Local のデコード側で上限レートへ変換して
                //   供給し直す。YT 側にはデコードレートの概念が無い
                //   ため、Local モードのときだけ適用する。
                if (currentPlaybackMode == PlaybackMode.LOCAL) {
                    runOnUiThread {
                        Toast.makeText(
                            this@MainActivity,
                            "出力に合わせて ${capHz}Hz に変換して再生します",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    localPlayer.applyOutputRateCap(capHz)
                }
            }
            playbackService?.onStreamConfigurationFailed = { _, targetRate ->
                // ★ 同じ構成で上限回数失敗した。Local ではその曲を
                //   理由つきで終え、YT では理由だけ知らせる（従来は
                //   再構成と破棄が無言で無限に繰り返されていた）。
                if (currentPlaybackMode == PlaybackMode.LOCAL) {
                    localPlayer.stopWithError(
                        "出力ストリームを構成できませんでした（${targetRate}Hz）"
                    )
                } else {
                    runOnUiThread {
                        Toast.makeText(
                            this@MainActivity,
                            "出力ストリームを構成できませんでした（${targetRate}Hz）",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }

            playbackService?.onPeakListener = { dbL, dbR, mask, queueSize, latencyMs ->
                lastPcmTime = System.currentTimeMillis()
                peakDbL = dbL
                peakDbR = dbR
                bitActivityMask = bitActivityMask or mask
                currentQueueSize = queueSize
                currentLatencyMs = latencyMs
                // ★ レベルメーター本体へはここから直接渡さない。到着駆動だと
                //   Local のバースト供給で配送間隔が空きメーターが凍結→
                //   ジャンプになるため、表示は uiUpdateRunnable が
                //   getPeakAlignedToPlayback（再生位置整列）で駆動する。
                //   こちらの値は SysMonitor 表示とストール検知用に保持する。
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
        textLocalMessage = findViewById(R.id.textLocalMessage)
        textModeSwitch = findViewById(R.id.textModeSwitch)
        localTrackList = findViewById(R.id.localTrackList)
        localScanProgress = findViewById(R.id.localScanProgress)
        progressLocalScan = findViewById(R.id.progressLocalScan)
        textLocalScanStatus = findViewById(R.id.textLocalScanStatus)
        textLocalScanCurrent = findViewById(R.id.textLocalScanCurrent)
        localScanUpdating = findViewById(R.id.localScanUpdating)
        textLocalUpdating = findViewById(R.id.textLocalUpdating)
        tabLocalQueue = findViewById(R.id.tabLocalQueue)
        tabLocalNowPlaying = findViewById(R.id.tabLocalNowPlaying)
        tabLocalLibrary = findViewById(R.id.tabLocalLibrary)
        localSearchRow = findViewById(R.id.localSearchRow)
        editLocalSearch = findViewById(R.id.editLocalSearch)
        btnLocalSort = findViewById(R.id.btnLocalSort)
        localGroupHeader = findViewById(R.id.localGroupHeader)
        btnLocalBack = findViewById(R.id.btnLocalBack)
        textLocalGroupTitle = findViewById(R.id.textLocalGroupTitle)
        localBrowserPage = findViewById(R.id.localBrowserPage)
        localNowPlaying = findViewById(R.id.localNowPlaying)
        localQueuePage = findViewById(R.id.localQueuePage)
        localPageContainer = findViewById(R.id.localPageContainer)
        localQueueList = findViewById(R.id.localQueueList)
        textQueueEmpty = findViewById(R.id.textQueueEmpty)
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
        btnNpStar = findViewById(R.id.btnNpStar)

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
                    // ★ アートワーク状態の所有権はモードで分ける:
                    //   Gecko セッションは Local モードでも保持されるため、
                    //   ページ側メタデータの再送や画像取得の遅延完了が
                    //   Local 中に届くことがある。表示・保持状態への
                    //   反映は YT モードのときだけに限る（Local 中に
                    //   currentArtworkBitmap 等へ YT のサムネイルが
                    //   入ると、Local 再生画面の再描画で漏れてしまう）。
                    //   ページ生存判定（lastPageSignalTime）はモードに
                    //   依らないため、ガードより先に更新する。
                    if (currentPlaybackMode != PlaybackMode.YT_MUSIC) return
                    currentTitle = title
                    currentArtist = artist
                    playbackService?.updateMetadata(title, artist, artworkUrl)
                    if (artworkUrl.isNotEmpty()) {
                        imageExecutor.execute {
                            try {
                                val stream = URL(artworkUrl).openStream()
                                val bmp = BitmapFactory.decodeStream(stream)
                                runOnUiThread {
                                    // 取得中に Local へ切り替わっていたら
                                    // 保持状態へは入れない（上のガードと対）。
                                    if (currentPlaybackMode == PlaybackMode.YT_MUSIC) {
                                        currentArtworkBitmap = bmp
                                        updateDialogPlayerUi()
                                    }
                                }
                            } catch (e: Exception) { Log.e("MainActivity", "Image decode error", e) }
                        }
                    }
                    updateDialogPlayerUi()
                }
                override fun onProgress(currentMs: Long, durationMs: Long, isPlaying: Boolean) {
                    lastPageSignalTime = System.currentTimeMillis()
                    // ★ 再生状態・位置の共有状態への反映は YT モードの
                    //   ときだけ（onMetadata の所有権分離と同じ作法）。
                    //   ページ生存判定（lastPageSignalTime）はモードに
                    //   依らないため、ガードより先に更新する。
                    //   Local モードでは保持中のページはポーズ済みで、
                    //   content.js の syncPlaybackProgress が 1 秒周期で
                    //   playing=false の progress を送り続ける。ガード
                    //   無しでは Local 再生中にも共有の isPlayingState /
                    //   Service の再生状態 / 現在位置が「停止・ページ
                    //   位置」へ毎秒上書きされ、再生/一時停止ボタンの
                    //   コマンド解決が "play" に化けてポーズが効かなく
                    //   なる（Local 中の正は LocalPlayer 側の通知）。
                    if (currentPlaybackMode != PlaybackMode.YT_MUSIC) return
                    currentPosition = currentMs
                    currentDuration = durationMs
                    isPlayingState = isPlaying
                    playbackService?.updateProgress(currentMs, durationMs, isPlaying)
                    updateDialogPlayerUi()
                }
                override fun onState(isPlaying: Boolean) {
                    lastPageSignalTime = System.currentTimeMillis()
                    // ★ onProgress と同じ所有権分離: 共有の再生状態への
                    //   反映は YT モードのときだけ。Local 中にページ側の
                    //   state（ポーズ済みページの false）が届いても、
                    //   Local の再生状態・メーター・Service 状態を
                    //   倒さない。
                    if (currentPlaybackMode != PlaybackMode.YT_MUSIC) return
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
                // ★ Local のドリルダウン中は、まず1段戻る
                //   （プレイリストのドリルダウンも同様）
                if (currentPlaybackMode == PlaybackMode.LOCAL && localDrillBack()) {
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
        localTrackList.setOnItemLongClickListener { _, view, position, _ ->
            view?.hapticTick(appPrefs)
            // ★ 長押し: プレイリストへの追加/管理（プレイリスト
            //   機能）。振り分けは onLocalListItemLongClicked 側。
            onLocalListItemLongClicked(position)
        }
        tabLocalQueue.setOnClickListener {
            it.hapticTick(appPrefs)
            selectLocalTab(LocalTab.QUEUE)
        }
        tabLocalNowPlaying.setOnClickListener {
            it.hapticTick(appPrefs)
            selectLocalTab(LocalTab.NOW_PLAYING)
        }
        localQueueList.setOnItemClickListener { _, view, position, _ ->
            view?.hapticTick(appPrefs)
            // ★ キューの行タップは、その曲（実効再生順の位置）へ
            //   ジャンプして再生する。他のリストと同じ作法で、
            //   ジャンプ後は「再生」タブへ自動で切り替える
            //   （スライドアニメーション付き）。
            if (::localPlayer.isInitialized) {
                localPlayer.playAtEffectivePosition(position)
                resetLocalSwipeTracker()
                selectLocalTabAnimated(LocalTab.NOW_PLAYING)
            }
        }
        localQueueList.setOnItemLongClickListener { _, view, position, _ ->
            view?.hapticTick(appPrefs)
            // ★ 長押しは他の曲リストと同じくプレイリストへの追加。
            val track = if (::localPlayer.isInitialized) {
                localPlayer.effectiveOrderTracks().getOrNull(position)
            } else {
                null
            } ?: return@setOnItemLongClickListener true
            showAddToPlaylistDialog(track)
            true
        }
        tabLocalLibrary.setOnClickListener {
            it.hapticTick(appPrefs)
            // ★ ライブラリタブ: ライブラリトップページへ切り
            //   替える（トップから各ビューへは項目タップで入る）。
            selectLocalTab(LocalTab.LIBRARY_TOP)
        }
        // （タブ切替スワイプは dispatchTouchEvent 側で傍受するため、
        //   ここでの配線は不要）
        btnLocalBack.setOnClickListener {
            it.hapticTick(appPrefs)
            localDrillBack()
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
            // ★ シャッフル切替で順序表が作り直され、実効位置
            //   （n / N 表示）も変わるため、再生画面を描き直す。
            updateLocalPlayerChrome()
        }
        btnNpRepeat.setOnClickListener {
            it.hapticTick(appPrefs)
            localPlayer.cycleRepeatMode()
            updateNpToggleStates()
        }
        btnNpStar.setOnClickListener {
            it.hapticTick(appPrefs)
            // ★ タップは状態で分岐する（★ の表示ルールと対象を一致
            //   させる）:
            //   - 塗りつぶし状態（追加先＝最後に作成したプレイリスト
            //     に現在曲が入っている）→ 「どのプレイリストから
            //     外すか」の選択ダイアログを出す。
            //   - 輪郭状態 → 追加先へのクイック追加（従来どおり）。
            val track = localPlayer.currentTrack ?: return@setOnClickListener
            val latest = playlistStore.latestPlaylist()
            val isMember = latest != null &&
                playlistStore.contains(latest.id, track.uri.toString())
            if (isMember) {
                showRemoveFromPlaylistPickerDialog(track)
            } else {
                addTrackToLatestPlaylist(track)
            }
        }
        btnNpStar.setOnLongClickListener {
            it.hapticTick(appPrefs)
            // ★ 長押し: 追加先の選択（既存一覧＋新規作成）
            val track = localPlayer.currentTrack ?: return@setOnLongClickListener true
            showAddToPlaylistDialog(track)
            true
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
            mainRootLayout.setBackgroundColor(ContextCompat.getColor(this, R.color.main_background))
            topInfoPanel?.setBackgroundColor(ContextCompat.getColor(this, R.color.main_background))
            panelDividerLine?.setBackgroundColor(ContextCompat.getColor(this@MainActivity, R.color.divider))
            textDacName.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
            textRateBits.setTextColor(Color.WHITE)
            textCodec.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_status_codec))
            textTransfer.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_status_transfer))
            textPeak.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_status_peak))
            textBufferLatency.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_secondary))
            textCpuRamMonitor.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_secondary))
            btnReload.setBackgroundResource(R.drawable.bg_btn_icon)
            btnDspSettings.setBackgroundResource(R.drawable.bg_btn_icon)
            btnUiSettings.setBackgroundResource(R.drawable.bg_btn_icon)
            textModeSwitch.setBackgroundResource(R.drawable.bg_btn_icon)
            textModeSwitch.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.accent))
            localPlaceholder.setBackgroundColor(ContextCompat.getColor(this, R.color.main_background))
            textLocalMessage.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_secondary))
            localTrackList.divider = ColorDrawable(ContextCompat.getColor(this@MainActivity, R.color.divider))
            editLocalSearch.setBackgroundResource(R.drawable.bg_local_search)
            editLocalSearch.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
            editLocalSearch.setHintTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_tertiary))
            btnLocalSort.setBackgroundResource(R.drawable.bg_btn_icon)
            btnLocalSort.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.accent))
            btnLocalBack.setBackgroundResource(R.drawable.bg_btn_icon)
            btnLocalBack.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.accent))
            textLocalGroupTitle.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
            localNowPlaying.setBackgroundColor(ContextCompat.getColor(this, R.color.main_background))
            textNpEmpty.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_secondary))
            textNpQueuePosition.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_secondary))
            textNpTitle.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
            textNpArtist.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_np_artist))
            textNpAlbum.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_secondary))
            textNpFormat.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_secondary))
            textNpCurrentTime.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_secondary))
            textNpTotalTime.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_secondary))
            seekNpProgress.progressTintList =
                ColorStateList.valueOf(ContextCompat.getColor(this@MainActivity, R.color.accent))
            seekNpProgress.thumbTintList = ColorStateList.valueOf(Color.WHITE)
            btnNpPrev.setBackgroundResource(R.drawable.bg_btn_walkman_circle_small)
            btnNpNext.setBackgroundResource(R.drawable.bg_btn_walkman_circle_small)
            // ★ シャッフル / リピートは円で囲わない（アイコンのみ、
            //   状態は updateNpToggleStates の色付けで示す）
            btnNpPlayPause.setBackgroundResource(R.drawable.bg_btn_walkman_circle_large)
            btnNpPrev.clearColorFilter()
            btnNpNext.clearColorFilter()
            btnNpPlayPause.clearColorFilter()
            updateNpToggleStates()
            updateLocalTabStyles()
            localTrackAdapter?.notifyDataSetChanged()
            localGroupAdapter?.notifyDataSetChanged()
            btnReload.setColorFilter(ContextCompat.getColor(this@MainActivity, R.color.label_gray))
            btnDspSettings.setColorFilter(ContextCompat.getColor(this@MainActivity, R.color.accent))
            btnUiSettings.setColorFilter(ContextCompat.getColor(this@MainActivity, R.color.accent))
        } else {
            mainRootLayout.setBackgroundColor(ContextCompat.getColor(this@MainActivity, R.color.main_background_light))
            topInfoPanel?.setBackgroundColor(ContextCompat.getColor(this@MainActivity, R.color.surface_panel_light))
            panelDividerLine?.setBackgroundColor(ContextCompat.getColor(this@MainActivity, R.color.divider_light))
            textDacName.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary_light))
            textRateBits.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary_light))
            textCodec.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_secondary_light))
            textTransfer.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_secondary_light))
            textPeak.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_secondary_strong_light))
            textBufferLatency.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_secondary_light))
            textCpuRamMonitor.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_secondary_light))
            btnReload.setBackgroundResource(R.drawable.bg_btn_icon_light)
            btnDspSettings.setBackgroundResource(R.drawable.bg_btn_icon_light)
            btnUiSettings.setBackgroundResource(R.drawable.bg_btn_icon_light)
            textModeSwitch.setBackgroundResource(R.drawable.bg_btn_icon_light)
            textModeSwitch.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.accent_light))
            localPlaceholder.setBackgroundColor(ContextCompat.getColor(this@MainActivity, R.color.main_background_light))
            textLocalMessage.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_secondary_light))
            localTrackList.divider = ColorDrawable(ContextCompat.getColor(this@MainActivity, R.color.divider_light))
            editLocalSearch.setBackgroundResource(R.drawable.bg_local_search_light)
            editLocalSearch.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary_light))
            editLocalSearch.setHintTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_tertiary_light))
            btnLocalSort.setBackgroundResource(R.drawable.bg_btn_icon_light)
            btnLocalSort.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.accent_light))
            btnLocalBack.setBackgroundResource(R.drawable.bg_btn_icon_light)
            btnLocalBack.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.accent_light))
            textLocalGroupTitle.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary_light))
            localNowPlaying.setBackgroundColor(ContextCompat.getColor(this@MainActivity, R.color.main_background_light))
            textNpEmpty.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_secondary_light))
            textNpQueuePosition.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_secondary_light))
            textNpTitle.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary_light))
            textNpArtist.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_secondary_strong_light))
            textNpAlbum.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_secondary_light))
            textNpFormat.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_secondary_light))
            textNpCurrentTime.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_secondary_light))
            textNpTotalTime.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_secondary_light))
            seekNpProgress.progressTintList =
                ColorStateList.valueOf(ContextCompat.getColor(this@MainActivity, R.color.accent_light))
            seekNpProgress.thumbTintList =
                ColorStateList.valueOf(ContextCompat.getColor(this@MainActivity, R.color.text_primary_light))
            btnNpPrev.setBackgroundResource(R.drawable.bg_btn_walkman_circle_small_light)
            btnNpNext.setBackgroundResource(R.drawable.bg_btn_walkman_circle_small_light)
            // ★ シャッフル / リピートは円で囲わない（ダーク側と同じ）
            btnNpPlayPause.setBackgroundResource(R.drawable.bg_btn_walkman_circle_large_light)
            btnNpPrev.setColorFilter(ContextCompat.getColor(this@MainActivity, R.color.text_primary_light))
            btnNpNext.setColorFilter(ContextCompat.getColor(this@MainActivity, R.color.text_primary_light))
            btnNpPlayPause.setColorFilter(ContextCompat.getColor(this@MainActivity, R.color.text_primary_light))
            updateNpToggleStates()
            updateLocalTabStyles()
            localTrackAdapter?.notifyDataSetChanged()
            localGroupAdapter?.notifyDataSetChanged()
            btnReload.setColorFilter(ContextCompat.getColor(this@MainActivity, R.color.text_primary_light))
            btnDspSettings.setColorFilter(ContextCompat.getColor(this@MainActivity, R.color.accent_light))
            btnUiSettings.setColorFilter(ContextCompat.getColor(this@MainActivity, R.color.accent_light))
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
        // ★ 位置は実効再生順（順序表）における現在位置で示す
        //   （シャッフル中はシャッフル順での n / N）。
        val index = localPlayer.effectivePosition()
        textNpQueuePosition.text =
            if (queueSize > 0 && index in 0 until queueSize) "${index + 1} / $queueSize" else ""
        // ★ 再生画面のアートワークは Local 専用の保持
        //   （localArtworkBitmap）を正とする。共有の
        //   currentArtworkBitmap は YT 側メタデータの到着で
        //   書き換わりうるため、ここで使うと画面遷移後の
        //   再描画で YT のサムネイルが表示されてしまう。
        val artwork = localArtworkBitmap
        if (artwork != null) {
            imgNpArtwork.setImageBitmap(artwork)
        } else {
            imgNpArtwork.setImageResource(R.drawable.ic_local_artwork_placeholder)
        }
        btnNpPlayPause.setImageResource(
            if (localPlayer.isPlaying) R.drawable.ic_pause else R.drawable.ic_play
        )
        updateNpToggleStates()
        updateNpStarState()
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
        val colorAccent = ContextCompat.getColor(this@MainActivity, if (isDark) R.color.accent else R.color.accent_light)
        val colorOff = ContextCompat.getColor(this@MainActivity, if (isDark) R.color.text_tertiary else R.color.text_tertiary_light)
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
            val colorTitle = ContextCompat.getColor(this@MainActivity, if (isDark) R.color.text_primary else R.color.text_primary_light)
            val colorValue = ContextCompat.getColor(this@MainActivity, if (isDark) R.color.text_secondary else R.color.text_secondary_light)
            val colorAccent = ContextCompat.getColor(this@MainActivity, if (isDark) R.color.accent else R.color.accent_light)
            // ★ 再生中ハイライトは位置ではなく曲そのもの（URI）で判定する。
            //   表示リストとキューの並びが一致しない瞬間（再スキャン直後等）
            //   でも別の曲を光らせないため。
            val isCurrent = currentPlaybackMode == PlaybackMode.LOCAL &&
                ::localPlayer.isInitialized &&
                localPlayer.currentTrack?.uri == track.uri
            // ★ プレイリストのドリルダウンで、ライブラリから消えた
            //   曲（スナップショットのプレースホルダ行）はグレーで示す。
            val isMissing = localTab == LocalTab.PLAYLIST &&
                localPlaylistDrillId != null &&
                track.uri.toString() !in localTrackUriSet

            val root = (convertView as? LinearLayout) ?: buildRow()
            val thumbView = root.getChildAt(0) as ImageView
            val titleView = root.getChildAt(1).let { (it as LinearLayout).getChildAt(0) as TextView }
            val subView = root.getChildAt(1).let { (it as LinearLayout).getChildAt(1) as TextView }
            val durationView = root.getChildAt(2) as TextView

            titleView.text = track.title
            titleView.setTextColor(
                when {
                    isCurrent -> colorAccent
                    isMissing -> colorValue
                    else -> colorTitle
                }
            )
            val subParts = mutableListOf<String>()
            if (track.artist.isNotBlank()) subParts += track.artist
            if (track.album.isNotBlank()) subParts += track.album
            if (subParts.isEmpty()) subParts += track.displayName
            if (isMissing) subParts += "ライブラリに見つかりません"
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
            thumbView.setBackgroundColor(ContextCompat.getColor(this@MainActivity, if (isDark) R.color.surface_thumbnail else R.color.surface_thumbnail_light))
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
    // ★ ライブラリトップの入口一覧のアダプタ。行は
    //   アイコン＋ラベル＋件数＋シェブロンで、グループ行と
    //   同じ寸法感に揃える。
    private inner class LocalLibraryTopAdapter(
        private val entries: List<LocalLibraryEntry>
    ) : BaseAdapter() {

        override fun getCount(): Int = entries.size
        override fun getItem(position: Int): LocalLibraryEntry = entries[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val entry = entries[position]
            val isDark = isDarkThemeActive()
            val colorTitle = ContextCompat.getColor(this@MainActivity, if (isDark) R.color.text_primary else R.color.text_primary_light)
            val colorValue = ContextCompat.getColor(this@MainActivity, if (isDark) R.color.text_secondary else R.color.text_secondary_light)

            val root = (convertView as? LinearLayout)?.takeIf { it.tag == ROW_TAG }
                ?: buildRow()
            val iconView = root.getChildAt(0) as android.widget.ImageView
            val nameView = root.getChildAt(1).let { (it as LinearLayout).getChildAt(0) as TextView }
            val subView = root.getChildAt(1).let { (it as LinearLayout).getChildAt(1) as TextView }
            val chevronView = root.getChildAt(2) as TextView

            val icon = ContextCompat.getDrawable(this@MainActivity, entry.iconRes)?.mutate()
            icon?.setTint(colorTitle)
            iconView.setImageDrawable(icon)
            nameView.text = entry.label
            nameView.setTextColor(colorTitle)
            subView.text = entry.subLabel
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
                setPadding(dp(4), dp(11), dp(4), dp(11))
                layoutParams = android.widget.AbsListView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                tag = ROW_TAG
            }
            val iconView = android.widget.ImageView(this@MainActivity).apply {
                layoutParams = LinearLayout.LayoutParams(dp(24), dp(24)).apply {
                    marginEnd = dp(12)
                }
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
            root.addView(iconView)
            root.addView(texts)
            root.addView(chevronView)
            return root
        }

        private companion object {
            const val ROW_TAG = "localLibraryTopRow"
        }
    }

    private inner class LocalGroupAdapter(
        private val groups: List<LocalGroup>
    ) : BaseAdapter() {

        override fun getCount(): Int = groups.size
        override fun getItem(position: Int): LocalGroup = groups[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val group = groups[position]
            val isDark = isDarkThemeActive()
            val colorTitle = ContextCompat.getColor(this@MainActivity, if (isDark) R.color.text_primary else R.color.text_primary_light)
            val colorValue = ContextCompat.getColor(this@MainActivity, if (isDark) R.color.text_secondary else R.color.text_secondary_light)

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

    // ★ アーティスト配下のアルバム一覧のアダプタ。先頭行は
    //   「全てのアルバムを再生」のアクション行で、それ以降が
    //   アルバム本体（名前＋曲数）。行レイアウトはグループ行と
    //   同じ作りで使い回す（プレイリスト一覧と同じ +1 行方式）。
    private inner class LocalArtistAlbumsAdapter(
        private val groups: List<LocalGroup>,
        private val totalTrackCount: Int
    ) : BaseAdapter() {

        override fun getCount(): Int = groups.size + 1

        // position 0 は全曲再生行のため null を返す
        fun albumGroupAt(position: Int): LocalGroup? =
            if (position == 0) null else groups.getOrNull(position - 1)

        override fun getItem(position: Int): LocalGroup? = albumGroupAt(position)
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val isDark = isDarkThemeActive()
            val colorTitle = ContextCompat.getColor(this@MainActivity, if (isDark) R.color.text_primary else R.color.text_primary_light)
            val colorValue = ContextCompat.getColor(this@MainActivity, if (isDark) R.color.text_secondary else R.color.text_secondary_light)
            val colorAccent = ContextCompat.getColor(this@MainActivity, if (isDark) R.color.accent else R.color.accent_light)

            val root = (convertView as? LinearLayout) ?: buildRow()
            val nameView = root.getChildAt(0).let { (it as LinearLayout).getChildAt(0) as TextView }
            val subView = root.getChildAt(0).let { (it as LinearLayout).getChildAt(1) as TextView }
            val chevronView = root.getChildAt(1) as TextView

            if (position == 0) {
                nameView.text = "全てのアルバムを再生"
                nameView.setTextColor(colorAccent)
                subView.text = "全 $totalTrackCount 曲"
                subView.setTextColor(colorValue)
                chevronView.text = "▶"
                chevronView.setTextColor(colorAccent)
            } else {
                val group = groups[position - 1]
                nameView.text = group.label
                nameView.setTextColor(colorTitle)
                subView.text = "${group.tracks.size} 曲"
                subView.setTextColor(colorValue)
                chevronView.text = "›"
                chevronView.setTextColor(colorValue)
            }
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

    // ★ プレイリスト一覧のアダプタ。先頭行は「＋ 新規作成」の
    //   アクション行で、それ以降がプレイリスト本体（名前＋曲数）。
    //   行レイアウトはグループ行と同じ作りで使い回す。
    private inner class LocalPlaylistAdapter(
        private val playlists: List<PlaylistStore.Playlist>
    ) : BaseAdapter() {

        override fun getCount(): Int = playlists.size + 1

        // position 0 は新規作成行のため null を返す
        override fun getItem(position: Int): PlaylistStore.Playlist? =
            if (position == 0) null else playlists.getOrNull(position - 1)

        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val isDark = isDarkThemeActive()
            val colorTitle = ContextCompat.getColor(this@MainActivity, if (isDark) R.color.text_primary else R.color.text_primary_light)
            val colorValue = ContextCompat.getColor(this@MainActivity, if (isDark) R.color.text_secondary else R.color.text_secondary_light)
            val colorAccent = ContextCompat.getColor(this@MainActivity, if (isDark) R.color.accent else R.color.accent_light)

            val root = (convertView as? LinearLayout) ?: buildRow()
            val nameView = root.getChildAt(0).let { (it as LinearLayout).getChildAt(0) as TextView }
            val subView = root.getChildAt(0).let { (it as LinearLayout).getChildAt(1) as TextView }
            val chevronView = root.getChildAt(1) as TextView

            if (position == 0) {
                nameView.text = "＋ 新規作成"
                nameView.setTextColor(colorAccent)
                subView.text = "新しいプレイリストを作る"
                subView.setTextColor(colorValue)
                chevronView.text = ""
            } else {
                val playlist = playlists[position - 1]
                nameView.text = playlist.name
                nameView.setTextColor(colorTitle)
                subView.text = "${playlist.itemCount} 曲"
                subView.setTextColor(colorValue)
                chevronView.text = "›"
                chevronView.setTextColor(colorValue)
            }
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
            textBufferLatency.setTextColor(if (isDarkThemeActive()) ContextCompat.getColor(this@MainActivity, R.color.text_secondary) else ContextCompat.getColor(this@MainActivity, R.color.text_secondary_light))
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
        textBitDepth.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.accent))

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