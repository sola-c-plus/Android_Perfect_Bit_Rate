#include <jni.h>
#include "aaudio_engine.h"

static AAudioEngine* g_engine = nullptr;

extern "C" {

JNIEXPORT void JNICALL
Java_com_example_perfectbitrate_NativeAudioEngine_nativeInit(JNIEnv *env, jobject thiz) {
    if (!g_engine) {
        g_engine = new AAudioEngine();
    }
}

JNIEXPORT jint JNICALL
Java_com_example_perfectbitrate_NativeAudioEngine_nativeOpen(
        JNIEnv *env, jobject thiz,
        jint sample_rate, jint channel_count, jint encoding, jint device_id) {
    if (!g_engine) return 0;

    aaudio_format_t format = AAUDIO_FORMAT_PCM_I16;
    if (encoding == 4) {
        format = AAUDIO_FORMAT_PCM_FLOAT;
    } else if (encoding == 2) {
        format = AAUDIO_FORMAT_PCM_I16;
    }

    return g_engine->openStream(sample_rate, channel_count, format, device_id);
}

JNIEXPORT jboolean JNICALL
Java_com_example_perfectbitrate_NativeAudioEngine_nativeStart(JNIEnv *env, jobject thiz) {
    return g_engine ? g_engine->start() : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_example_perfectbitrate_NativeAudioEngine_nativeStop(JNIEnv *env, jobject thiz) {
    return g_engine ? g_engine->stop() : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_example_perfectbitrate_NativeAudioEngine_nativeFlush(JNIEnv *env, jobject thiz) {
    if (g_engine) g_engine->flush();
}

JNIEXPORT void JNICALL
Java_com_example_perfectbitrate_NativeAudioEngine_nativeClose(JNIEnv *env, jobject thiz) {
    if (g_engine) g_engine->closeStream();
}

JNIEXPORT jint JNICALL
Java_com_example_perfectbitrate_NativeAudioEngine_nativeWriteDirect(
        JNIEnv *env, jobject thiz,
        jobject byte_buffer, jint offset, jint length) {
    if (!g_engine) return 0;
    auto* bufferAddress = static_cast<uint8_t*>(env->GetDirectBufferAddress(byte_buffer));
    if (!bufferAddress) return 0;
    return static_cast<jint>(g_engine->write(bufferAddress + offset, length));
}

}