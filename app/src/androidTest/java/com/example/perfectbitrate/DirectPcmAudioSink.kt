package com.example.perfectbitrate

import android.media.AudioFormat
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.PlaybackParameters
import androidx.media3.exoplayer.audio.AudioSink
import java.nio.ByteBuffer

class DirectPcmAudioSink(private val targetDeviceId: Int = 0) : AudioSink {

    private var listener: AudioSink.Listener? = null
    private var currentSampleRate = 0
    private var currentChannelCount = 0
    private var currentEncoding = C.ENCODING_PCM_16BIT
    private var isPlaying = false
    private var streamPositionFrames = 0L

    override fun setListener(listener: AudioSink.Listener) {
        this.listener = listener
    }

    override fun supportsFormat(format: Format): Boolean = true

    override fun getFormatSupport(format: Format): Int {
        return AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY
    }

    override fun configure(
        inputFormat: Format,
        specifiedBufferSize: Int,
        outputChannels: IntArray?
    ) {
        val sampleRate = inputFormat.sampleRate
        val channelCount = inputFormat.channelCount
        val encoding = when (inputFormat.pcmEncoding) {
            C.ENCODING_PCM_FLOAT -> AudioFormat.ENCODING_PCM_FLOAT
            else -> AudioFormat.ENCODING_PCM_16BIT
        }

        if (currentSampleRate != sampleRate ||
            currentChannelCount != channelCount ||
            currentEncoding != encoding
        ) {
            currentSampleRate = sampleRate
            currentChannelCount = channelCount
            currentEncoding = encoding

            // レート変更時にストリーム再生成
            NativeAudioEngine.nativeClose()
            NativeAudioEngine.nativeOpen(sampleRate, channelCount, encoding, targetDeviceId)
            if (isPlaying) {
                NativeAudioEngine.nativeStart()
            }
        }
    }

    override fun play() {
        isPlaying = true
        NativeAudioEngine.nativeStart()
    }

    override fun handleBuffer(
        buffer: ByteBuffer,
        presentationTimeUs: Long,
        encodedAccessUnitCount: Int
    ): Boolean {
        if (!buffer.hasRemaining()) return true

        val remaining = buffer.remaining()
        val written = if (buffer.isDirect) {
            NativeAudioEngine.nativeWriteDirect(buffer, buffer.position(), remaining)
        } else {
            val direct = ByteBuffer.allocateDirect(remaining)
            direct.put(buffer)
            direct.flip()
            NativeAudioEngine.nativeWriteDirect(direct, 0, remaining)
        }

        if (written > 0) {
            buffer.position(buffer.position() + written)
            val bytesPerFrame = currentChannelCount * (if (currentEncoding == AudioFormat.ENCODING_PCM_FLOAT) 4 else 2)
            streamPositionFrames += (written / bytesPerFrame)
        }

        return !buffer.hasRemaining()
    }

    override fun getCurrentPositionUs(sourceEnded: Boolean): Long {
        if (currentSampleRate == 0) return 0L
        return (streamPositionFrames * 1_000_000L) / currentSampleRate
    }

    override fun pause() {
        isPlaying = false
        NativeAudioEngine.nativeStop()
    }

    override fun flush() {
        NativeAudioEngine.nativeFlush()
        streamPositionFrames = 0L
    }

    override fun reset() {
        flush()
        NativeAudioEngine.nativeClose()
        currentSampleRate = 0
    }

    override fun isEnded(): Boolean = false
    override fun hasPendingData(): Boolean = false
    override fun setPlaybackParameters(playbackParameters: PlaybackParameters) {}
    override fun getPlaybackParameters(): PlaybackParameters = PlaybackParameters.DEFAULT
    override fun setSkipSilenceEnabled(skipSilenceEnabled: Boolean) {}
    override fun getSkipSilenceEnabled(): Boolean = false
    override fun setAudioAttributes(audioAttributes: androidx.media3.common.AudioAttributes) {}
    override fun setAudioSessionId(audioSessionId: Int) {}
    override fun setAuxEffectInfo(auxEffectInfo: androidx.media3.common.AuxEffectInfo) {}
    override fun enableTunnelingV21() {}
    override fun disableTunneling() {}
    override fun setVolume(volume: Float) {}
    override fun handleDiscontinuity() {}
    override fun playToEndOfStream() {}
}