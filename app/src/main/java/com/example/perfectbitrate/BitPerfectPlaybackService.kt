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
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.media.app.NotificationCompat.MediaStyle
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.locks.ReentrantLock
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max

class BitPerfectPlaybackService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null
    var audioTrack: AudioTrack? = null
    private lateinit var audioManager: AudioManager
    var currentSampleRate = 48000
    var currentBitMode = "16bit"
    var activeOutputDevice: AudioDeviceInfo? = null
    private val audioLock = ReentrantLock()

    private val trackExecutor = Executors.newSingleThreadExecutor()

    val pcmQueue = LinkedBlockingQueue<ByteArray>(60)
    @Volatile private var isRunning = false
    private var playbackThread: Thread? = null

    var onPeakListener: ((Float, Float, Int) -> Unit)? = null
    var onDeviceDisconnectedListener: (() -> Unit)? = null

    var isVolumeLocked = false
        set(value) {
            field = value
            if (value) {
                lockSystemVolumeToMax()
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
            if (isVolumeLocked && isUsbDevice(activeOutputDevice)) {
                lockSystemVolumeToMax()
            }
        }
    }

    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                Log.w("BitPerfect", "★ Audio output disconnected! Resetting stream.")
                isVolumeLocked = false
                muteVolumeToZero()
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

        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PerfectBitRate::ServiceWakeLock")
        wakeLock?.acquire()

        // ★ Android 14 (API 34) RECEIVER_EXPORTED 安全登録
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
            Log.i("BitPerfect", "★ System volume set to 0.")
        } catch (e: Exception) {
            Log.e("BitPerfect", "Volume zero error", e)
        }
    }

    fun pushPcm(pcmBytes: ByteArray, sampleRate: Int, bitMode: String) {
        isCurrentlyPlaying = true

        val needsRecreate = (sampleRate != currentSampleRate || 
                             bitMode != currentBitMode || 
                             audioTrack == null || 
                             audioTrack?.state != AudioTrack.STATE_INITIALIZED)

        if (needsRecreate) {
            currentSampleRate = sampleRate
            currentBitMode = bitMode
            trackExecutor.execute {
                initAudioTrack(currentBitMode, currentSampleRate, activeOutputDevice)
            }
        }

        if (!pcmQueue.offer(pcmBytes)) {
            pcmQueue.poll()
            pcmQueue.offer(pcmBytes)
        }
    }

    fun resetBuffer() {
        pcmQueue.clear()
    }

    fun setOutputDevice(device: AudioDeviceInfo?) {
        val changed = (activeOutputDevice?.id != device?.id)
        activeOutputDevice = device

        if (device == null) {
            isVolumeLocked = false
            muteVolumeToZero()
        }

        if (changed || audioTrack == null || audioTrack?.state != AudioTrack.STATE_INITIALIZED) {
            trackExecutor.execute {
                initAudioTrack(currentBitMode, currentSampleRate, device)
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
        pcmQueue.clear()
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
            } finally {
                audioLock.unlock()
            }
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
            trackExecutor.execute {
                audioLock.lock()
                try {
                    if (audioTrack == null || audioTrack?.state != AudioTrack.STATE_INITIALIZED) {
                        initAudioTrack(currentBitMode, currentSampleRate, activeOutputDevice)
                    }
                } finally {
                    audioLock.unlock()
                }
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
            "32bit" -> "32bit"
            "24bit" -> "24bit"
            else -> "16bit"
        }

        val deviceLabel = when {
            isUsbDevice(activeOutputDevice) -> "DIRECT"
            isBluetoothDevice(activeOutputDevice) -> "BLUETOOTH"
            else -> "SPEAKER"
        }

        val notification = NotificationCompat.Builder(this, "bitperfect_service_channel")
            .setContentTitle(currentTitle)
            .setContentText("$currentArtist | $currentCodec")
            .setSubText("${currentSampleRate}Hz $bitStr $deviceLabel")
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

        // ★ Android 14+ FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK 指定
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1001, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(1001, notification)
        }
    }

    fun initAudioTrack(bitMode: String, sampleRate: Int = currentSampleRate, targetDevice: AudioDeviceInfo? = null) {
        audioLock.lock()
        try {
            val oldTrack = audioTrack
            audioTrack = null

            oldTrack?.let {
                try {
                    it.pause()
                    it.flush()
                    it.stop()
                    it.release()
                } catch (e: Exception) {}
            }

            currentBitMode = bitMode
            currentSampleRate = sampleRate
            activeOutputDevice = targetDevice

            var encoding = when (bitMode) {
                "32bit" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    AudioFormat.ENCODING_PCM_32BIT
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    AudioFormat.ENCODING_PCM_24BIT_PACKED
                } else {
                    AudioFormat.ENCODING_PCM_16BIT
                }
                "24bit" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    AudioFormat.ENCODING_PCM_24BIT_PACKED
                } else {
                    AudioFormat.ENCODING_PCM_16BIT
                }
                else -> AudioFormat.ENCODING_PCM_16BIT
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && isUsbDevice(targetDevice)) {
                try {
                    val mixers = audioManager.getSupportedMixerAttributes(targetDevice!!)
                    var matched = mixers.firstOrNull {
                        it.format.sampleRate == sampleRate && it.format.encoding == encoding
                    }

                    if (matched == null) {
                        matched = mixers.firstOrNull {
                            it.format.sampleRate == sampleRate && it.format.encoding == AudioFormat.ENCODING_PCM_24BIT_PACKED
                        } ?: mixers.firstOrNull {
                            it.format.sampleRate == sampleRate && it.format.encoding == AudioFormat.ENCODING_PCM_16BIT
                        } ?: mixers.firstOrNull {
                            it.format.sampleRate == sampleRate
                        }
                        if (matched != null) {
                            encoding = matched.format.encoding
                        }
                    }

                    val finalMixer = matched ?: AudioMixerAttributes.Builder(
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
                    audioManager.setPreferredMixerAttributes(mediaAttr, targetDevice, finalMixer)
                } catch (e: Exception) {
                    Log.e("BitPerfect", "Mixer attribute set error", e)
                }
            }

            val bytesPerSample = when (encoding) {
                AudioFormat.ENCODING_PCM_32BIT -> 4
                AudioFormat.ENCODING_PCM_24BIT_PACKED -> 3
                else -> 2
            }

            val minBuf = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_STEREO, encoding)
            val desiredBuf = sampleRate * 2 * bytesPerSample / 4 // 250ms
            val bufferSize = max(minBuf * 4, desiredBuf)

            val newTrack = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(encoding)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .build()
                )
                .setBufferSizeInBytes(bufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

            targetDevice?.let {
                newTrack.setPreferredDevice(it)
            }

            if (isVolumeLocked && isUsbDevice(targetDevice)) {
                newTrack.setVolume(1.0f)
                lockSystemVolumeToMax()
            }

            newTrack.play()
            audioTrack = newTrack

            Log.i("BitPerfect", "★ AudioTrack Started: ${sampleRate}Hz, Encoding=$encoding, Buffer=$bufferSize -> ${targetDevice?.productName ?: "Default"}")
        } catch (e: Exception) {
            Log.e("BitPerfect", "AudioTrack init error", e)
        } finally {
            audioLock.unlock()
        }
    }

    private fun startPlaybackLoop() {
        isRunning = true
        playbackThread = Thread {
            while (isRunning) {
                try {
                    val pcm = pcmQueue.take()

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
                        if (isVolumeLocked && isUsbDevice(activeOutputDevice)) {
                            track.setVolume(1.0f)
                        }
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

    private fun safeAbs(value: Int): Long {
        return abs(value.toLong())
    }

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
                    bitMask = bitMask or rawL or rawR
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
                    bitMask = bitMask or (rawL and 0xFFFFFF) or (rawR and 0xFFFFFF)
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
                    bitMask = bitMask or (rawL and 0xFFFF) or (rawR and 0xFFFF)
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