package com.example.perfectbitrate

import java.nio.ByteBuffer

object NativeAudioEngine {
    init {
        try {
            System.loadLibrary("native_audio_engine")
        } catch (e: UnsatisfiedLinkError) {
            e.printStackTrace()
        }
    }

    external fun nativeInit()
    // 戻り値: 0=失敗, 1=EXCLUSIVE(排他), 2=SHARED(共有)
    external fun nativeOpen(sampleRate: Int, channelCount: Int, encoding: Int, deviceId: Int): Int
    external fun nativeStart(): Boolean
    external fun nativeStop(): Boolean
    external fun nativeFlush()
    external fun nativeClose()
    external fun nativeWriteByteArray(byteArray: ByteArray, offset: Int, length: Int): Int
    external fun nativeWriteDirect(byteBuffer: ByteBuffer, offset: Int, length: Int): Int
}