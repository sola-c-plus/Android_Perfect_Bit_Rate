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
    external fun nativeConfigureUpsampler(factor: Int, sampleRate: Int)
    external fun nativeResetUpsampler()
    external fun nativeSetDirectSource(enabled: Boolean)
    external fun nativeSetDitherMode(mode: Int)
    external fun nativeSetFirFilterType(type: Int)
    external fun nativeSetDcPhaseType(type: Int)
    external fun nativeSetDseeMode(mode: Int)
    external fun nativeSetTransientMode(mode: Int)
    external fun nativeSetEqualizer(enabled: Boolean, gains: FloatArray)
    external fun nativeProcessUpsample(
        inBytes: ByteArray,
        inLength: Int,
        inBitMode: String,
        outBitMode: String,
        factor: Int
    ): ByteArray?

    external fun nativeOpen(sampleRate: Int, channelCount: Int, encoding: Int, deviceId: Int): Int
    external fun nativeStart(): Boolean
    external fun nativeStop(): Boolean
    external fun nativeFlush()
    external fun nativeClose()
    external fun nativeWriteByteArray(byteArray: ByteArray, offset: Int, length: Int): Int
    external fun nativeWriteDirect(byteBuffer: ByteBuffer, offset: Int, length: Int): Int
}