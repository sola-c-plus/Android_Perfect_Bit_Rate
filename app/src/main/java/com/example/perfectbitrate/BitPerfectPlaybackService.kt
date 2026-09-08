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
    
    var currentBitMode = "16bit"

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
    private val isSwitchingRate = AtomicBoolean(false)
    private var lastConfiguredMixerDevice: AudioDeviceInfo? = null

    private val MAX_QUEUE_CAPACITY = 64
    val pcmQueue = LinkedBlockingQueue<ByteArray>(MAX_QUEUE_CAPACITY)
    
    private val PREROLL_THRESHOLD = 3
    private val isBuffering = AtomicBoolean(true)

    @Volatile private var isRunning = false
    private var playbackThread: Thread? = null

    var onPeakListener: ((Float, Float, Int) -> Unit)? = null
    var onDeviceDisconnectedListener: (() -> Unit)? = null
    var onActualBitModeChanged: ((String) -> Unit)? = null

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

    fun setSafeSpeakerVolume() {
        try {
            val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val safeVol = (maxVol * 0.25f).toInt().coerceAtLeast(1)
            val currentVol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            if (currentVol > safeVol) {
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, safeVol, 0)
            }
        } catch (e: Exception) {}
    }

    // ★ OS のスピーカー切り替え遅延（50ms〜300ms）を考慮し、多段階で確実に消音リセット
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
                    val currentVol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                    val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                    if (currentVol == 0 || currentVol < (maxVol * 0.5f).toInt()) {
                        val defaultVol = (maxVol * 0.90f).toInt().coerceAtLeast(1)
                        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, defaultVol, 0)
                    }
                    audioTrack?.setVolume(1.0f)
                }
            } else if (device == null) {
                forceResetSpeakerVolume()
            }
        } catch (e: Exception) {}
    }

    fun handleBecomingNoisyOrDisconnected() {
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
        onPeakListener?.invoke(-60f, -60f, 0)

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

        if (device == null) {
            isVolumeLocked = false
            forceResetSpeakerVolume()
            if (isUsbDevice(prevDev)) {
                handleBecomingNoisyOrDisconnected()
                return
            }
        }

        updateVolumeControlMode()

        if (changed && device != null) {
            switchStreamConfiguration()
        }
    }

    fun setDirectSourceMode(isDirect: Boolean) {
        if (isDirectSource == isDirect) return
        isDirectSource = isDirect
        NativeAudioEngine.nativeSetDirectSource(isDirect)
        switchStreamConfiguration()
    }

    fun setUpsampling(factor: Int) {
        val validFactor = when (factor) {
            2 -> 2
            4 -> 4
            8 -> 8
            else -> 1
        }
        upsampleFactor = validFactor
        switchStreamConfiguration()
    }

    fun updateBaseSampleRate(newRate: Int) {
        if (baseSampleRate == newRate) return
        baseSampleRate = newRate
        switchStreamConfiguration()
    }

    private fun switchStreamConfiguration() {
        val factorToApply = effectiveFactor
        val targetRate = baseSampleRate * factorToApply
        
        isSwitchingRate.set(true)
        isBuffering.set(true)
        pcmQueue.clear()

        effectiveSampleRate = targetRate
        NativeAudioEngine.nativeConfigureUpsampler(factorToApply, baseSampleRate)

        trackExecutor.execute {
            try {
                initAudioTrack(currentBitMode, baseSampleRate, factorToApply, activeOutputDevice)
            } finally {
                pcmQueue.clear()
                isSwitchingRate.set(false)
            }
        }
    }

    fun pushPcm(pcmBytes: ByteArray, sampleRate: Int, inBitMode: String) {
        if (!isCurrentlyPlaying) {
            isCurrentlyPlaying = true
        }

        if (isSwitchingRate.get()) {
            return
        }

        val actualInputRate = if (sampleRate > 0) sampleRate else baseSampleRate
        val factorToApply = effectiveFactor
        val targetEffectiveRate = actualInputRate * factorToApply

        if (actualInputRate != baseSampleRate || targetEffectiveRate != effectiveSampleRate) {
            baseSampleRate = actualInputRate
            switchStreamConfiguration()
            return
        }

        val needsRecreate = (audioTrack == null || audioTrack?.state != AudioTrack.STATE_INITIALIZED)
        if (needsRecreate) {
            switchStreamConfiguration()
            return
        }

        val processedBytes = NativeAudioEngine.nativeProcessUpsample(
            pcmBytes, pcmBytes.size, inBitMode, currentBitMode, factorToApply
        ) ?: pcmBytes

        if (!pcmQueue.offer(processedBytes)) {
            pcmQueue.poll()
            pcmQueue.offer(processedBytes)
        }

        if (isBuffering.get() && pcmQueue.size >= PREROLL_THRESHOLD) {
            isBuffering.set(false)
        }
    }

    fun resetBuffer() {
        isBuffering.set(true)
        pcmQueue.clear()
        audioLock.lock()
        try {
            audioTrack?.flush()
        } catch (e: Exception) {}
        finally {
            audioLock.unlock()
        }
        NativeAudioEngine.nativeResetUpsampler()
        onPeakListener?.invoke(-60f, -60f, 0)
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
                    resetBuffer()
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
    }

    fun forceCloseDacStream() {
        isCurrentlyPlaying = false
        isBuffering.set(true)
        pcmQueue.clear()
        NativeAudioEngine.nativeResetUpsampler()
        onPeakListener?.invoke(-60f, -60f, 0)

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
        val bitStr = currentBitMode
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

    fun initAudioTrack(
        bitMode: String = currentBitMode,
        baseRate: Int = baseSampleRate,
        factor: Int = effectiveFactor,
        targetDevice: AudioDeviceInfo? = activeOutputDevice
    ) {
        if (isInitializingTrack.getAndSet(true)) {
            hasPendingInit.set(true)
            return
        }

        try {
            do {
                hasPendingInit.set(false)
                doInitAudioTrackInternal(currentBitMode, baseSampleRate, effectiveFactor, activeOutputDevice)
            } while (hasPendingInit.get())
        } finally {
            isInitializingTrack.set(false)
        }
    }

    private fun doInitAudioTrackInternal(
        bitMode: String,
        baseRate: Int,
        factor: Int,
        targetDevice: AudioDeviceInfo?
    ) {
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

                effectiveSampleRate = targetRate
                NativeAudioEngine.nativeConfigureUpsampler(factorToApply, baseSampleRate)

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
                            it.format.sampleRate == effectiveSampleRate &&
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
                                it.format.sampleRate == effectiveSampleRate &&
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
                                    .setSampleRate(effectiveSampleRate)
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
                                    .setSampleRate(effectiveSampleRate)
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

                val minBuf = AudioTrack.getMinBufferSize(effectiveSampleRate, AudioFormat.CHANNEL_OUT_STEREO, finalEncoding)
                val desiredBuf = effectiveSampleRate * 2 * bytesPerSample / 4
                val bufferSize = max(if (minBuf > 0) minBuf * 4 else 16384, desiredBuf)

                var createdTrack: AudioTrack? = null
                for (retry in 0..2) {
                    try {
                        val track = AudioTrack.Builder()
                            .setAudioAttributes(mediaAttr)
                            .setAudioFormat(
                                AudioFormat.Builder()
                                    .setEncoding(finalEncoding)
                                    .setSampleRate(effectiveSampleRate)
                                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                                    .build()
                            )
                            .setBufferSizeInBytes(bufferSize)
                            .setTransferMode(AudioTrack.MODE_STREAM)
                            .build()

                        if (track.state == AudioTrack.STATE_INITIALIZED) {
                            targetDevice?.let { track.setPreferredDevice(it) }
                            restoreVolumeForDevice(targetDevice)
                            track.setVolume(1.0f)
                            track.play()
                            createdTrack = track
                            break
                        } else {
                            track.release()
                            try { Thread.sleep(60) } catch (e: InterruptedException) {}
                        }
                    } catch (e: Exception) {
                        try { Thread.sleep(60) } catch (e: InterruptedException) {}
                    }
                }

                audioTrack = createdTrack

                val actualModeStr = when (finalEncoding) {
                    AudioFormat.ENCODING_PCM_32BIT -> "32bit"
                    AudioFormat.ENCODING_PCM_24BIT_PACKED -> "24bit"
                    else -> "16bit"
                }
                currentBitMode = actualModeStr
                onActualBitModeChanged?.invoke(actualModeStr)

                Log.i("BitPerfect", "★ AudioTrack Ready: ${effectiveSampleRate}Hz ($actualModeStr) -> ${targetDevice?.productName ?: "Default"} (Lock: $lockSuccess)")
            } finally {
                audioLock.unlock()
            }
        } catch (e: Exception) {
            Log.e("BitPerfect", "Critical AudioTrack init error", e)
        }
    }

    private fun startPlaybackLoop() {
        isRunning = true
        playbackThread = Thread {
            var heartbeatCounter = 0
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
                        if (pcmQueue.size < PREROLL_THRESHOLD) {
                            Thread.sleep(10)
                            continue
                        } else {
                            isBuffering.set(false)
                        }
                    }

                    val pcm = pcmQueue.poll(100, TimeUnit.MILLISECONDS)
                    if (pcm == null) {
                        if (pcmQueue.isEmpty()) {
                            isBuffering.set(true)
                        }
                        continue
                    }

                    analyzeAndDispatchPeak(pcm, currentBitMode)

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
                        if (written < 0) {
                            handleBecomingNoisyOrDisconnected()
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

    private fun analyzeAndDispatchPeak(pcmBytes: ByteArray, bitMode: String) {
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

        onPeakListener?.invoke(instantPeakL, instantPeakR, bitMask)
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