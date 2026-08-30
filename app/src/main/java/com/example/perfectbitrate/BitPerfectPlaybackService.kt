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
    private var targetDacDevice: AudioDeviceInfo? = null
    private val audioLock = ReentrantLock()

    private var originalSystemVolume = -1

    val pcmQueue = LinkedBlockingQueue<ByteArray>(20)
    @Volatile private var isRunning = false
    private var playbackThread: Thread? = null

    var onPeakListener: ((Float, Float, Int) -> Unit)? = null

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
            // DACが接続されている時だけ0dBを維持
            if (isVolumeLocked && targetDacDevice != null) {
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
            // DACが接続されている場合のみ最大化
            if (targetDacDevice != null) {
                val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, maxVol, 0)
                audioTrack?.setVolume(1.0f)
            }
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

    fun pushPcm(pcmBytes: ByteArray, sampleRate: Int, bitMode: String) {
        if (!isCurrentlyPlaying) {
            isCurrentlyPlaying = true
        }

        val needsRecreate = (sampleRate != currentSampleRate || 
                             bitMode != currentBitMode || 
                             audioTrack == null || 
                             audioTrack?.state != AudioTrack.STATE_INITIALIZED)

        if (needsRecreate) {
            currentSampleRate = sampleRate
            currentBitMode = bitMode
            pcmQueue.clear()
            initAudioTrack(currentBitMode, currentSampleRate, targetDacDevice)
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
        
        // ★重要: DACが抜けた場合は即座に0dBロックを解除し、内蔵スピーカーの音量を元に戻す
        if (device == null && isVolumeLocked) {
            isVolumeLocked = false
            restoreOriginalVolume()
            Log.i("BitPerfect", "DAC disconnected: Auto-disabled 0dB and restored speaker volume.")
        }

        if (isCurrentlyPlaying) {
            initAudioTrack(currentBitMode, currentSampleRate, device)
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
                if (audioTrack == null || audioTrack?.state != AudioTrack.STATE_INITIALIZED) {
                    initAudioTrack(currentBitMode, currentSampleRate, targetDacDevice)
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

        val notification = NotificationCompat.Builder(this, "bitperfect_service_channel")
            .setContentTitle(currentTitle)
            .setContentText("$currentArtist | $currentCodec")
            .setSubText("${currentSampleRate}Hz $bitStr DIRECT")
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

    fun initAudioTrack(bitMode: String, sampleRate: Int = currentSampleRate, targetDevice: AudioDeviceInfo? = null) {
        audioLock.lock()
        try {
            audioTrack?.let {
                try {
                    it.pause()
                    it.flush()
                    it.stop()
                    it.release()
                } catch (e: Exception) {}
            }
            audioTrack = null

            currentBitMode = bitMode
            currentSampleRate = sampleRate

            val encoding = when (bitMode) {
                "32bit" -> AudioFormat.ENCODING_PCM_FLOAT
                "24bit" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    AudioFormat.ENCODING_PCM_24BIT_PACKED
                } else {
                    AudioFormat.ENCODING_PCM_16BIT
                }
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
                    Log.i("BitPerfect", "★ Mixer switch to $sampleRate Hz: $success")
                } catch (e: Exception) {
                    Log.e("BitPerfect", "Mixer attribute set error", e)
                }
            }

            val bytesPerSample = when (bitMode) {
                "32bit" -> 4
                "24bit" -> 3
                else -> 2
            }
            var minBuf = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_STEREO, encoding)
            if (minBuf <= 0) {
                minBuf = sampleRate * 2 * bytesPerSample / 5
            }

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

            targetDevice?.let {
                audioTrack?.setPreferredDevice(it)
            }

            // DAC接続時のみ0dB音量を反映
            if (isVolumeLocked && targetDevice != null) {
                audioTrack?.setVolume(1.0f)
                lockSystemVolumeToMax()
            }

            audioTrack?.play()
            Log.i("BitPerfect", "${sampleRate}Hz DIRECT AudioTrack Opened ($bitMode)")
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

                    audioLock.lock()
                    try {
                        audioTrack?.let { track ->
                            if (track.state == AudioTrack.STATE_INITIALIZED && isCurrentlyPlaying) {
                                if (track.playState != AudioTrack.PLAYSTATE_PLAYING) {
                                    track.play()
                                }
                                if (isVolumeLocked && targetDacDevice != null) {
                                    track.setVolume(1.0f)
                                }
                                track.write(pcm, 0, pcm.size, AudioTrack.WRITE_BLOCKING)
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

    private fun analyzeAndDispatchPeak(pcmBytes: ByteArray, bitMode: String) {
        val buffer = ByteBuffer.wrap(pcmBytes).order(ByteOrder.LITTLE_ENDIAN)
        var instantPeakL = -60f
        var instantPeakR = -60f
        var bitMask = 0

        when (bitMode) {
            "32bit" -> {
                var maxL = 0f
                var maxR = 0f
                while (buffer.remaining() >= 8) {
                    val sL = abs(buffer.float)
                    val sR = abs(buffer.float)
                    if (sL > maxL) maxL = sL
                    if (sR > maxR) maxR = sR
                }
                instantPeakL = if (maxL > 0f) 20 * log10(maxL) else -60f
                instantPeakR = if (maxR > 0f) 20 * log10(maxR) else -60f
            }
            "24bit" -> {
                var maxL = 0
                var maxR = 0
                while (buffer.remaining() >= 6) {
                    val b0L = buffer.get().toInt() and 0xFF
                    val b1L = buffer.get().toInt() and 0xFF
                    val b2L = buffer.get().toInt()
                    val valL = abs((b2L shl 16) or (b1L shl 8) or b0L)

                    val b0R = buffer.get().toInt() and 0xFF
                    val b1R = buffer.get().toInt() and 0xFF
                    val b2R = buffer.get().toInt()
                    val valR = abs((b2R shl 16) or (b1R shl 8) or b0R)

                    if (valL > maxL) maxL = valL
                    if (valR > maxR) maxR = valR
                    bitMask = bitMask or (valL and 0xFFFFFF) or (valR and 0xFFFFFF)
                }
                instantPeakL = if (maxL > 0) 20 * log10(maxL / 8388607.0f) else -60f
                instantPeakR = if (maxR > 0) 20 * log10(maxR / 8388607.0f) else -60f
            }
            else -> {
                var maxL = 0
                var maxR = 0
                while (buffer.remaining() >= 4) {
                    val valL = abs(buffer.short.toInt())
                    val valR = abs(buffer.short.toInt())
                    if (valL > maxL) maxL = valL
                    if (valR > maxR) maxR = valR
                    bitMask = bitMask or (valL and 0xFFFF) or (valR and 0xFFFF)
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
        } catch (e: Exception) {}

        // ★アプリ終了時も確実に元の音量へ復帰
        restoreOriginalVolume()

        try {
            mediaSession.isActive = false
            mediaSession.release()
        } catch (e: Exception) {}

        audioLock.lock()
        try {
            audioTrack?.stop()
            audioTrack?.release()
            audioTrack = null
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