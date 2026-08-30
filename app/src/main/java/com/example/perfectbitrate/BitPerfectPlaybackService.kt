package com.example.perfectbitrate

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.media.app.NotificationCompat.MediaStyle
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.locks.ReentrantLock

class BitPerfectPlaybackService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null
    var audioTrack: AudioTrack? = null
    private lateinit var audioManager: AudioManager
    var currentSampleRate = 48000
    var currentBitMode = "16bit"
    var targetBitMode = "16bit"
    private var targetDacDevice: AudioDeviceInfo? = null
    private val audioLock = ReentrantLock()

    private var originalSystemVolume = -1

    val pcmQueue = LinkedBlockingQueue<ByteArray>(500)
    @Volatile private var isRunning = false
    private var playbackThread: Thread? = null

    private val isAndroid14Plus = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE

    var isVolumeLocked = false
        set(value) {
            field = value
            if (value) {
                lockSystemVolumeToMax()
            } else {
                restoreOriginalVolume()
            }
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

    private val volumeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (isVolumeLocked) {
                lockSystemVolumeToMax()
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

        try {
            NativeAudioEngine.nativeInit()
        } catch (e: Exception) {
            Log.e("BitPerfect", "Native init error", e)
        }

        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        originalSystemVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)

        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PerfectBitRate::ServiceWakeLock")
        wakeLock?.acquire()

        try {
            val filter = IntentFilter("android.media.VOLUME_CHANGED_ACTION")
            registerReceiver(volumeReceiver, filter)
        } catch (e: Exception) {}

        createNotificationChannel()
        setupMediaSession()

        startPlaybackLoop()
        updateNotification()
    }

    fun lockSystemVolumeToMax() {
        try {
            val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, maxVol, 0)
            audioTrack?.setVolume(1.0f)
        } catch (e: Exception) {
            Log.e("BitPerfect", "Volume lock error", e)
        }
    }

    fun restoreOriginalVolume() {
        try {
            if (originalSystemVolume >= 0) {
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, originalSystemVolume, 0)
            }
        } catch (e: Exception) {
            Log.e("BitPerfect", "Volume restore error", e)
        }
    }

    fun setTargetMode(mode: String) {
        targetBitMode = mode
        pcmQueue.clear()
    }

    fun pushPcm(pcmBytes: ByteArray, sampleRate: Int, bitMode: String) {
        if (!isCurrentlyPlaying) {
            isCurrentlyPlaying = true
        }

        // 目的のビットモードと異なる古い過渡パケットは破棄
        if (targetBitMode.isNotEmpty() && bitMode != targetBitMode) {
            return
        }

        val rateOrModeChanged = (sampleRate != currentSampleRate || bitMode != currentBitMode || 
            (isAndroid14Plus && audioTrack == null))

        if (rateOrModeChanged) {
            currentSampleRate = sampleRate
            currentBitMode = bitMode
            targetBitMode = bitMode
            pcmQueue.clear()
            initAudioEngine(currentBitMode, currentSampleRate, targetDacDevice, isRateShift = true)
        }

        if (pcmQueue.remainingCapacity() > 0) {
            pcmQueue.offer(pcmBytes)
        }
    }

    fun resetBuffer() {
        pcmQueue.clear()
    }

    fun setDacDevice(device: AudioDeviceInfo?) {
        targetDacDevice = device
        if (isCurrentlyPlaying) {
            initAudioEngine(currentBitMode, currentSampleRate, device, isRateShift = false)
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
        mediaSession = MediaSessionCompat(this, "BitPerfectMediaSession").apply {
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
    }

    fun updateProgress(currentMs: Long, durationMs: Long, isPlaying: Boolean) {
        currentPosition = currentMs
        currentDuration = durationMs

        val metaBuilder = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, currentTitle)
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, currentArtist)
            .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, durationMs)

        if (currentArtwork != null) {
            metaBuilder.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, currentArtwork)
        }

        mediaSession.setMetadata(metaBuilder.build())
    }

    fun forceCloseDacStream() {
        isCurrentlyPlaying = false
        pcmQueue.clear()
        audioLock.lock()
        try {
            if (isAndroid14Plus) {
                audioTrack?.let { track ->
                    try {
                        track.pause()
                        track.flush()
                        track.stop()
                        track.release()
                    } catch (e: Exception) {}
                }
                audioTrack = null
            } else {
                NativeAudioEngine.nativeStop()
                NativeAudioEngine.nativeClose()
            }
        } finally {
            audioLock.unlock()
        }
        updateNotification()
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
                .setState(state, position, if (isPlaying) 1.0f else 0.0f)
                .build()
        )

        if (!isPlaying) {
            forceCloseDacStream()
        } else {
            audioLock.lock()
            try {
                if (isAndroid14Plus) {
                    if (audioTrack == null) {
                        initAudioEngine(currentBitMode, currentSampleRate, targetDacDevice, isRateShift = false)
                    }
                } else {
                    initAudioEngine(currentBitMode, currentSampleRate, targetDacDevice, isRateShift = false)
                }
            } finally {
                audioLock.unlock()
            }
        }

        updateNotification()
    }

    fun updateCodec(codec: String) {
        currentCodec = codec
        updateNotification()
    }

    fun updateMetadata(title: String, artist: String, artworkUrl: String) {
        currentTitle = title
        currentArtist = artist

        val metaBuilder = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title)
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, artist)
            .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, currentDuration)

        mediaSession.setMetadata(metaBuilder.build())
        updateNotification()

        if (artworkUrl.isNotEmpty()) {
            imageExecutor.execute {
                try {
                    val stream = URL(artworkUrl).openStream()
                    currentArtwork = BitmapFactory.decodeStream(stream)
                    metaBuilder.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, currentArtwork)
                    mediaSession.setMetadata(metaBuilder.build())
                    updateNotification()
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

        val playPauseIcon = if (isCurrentlyPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play

        val bitStr = when (currentBitMode) {
            "32bit" -> "32bit Float"
            "24bit" -> "24bit"
            else -> "16bit"
        }

        val modeLabel = if (isAndroid14Plus) "DIRECT" else "AAUDIO EXCLUSIVE"

        val notification = NotificationCompat.Builder(this, "bitperfect_service_channel")
            .setContentTitle(currentTitle)
            .setContentText("$currentArtist | $currentCodec")
            .setSubText("${currentSampleRate}Hz $bitStr $modeLabel")
            .setLargeIcon(currentArtwork)
            .setSmallIcon(android.R.drawable.ic_media_play)
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
            .build()

        startForeground(1001, notification)
    }

    fun initAudioTrack(
        bitMode: String = currentBitMode,
        sampleRate: Int = currentSampleRate,
        targetDevice: AudioDeviceInfo? = targetDacDevice
    ) {
        initAudioEngine(bitMode, sampleRate, targetDevice, isRateShift = false)
    }

    fun initAudioTrack(bitMode: String, targetDevice: AudioDeviceInfo?) {
        initAudioEngine(bitMode, currentSampleRate, targetDevice, isRateShift = false)
    }

    fun initAudioEngine(
        bitMode: String,
        sampleRate: Int = currentSampleRate,
        targetDevice: AudioDeviceInfo? = null,
        isRateShift: Boolean = false
    ) {
        audioLock.lock()
        try {
            currentBitMode = bitMode
            targetBitMode = bitMode
            currentSampleRate = sampleRate

            val bytesPerSample = when (bitMode) {
                "32bit" -> 4
                "24bit" -> 3
                else -> 2
            }

            if (isAndroid14Plus) {
                // ==================== Android 14+ (AudioTrack + preferredMixer) ====================
                audioTrack?.let {
                    try {
                        it.pause()
                        it.flush()
                        it.stop()
                        it.release()
                    } catch (e: Exception) {}
                }
                audioTrack = null

                val encoding = when (bitMode) {
                    "32bit" -> AudioFormat.ENCODING_PCM_FLOAT
                    "24bit" -> AudioFormat.ENCODING_PCM_24BIT_PACKED
                    else -> AudioFormat.ENCODING_PCM_16BIT
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && targetDevice != null) {
                    try {
                        val mixers = audioManager.getSupportedMixerAttributes(targetDevice)
                        val matched = mixers.firstOrNull {
                            it.format.sampleRate == sampleRate && it.format.encoding == encoding
                        } ?: mixers.firstOrNull {
                            it.format.sampleRate == sampleRate
                        } ?: AudioMixerAttributes.Builder(
                            AudioFormat.Builder()
                                .setSampleRate(sampleRate)
                                .setEncoding(encoding)
                                .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                                .build()
                        ).setMixerBehavior(AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT).build()

                        val mediaAttr = AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build()
                        val success = audioManager.setPreferredMixerAttributes(mediaAttr, targetDevice, matched)
                        Log.i("BitPerfect", "★ [Android 14+] Mixer switch to $sampleRate Hz ($bitMode): $success")
                    } catch (e: Exception) {
                        Log.e("BitPerfect", "Mixer attribute set error", e)
                    }
                }

                var minBuf = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_STEREO, encoding)
                if (minBuf <= 0) minBuf = sampleRate * 2 * bytesPerSample / 5

                audioTrack = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .setFlags(AudioAttributes.FLAG_LOW_LATENCY)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(encoding)
                            .setSampleRate(sampleRate)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                            .build()
                    )
                    .setBufferSizeInBytes(minBuf * 4)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()

                targetDevice?.let { audioTrack?.setPreferredDevice(it) }

                if (isVolumeLocked) {
                    audioTrack?.setVolume(1.0f)
                    lockSystemVolumeToMax()
                }

                audioTrack?.play()

                if (isRateShift) {
                    val preRollBytes = (sampleRate * 2 * bytesPerSample * 120) / 1000
                    audioTrack?.write(ByteArray(preRollBytes), 0, preRollBytes, AudioTrack.WRITE_BLOCKING)
                }

            } else {
                // ==================== Android 13以下 (C++ AAudio 排他モード) ====================
                NativeAudioEngine.nativeClose()

                val encodingCode = when (bitMode) {
                    "32bit" -> 4  // Float
                    "24bit" -> 21 // 24-bit Packed
                    else -> 2     // 16-bit
                }

                val deviceId = targetDevice?.id ?: getUsbAudioDeviceId()
                val resultMode = NativeAudioEngine.nativeOpen(sampleRate, 2, encodingCode, deviceId)
                NativeAudioEngine.nativeStart()

                if (isRateShift) {
                    val preRollBytes = (sampleRate * 2 * bytesPerSample * 120) / 1000
                    NativeAudioEngine.nativeWriteByteArray(ByteArray(preRollBytes), 0, preRollBytes)
                }

                Log.i("BitPerfect", "★ [Android 13-] AAudio Exclusive Opened: Rate=$sampleRate Hz, Mode=$resultMode")
            }
        } catch (e: Exception) {
            Log.e("BitPerfect", "Audio Engine init error", e)
        } finally {
            audioLock.unlock()
        }
    }

    private fun getUsbAudioDeviceId(): Int {
        targetDacDevice?.id?.let { if (it > 0) return it }
        val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        for (device in devices) {
            if (device.type == AudioDeviceInfo.TYPE_USB_DEVICE || device.type == AudioDeviceInfo.TYPE_USB_HEADSET) {
                return device.id
            }
        }
        return 0
    }

    private fun startPlaybackLoop() {
        isRunning = true
        playbackThread = Thread {
            while (isRunning) {
                try {
                    val pcm = pcmQueue.take()

                    audioLock.lock()
                    try {
                        if (isAndroid14Plus) {
                            audioTrack?.let { track ->
                                if (track.state == AudioTrack.STATE_INITIALIZED && isCurrentlyPlaying) {
                                    if (track.playState != AudioTrack.PLAYSTATE_PLAYING) {
                                        track.play()
                                    }
                                    if (isVolumeLocked) {
                                        track.setVolume(1.0f)
                                    }
                                    track.write(pcm, 0, pcm.size, AudioTrack.WRITE_BLOCKING)
                                }
                            }
                        } else {
                            if (isCurrentlyPlaying) {
                                NativeAudioEngine.nativeWriteByteArray(pcm, 0, pcm.size)
                            }
                        }
                    } finally {
                        audioLock.unlock()
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
        } catch (e: Exception) {}

        restoreOriginalVolume()

        try {
            mediaSession.isActive = false
            mediaSession.release()
        } catch (e: Exception) {}

        audioLock.lock()
        try {
            if (isAndroid14Plus) {
                audioTrack?.stop()
                audioTrack?.release()
                audioTrack = null
            } else {
                NativeAudioEngine.nativeStop()
                NativeAudioEngine.nativeClose()
            }
        } catch (e: Exception) {}
        finally {
            audioLock.unlock()
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