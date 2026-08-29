package com.example.perfectbitrate

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.util.Log
import androidx.media3.common.AudioAttributes as Media3AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.PlaybackParameters
import androidx.media3.exoplayer.audio.AudioSink
import java.nio.ByteBuffer

class DirectPcmAudioSink(
    private val context: Context,
    private val onRateChanged: (Int, Boolean) -> Unit
) : AudioSink {

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var listener: AudioSink.Listener? = null
    private var currentSampleRate = 0
    private var currentEncoding = AudioFormat.ENCODING_PCM_16BIT
    private var audioTrack: AudioTrack? = null
    private var isPlaying = false
    private var media3AudioAttributes: Media3AudioAttributes = Media3AudioAttributes.DEFAULT

    override fun setListener(listener: AudioSink.Listener) {
        this.listener = listener
    }

    override fun supportsFormat(format: Format): Boolean = true
    override fun getFormatSupport(format: Format): Int = AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY

    override fun configure(
        inputFormat: Format,
        specifiedBufferSize: Int,
        outputChannels: IntArray?
    ) {
        val sampleRate = inputFormat.sampleRate
        val encoding = when (inputFormat.pcmEncoding) {
            C.ENCODING_PCM_FLOAT -> AudioFormat.ENCODING_PCM_FLOAT
            else -> AudioFormat.ENCODING_PCM_16BIT
        }

        if (currentSampleRate != sampleRate || currentEncoding != encoding || audioTrack == null) {
            currentSampleRate = sampleRate
            currentEncoding = encoding
            setupHardwareAndAudioTrack(sampleRate, encoding)
        }
    }

    private fun setupHardwareAndAudioTrack(sampleRate: Int, encoding: Int) {
        try {
            audioTrack?.stop()
            audioTrack?.release()
        } catch (e: Exception) {}
        audioTrack = null

        val usbDevice = getUsbAudioDevice()
        var lockSuccess = false

        // 1. RP2350 のハードウェアクロックを音源レート (44.1k / 48k) に切り替える
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && usbDevice != null) {
            try {
                val mixers = audioManager.getSupportedMixerAttributes(usbDevice)
                val matched = mixers.firstOrNull {
                    it.format.sampleRate == sampleRate && it.format.encoding == encoding
                } ?: mixers.firstOrNull {
                    it.format.sampleRate == sampleRate
                }

                if (matched != null) {
                    val mediaAttr = AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()

                    lockSuccess = audioManager.setPreferredMixerAttributes(mediaAttr, usbDevice, matched)
                    Log.i("BitPerfectSink", "★ Mixer switch to $sampleRate Hz: $lockSuccess")
                }
            } catch (e: Exception) {
                Log.e("BitPerfectSink", "Mixer error: ${e.message}")
            }
        }

        onRateChanged(sampleRate, lockSuccess)

        // 2. Direct AudioTrack を生成
        val playbackAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()

        val pcmFormat = AudioFormat.Builder()
            .setEncoding(encoding)
            .setSampleRate(sampleRate)
            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
            .build()

        val minBuf = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_STEREO, encoding)
        val bufferSize = if (minBuf > 0) minBuf * 4 else 8192

        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(playbackAttributes)
            .setAudioFormat(pcmFormat)
            .setBufferSizeInBytes(bufferSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        if (isPlaying) {
            audioTrack?.play()
        }
    }

    override fun play() {
        isPlaying = true
        audioTrack?.play()
    }

    override fun handleBuffer(
        buffer: ByteBuffer,
        presentationTimeUs: Long,
        encodedAccessUnitCount: Int
    ): Boolean {
        if (!buffer.hasRemaining()) return true
        val track = audioTrack ?: return false

        val remaining = buffer.remaining()
        val written = if (buffer.isDirect) {
            track.write(buffer, remaining, AudioTrack.WRITE_BLOCKING)
        } else {
            val tempArray = ByteArray(remaining)
            val pos = buffer.position()
            buffer.get(tempArray)
            val w = track.write(tempArray, 0, remaining)
            if (w < remaining) buffer.position(pos + w)
            w
        }

        return written >= 0
    }

    override fun pause() {
        isPlaying = false
        audioTrack?.pause()
    }

    override fun flush() {
        audioTrack?.flush()
    }

    override fun reset() {
        flush()
        try {
            audioTrack?.stop()
            audioTrack?.release()
        } catch (e: Exception) {}
        audioTrack = null
        currentSampleRate = 0
    }

    private fun getUsbAudioDevice(): AudioDeviceInfo? {
        val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        for (device in devices) {
            if (device.type == AudioDeviceInfo.TYPE_USB_DEVICE ||
                device.type == AudioDeviceInfo.TYPE_USB_HEADSET
            ) {
                return device
            }
        }
        return null
    }

    override fun getCurrentPositionUs(sourceEnded: Boolean): Long = 0L
    override fun isEnded(): Boolean = false
    override fun hasPendingData(): Boolean = false
    override fun setPlaybackParameters(playbackParameters: PlaybackParameters) {}
    override fun getPlaybackParameters(): PlaybackParameters = PlaybackParameters.DEFAULT
    override fun setSkipSilenceEnabled(skipSilenceEnabled: Boolean) {}
    override fun getSkipSilenceEnabled(): Boolean = false
    override fun setAudioAttributes(audioAttributes: Media3AudioAttributes) { this.media3AudioAttributes = audioAttributes }
    override fun getAudioAttributes(): Media3AudioAttributes = this.media3AudioAttributes
    override fun setAudioSessionId(audioSessionId: Int) {}
    override fun setAuxEffectInfo(auxEffectInfo: androidx.media3.common.AuxEffectInfo) {}
    override fun enableTunnelingV21() {}
    override fun disableTunneling() {}
    override fun setVolume(volume: Float) {}
    override fun handleDiscontinuity() {}
    override fun playToEndOfStream() {}
}