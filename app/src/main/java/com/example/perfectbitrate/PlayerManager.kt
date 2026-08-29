package com.example.perfectbitrate

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.MediaCodecAudioRenderer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.source.ProgressiveMediaSource

class PlayerManager(
    private val context: Context,
    private val onRateChanged: (Int, Boolean) -> Unit
) {
    private var exoPlayer: ExoPlayer? = null

    fun initPlayer() {
        val renderersFactory = object : DefaultRenderersFactory(context) {
            override fun buildAudioRenderers(
                context: Context,
                extensionRendererMode: Int,
                mediaCodecSelector: MediaCodecSelector,
                enableDecoderFallback: Boolean,
                audioSink: AudioSink,
                eventHandler: android.os.Handler,
                eventListener: androidx.media3.exoplayer.audio.AudioRendererEventListener,
                out: java.util.ArrayList<androidx.media3.exoplayer.Renderer>
            ) {
                // 最新の DirectPcmAudioSink に合わせて初期化
                val directSink = DirectPcmAudioSink(context, onRateChanged)
                out.add(
                    MediaCodecAudioRenderer(
                        context,
                        mediaCodecSelector,
                        enableDecoderFallback,
                        eventHandler,
                        eventListener,
                        directSink
                    )
                )
            }
        }
        exoPlayer = ExoPlayer.Builder(context, renderersFactory).build()
    }

    fun playStream(url: String) {
        val dataSourceFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(YouTubeStreamHelper.USER_AGENT)
            .setConnectTimeoutMs(10000)
            .setReadTimeoutMs(10000)

        val mediaSource = ProgressiveMediaSource.Factory(dataSourceFactory)
            .createMediaSource(MediaItem.fromUri(url))

        exoPlayer?.setMediaSource(mediaSource)
        exoPlayer?.prepare()
        exoPlayer?.play()
    }

    fun stop() {
        exoPlayer?.stop()
    }

    fun release() {
        exoPlayer?.release()
        exoPlayer = null
    }
}