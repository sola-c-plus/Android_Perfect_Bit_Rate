package com.example.perfectbitrate

import java.nio.ByteBuffer

object NativeAudioEngine {
    init {
        System.loadLibrary("native_audio_engine")
    }

    external fun nativeInit()
    // 戻り値: 0=失敗, 1=EXCLUSIVE(排他), 2=SHARED(共有)
    external fun nativeOpen(sampleRate: Int, channelCount: Int, encoding: Int, deviceId: Int): Int
    external fun nativeStart(): Boolean
    external fun nativeStop(): Boolean
    external fun nativeFlush()
    external fun nativeClose()
    external fun nativeWriteDirect(byteBuffer: ByteBuffer, offset: Int, length: Int): Int
}