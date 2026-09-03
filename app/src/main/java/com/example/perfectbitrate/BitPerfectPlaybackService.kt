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
    var audioTrack: AudioTrack? = null
    private lateinit var audioManager: AudioManager

    var baseSampleRate = 48000
    var effectiveSampleRate = 48000
    
    var currentBitMode = "16bit"
    var upsampleFactor = 1
    var activeOutputDevice: AudioDeviceInfo? = null
    private val audioLock = ReentrantLock()

    private val trackExecutor = Executors.newSingleThreadExecutor()
    private val isInitializingTrack = AtomicBoolean(false)
    private var lastConfiguredMixerDevice: AudioDeviceInfo? = null

    private val MAX_QUEUE_CAPACITY = 32
    val pcmQueue = LinkedBlockingQueue<ByteArray>(MAX_QUEUE_CAPACITY)
    
    private val PREROLL_THRESHOLD = 3
    private val isBuffering = AtomicBoolean(true)

    @Volatile private var isRunning = false
    private var playbackThread: Thread? = null

    var onPeakListener: ((Float, Float, Int, FloatArray) -> Unit)? = null
    var onDeviceDisconnectedListener: (() -> Unit)? = null
    var onActualBitModeChanged: ((String) -> Unit)? = null

    private val tempSpectrumOut = FloatArray(32) { -60f }

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
    private var currentArtwork: Bitmap? = null
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
                isVolumeLocked = false
                muteVolumeToZero()
                clearPreviousMixerAttributes()
                forceCloseDacStream()
                onDeviceDisconnectedListener?.invoke()
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
        PlayerWidgetProvider.updateAllWidgets(this, currentTitle, currentArtist, currentArtwork, isCurrentlyPlaying, currentPosition, currentDuration)
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

    fun restoreVolumeForDevice(device: AudioDeviceInfo?) {
        try {
            if (isUsbDevice(device)) {
                if (isVolumeLocked) {
                    lockSystemVolumeToMax()
                } else {
                    val currentVol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                    if (currentVol == 0) {
                        val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                        val defaultVol = (maxVol * 0.85f).toInt().coerceAtLeast(1)
                        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, defaultVol, 0)
                    }
                    audioTrack?.setVolume(1.0f)
                }
            }
        } catch (e: Exception) {}
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

    fun setUpsampling(factor: Int) {
        val validFactor = when (factor) {
            2 -> 2
            4 -> 4
            8 -> 8
            else -> 1
        }
        upsampleFactor = validFactor
        effectiveSampleRate = baseSampleRate * validFactor
        NativeAudioEngine.nativeConfigureUpsampler(validFactor, baseSampleRate)
        isBuffering.set(true)
        trackExecutor.execute {
            initAudioTrack(currentBitMode, baseSampleRate, validFactor, activeOutputDevice)
        }
    }

    fun pushPcm(pcmBytes: ByteArray, sampleRate: Int, inBitMode: String) {
        isCurrentlyPlaying = true

        val actualInputRate = if (sampleRate > 0) sampleRate else baseSampleRate
        val targetEffectiveRate = actualInputRate * upsampleFactor

        val needsRecreate = (actualInputRate != baseSampleRate ||
                             targetEffectiveRate != effectiveSampleRate || 
                             audioTrack == null || 
                             audioTrack?.state != AudioTrack.STATE_INITIALIZED)

        if (needsRecreate && !isInitializingTrack.get()) {
            baseSampleRate = actualInputRate
            effectiveSampleRate = targetEffectiveRate
            NativeAudioEngine.nativeConfigureUpsampler(upsampleFactor, baseSampleRate)
            isBuffering.set(true)
            trackExecutor.execute {
                initAudioTrack(currentBitMode, baseSampleRate, upsampleFactor, activeOutputDevice)
            }
        }

        val processedBytes = NativeAudioEngine.nativeProcessUpsample(
            pcmBytes, pcmBytes.size, inBitMode, currentBitMode, upsampleFactor
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
        NativeAudioEngine.nativeResetUpsampler()
        tempSpectrumOut.fill(-60f)
        onPeakListener?.invoke(-60f, -60f, 0, tempSpectrumOut)
    }

    fun setOutputDevice(device: AudioDeviceInfo?) {
        val changed = (activeOutputDevice?.id != device?.id)
        activeOutputDevice = device

        if (device == null) {
            isVolumeLocked = false
            muteVolumeToZero()
            clearPreviousMixerAttributes()
        }

        updateVolumeControlMode()

        if (changed && audioTrack != null) {
            isBuffering.set(true)
            trackExecutor.execute {
                initAudioTrack(currentBitMode, baseSampleRate, upsampleFactor, device)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "ACTION_PLAY" -> onCommandListener?.invoke("play")
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
                override fun onPlay() { onCommandListener?.invoke("play") }
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

        if (currentArtwork != null) {
            metaBuilder.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, currentArtwork)
            metaBuilder.putBitmap(MediaMetadataCompat.METADATA_KEY_ART, currentArtwork)
        }

        mediaSession.setMetadata(metaBuilder.build())
        updatePlaybackState(isPlaying, currentMs)
        PlayerWidgetProvider.updateAllWidgets(this, currentTitle, currentArtist, currentArtwork, isPlaying, currentMs, durationMs)
    }

    fun forceCloseDacStream() {
        isBuffering.set(true)
        pcmQueue.clear()
        NativeAudioEngine.nativeResetUpsampler()
        tempSpectrumOut.fill(-60f)
        onPeakListener?.invoke(-60f, -60f, 0, tempSpectrumOut)
        trackExecutor.execute {
            audioLock.lock()
            try {
                audioTrack?.let { track ->
                    try {
                        track.pause()
                        track.flush()
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
        PlayerWidgetProvider.updateAllWidgets(this, currentTitle, currentArtist, currentArtwork, false, currentPosition, currentDuration)
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

        if (!isPlaying) {
            forceCloseDacStream()
        }
        updateNotification()
        PlayerWidgetProvider.updateAllWidgets(this, currentTitle, currentArtist, currentArtwork, isPlaying, position, currentDuration)
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

        if (currentArtwork != null) {
            metaBuilder.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, currentArtwork)
            metaBuilder.putBitmap(MediaMetadataCompat.METADATA_KEY_ART, currentArtwork)
        }

        mediaSession.setMetadata(metaBuilder.build())
        updateNotification()
        PlayerWidgetProvider.updateAllWidgets(this, title, artist, currentArtwork, isCurrentlyPlaying, currentPosition, currentDuration)

        if (artworkUrl.isNotEmpty()) {
            imageExecutor.execute {
                try {
                    val stream = URL(artworkUrl).openStream()
                    currentArtwork = BitmapFactory.decodeStream(stream)
                    metaBuilder.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, currentArtwork)
                    metaBuilder.putBitmap(MediaMetadataCompat.METADATA_KEY_ART, currentArtwork)
                    mediaSession.setMetadata(metaBuilder.build())
                    updateNotification()
                    PlayerWidgetProvider.updateAllWidgets(this, currentTitle, currentArtist, currentArtwork, isCurrentlyPlaying, currentPosition, currentDuration)
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

        val upsampleTag = if (upsampleFactor > 1) " [FREQ ${upsampleFactor}x]" else ""

        val notification = NotificationCompat.Builder(this, "bitperfect_service_channel")
            .setContentTitle(currentTitle)
            .setContentText("$currentArtist | $currentCodec$upsampleTag")
            .setSubText("${effectiveSampleRate}Hz $bitStr $deviceLabel")
            .setLargeIcon(currentArtwork)
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

    // ★ 多段フォールバック & 強制アンラッチによる192k固定化の完全解消
    fun initAudioTrack(
        bitMode: String,
        baseRate: Int = baseSampleRate,
        factor: Int = upsampleFactor,
        targetDevice: AudioDeviceInfo? = null
    ) {
        if (isInitializingTrack.getAndSet(true)) {
            return
        }

        try {
            audioLock.lock()
            try {
                // 1. 旧トラック停止と完全解放
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

                // ★ 古い192k設定をOSから明示的に解除してラッチを破壊！
                clearPreviousMixerAttributes()

                // DACのALSAハードウェアクロック解放待ち
                try { Thread.sleep(120) } catch (e: InterruptedException) {}

                activeOutputDevice = targetDevice
                baseSampleRate = baseRate
                upsampleFactor = factor
                effectiveSampleRate = baseRate * factor

                val mediaAttr = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()

                var finalEncoding = AudioFormat.ENCODING_PCM_16BIT
                var lockSuccess = false

                // 2. Android 14+ ハードウェアクロック切り替え (安全な多段フォールバック)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && isUsbDevice(targetDevice)) {
                    val supportedMixers = try {
                        audioManager.getSupportedMixerAttributes(targetDevice!!)
                    } catch (e: Exception) {
                        emptyList<AudioMixerAttributes>()
                    }

                    // ユーザー希望のエンコーディング優先リスト
                    val encTrialList = when (bitMode) {
                        "32bit" -> listOf(
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) AudioFormat.ENCODING_PCM_32BIT else AudioFormat.ENCODING_PCM_16BIT,
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) AudioFormat.ENCODING_PCM_24BIT_PACKED else AudioFormat.ENCODING_PCM_16BIT,
                            AudioFormat.ENCODING_PCM_16BIT
                        )
                        "24bit" -> listOf(
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) AudioFormat.ENCODING_PCM_24BIT_PACKED else AudioFormat.ENCODING_PCM_16BIT,
                            AudioFormat.ENCODING_PCM_16BIT
                        )
                        else -> listOf(AudioFormat.ENCODING_PCM_16BIT)
                    }

                    // 非対応エンコーディングで拒否されても、下位エンコーディング（16bit等）で必ず成功させる
                    for (tryEnc in encTrialList) {
                        val matched = supportedMixers.firstOrNull { 
                            it.format.sampleRate == effectiveSampleRate && 
                            it.format.encoding == tryEnc &&
                            it.mixerBehavior == AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT
                        } ?: supportedMixers.firstOrNull { 
                            it.format.sampleRate == effectiveSampleRate && 
                            it.format.encoding == tryEnc
                        } ?: supportedMixers.firstOrNull { 
                            it.format.sampleRate == effectiveSampleRate
                        } ?: AudioMixerAttributes.Builder(
                            AudioFormat.Builder()
                                .setSampleRate(effectiveSampleRate)
                                .setEncoding(tryEnc)
                                .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                                .build()
                        ).setMixerBehavior(AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT).build()

                        try {
                            val ok = audioManager.setPreferredMixerAttributes(mediaAttr, targetDevice!!, matched)
                            if (ok) {
                                lastConfiguredMixerDevice = targetDevice
                                finalEncoding = matched.format.encoding
                                lockSuccess = true
                                Log.i("BitPerfect", "★ DAC Clock Locked to ${effectiveSampleRate} Hz (enc=$finalEncoding): SUCCESS")
                                break
                            }
                        } catch (e: Exception) {
                            Log.w("BitPerfect", "Mixer set try failed for enc=$tryEnc", e)
                        }
                    }

                    if (!lockSuccess) {
                        Log.w("BitPerfect", "★ All mixer tries failed for ${effectiveSampleRate} Hz. Fallback to 16-bit default.")
                        finalEncoding = AudioFormat.ENCODING_PCM_16BIT
                    }
                }

                // 3. AudioTrack の作成
                val bytesPerSample = when (finalEncoding) {
                    AudioFormat.ENCODING_PCM_32BIT -> 4
                    AudioFormat.ENCODING_PCM_24BIT_PACKED -> 3
                    else -> 2
                }

                val minBuf = AudioTrack.getMinBufferSize(effectiveSampleRate, AudioFormat.CHANNEL_OUT_STEREO, finalEncoding)
                val desiredBuf = effectiveSampleRate * 2 * bytesPerSample / 4
                val bufferSize = max(if (minBuf > 0) minBuf * 4 else 16384, desiredBuf)

                var createdTrack: AudioTrack? = null
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
                    } else {
                        track.release()
                    }
                } catch (e: Exception) {
                    Log.e("BitPerfect", "AudioTrack create error", e)
                }

                audioTrack = createdTrack

                val actualModeStr = when (finalEncoding) {
                    AudioFormat.ENCODING_PCM_32BIT -> "32bit"
                    AudioFormat.ENCODING_PCM_24BIT_PACKED -> "24bit"
                    else -> "16bit"
                }
                currentBitMode = actualModeStr
                onActualBitModeChanged?.invoke(actualModeStr)

                Log.i("BitPerfect", "★ AudioTrack Active: ${effectiveSampleRate}Hz ($actualModeStr) -> ${targetDevice?.productName ?: "Default"}")
            } finally {
                audioLock.unlock()
            }
        } catch (e: Exception) {
            Log.e("BitPerfect", "Critical AudioTrack init error", e)
        } finally {
            isInitializingTrack.set(false)
        }
    }

    private fun startPlaybackLoop() {
        isRunning = true
        playbackThread = Thread {
            while (isRunning) {
                try {
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

                    if (track != null && track.state == AudioTrack.STATE_INITIALIZED) {
                        if (track.playState != AudioTrack.PLAYSTATE_PLAYING) {
                            try { track.play() } catch (e: Exception) {}
                        }
                        track.setVolume(1.0f)
                        track.write(pcm, 0, pcm.size, AudioTrack.WRITE_BLOCKING)
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

        NativeAudioEngine.nativeGetSpectrum(tempSpectrumOut)
        onPeakListener?.invoke(instantPeakL, instantPeakR, bitMask, tempSpectrumOut)
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