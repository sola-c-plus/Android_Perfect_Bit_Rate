package com.example.perfectbitrate

import java.nio.ByteBuffer

object NativeAudioEngine {
    init {
        System.loadLibrary("native_audio_engine")
    }

    external fun nativeInit()
    external fun nativeOpen(sampleRate: Int, channelCount: Int, encoding: Int, deviceId: Int): Boolean
    external fun nativeStart(): Boolean
    external fun nativeStop(): Boolean
    external fun nativeFlush()
    external fun nativeClose()
    external fun nativeWriteDirect(byteBuffer: ByteBuffer, offset: Int, length: Int): Int
}