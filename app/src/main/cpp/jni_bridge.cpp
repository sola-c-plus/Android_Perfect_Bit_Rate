#include <jni.h>
#include "aaudio_engine.h"
#include "dsp_upsampler.h"
#include <vector>
#include <string>

static AAudioEngine* g_engine = nullptr;
static DspUpsampler* g_upsampler = nullptr;
static std::vector<uint8_t> g_outDspBuffer;
static int g_currentDitherMode = 1;
static int g_currentFirFilterType = 2; // Minimum Phase Sharp
static int g_currentDcPhaseType = 2;
static int g_currentFreqMode = 1;      // AUTO_AI
static int g_currentTransientMode = 3; // Acoustic
static bool g_isDirectSource = false;
static bool g_isCascadeFir = true;

extern "C" {

JNIEXPORT void JNICALL
Java_com_example_perfectbitrate_NativeAudioEngine_nativeInit(JNIEnv *env, jobject thiz) {
    if (!g_engine) g_engine = new AAudioEngine();
    if (!g_upsampler) {
        g_upsampler = new DspUpsampler();
        g_upsampler->setDirectSource(g_isDirectSource);
        g_upsampler->setCascadeFir(g_isCascadeFir);
        g_upsampler->setDitherMode(static_cast<DitherMode>(g_currentDitherMode));
        g_upsampler->setFirFilterType(static_cast<FirFilterType>(g_currentFirFilterType));
        g_upsampler->setDcPhaseType(static_cast<DcPhaseType>(g_currentDcPhaseType));
        g_upsampler->setFreqMode(static_cast<FreqMode>(g_currentFreqMode));
        g_upsampler->setTransientMode(static_cast<TransientMode>(g_currentTransientMode));
    }
}

JNIEXPORT void JNICALL
Java_com_example_perfectbitrate_NativeAudioEngine_nativeConfigureUpsampler(
        JNIEnv *env, jobject thiz, jint factor, jint sample_rate) {
    if (!g_upsampler) g_upsampler = new DspUpsampler();
    g_upsampler->configure(factor, static_cast<float>(sample_rate));
}

JNIEXPORT void JNICALL
Java_com_example_perfectbitrate_NativeAudioEngine_nativeResetUpsampler(
        JNIEnv *env, jobject thiz) {
    if (g_upsampler) g_upsampler->reset();
}

JNIEXPORT void JNICALL
Java_com_example_perfectbitrate_NativeAudioEngine_nativeSetDirectSource(
        JNIEnv *env, jobject thiz, jboolean enabled) {
    g_isDirectSource = (enabled == JNI_TRUE);
    if (!g_upsampler) g_upsampler = new DspUpsampler();
    g_upsampler->setDirectSource(g_isDirectSource);
}

JNIEXPORT void JNICALL
Java_com_example_perfectbitrate_NativeAudioEngine_nativeSetCascadeFir(
        JNIEnv *env, jobject thiz, jboolean enabled) {
    g_isCascadeFir = (enabled == JNI_TRUE);
    if (!g_upsampler) g_upsampler = new DspUpsampler();
    g_upsampler->setCascadeFir(g_isCascadeFir);
}

JNIEXPORT void JNICALL
Java_com_example_perfectbitrate_NativeAudioEngine_nativeSetDitherMode(
        JNIEnv *env, jobject thiz, jint mode) {
    g_currentDitherMode = mode;
    if (!g_upsampler) g_upsampler = new DspUpsampler();
    g_upsampler->setDitherMode(static_cast<DitherMode>(mode));
}

JNIEXPORT void JNICALL
Java_com_example_perfectbitrate_NativeAudioEngine_nativeSetLrIndependentDither(
        JNIEnv *env, jobject thiz, jboolean enabled) {
    if (!g_upsampler) g_upsampler = new DspUpsampler();
    g_upsampler->setLrIndependentDither(enabled == JNI_TRUE);
}

JNIEXPORT void JNICALL
Java_com_example_perfectbitrate_NativeAudioEngine_nativeSetFirFilterType(
        JNIEnv *env, jobject thiz, jint type) {
    g_currentFirFilterType = type;
    if (!g_upsampler) g_upsampler = new DspUpsampler();
    g_upsampler->setFirFilterType(static_cast<FirFilterType>(type));
}

JNIEXPORT void JNICALL
Java_com_example_perfectbitrate_NativeAudioEngine_nativeSetDcPhaseType(
        JNIEnv *env, jobject thiz, jint type) {
    g_currentDcPhaseType = type;
    if (!g_upsampler) g_upsampler = new DspUpsampler();
    g_upsampler->setDcPhaseType(static_cast<DcPhaseType>(type));
}

// FREQ Engine API
JNIEXPORT void JNICALL
Java_com_example_perfectbitrate_NativeAudioEngine_nativeSetFreqMode(
        JNIEnv *env, jobject thiz, jint mode) {
    g_currentFreqMode = mode;
    if (!g_upsampler) g_upsampler = new DspUpsampler();
    g_upsampler->setFreqMode(static_cast<FreqMode>(mode));
}

JNIEXPORT void JNICALL
Java_com_example_perfectbitrate_NativeAudioEngine_nativeSetFreqCustomParams(
        JNIEnv *env, jobject thiz, jfloat gain, jfloat extractFreq) {
    if (!g_upsampler) g_upsampler = new DspUpsampler();
    g_upsampler->setFreqCustomParams(gain, extractFreq);
}

// DSEE 互換 API
JNIEXPORT void JNICALL
Java_com_example_perfectbitrate_NativeAudioEngine_nativeSetDseeMode(
        JNIEnv *env, jobject thiz, jint mode) {
    Java_com_example_perfectbitrate_NativeAudioEngine_nativeSetFreqMode(env, thiz, mode);
}

JNIEXPORT void JNICALL
Java_com_example_perfectbitrate_NativeAudioEngine_nativeSetDseeCustomParams(
        JNIEnv *env, jobject thiz, jint lpcAlgo, jfloat gain, jfloat extractFreq, jboolean useQmf) {
    Java_com_example_perfectbitrate_NativeAudioEngine_nativeSetFreqCustomParams(env, thiz, gain, extractFreq);
}

// ★ MainActivity から参照される Transient API の完全復元
JNIEXPORT void JNICALL
Java_com_example_perfectbitrate_NativeAudioEngine_nativeSetTransientMode(
        JNIEnv *env, jobject thiz, jint mode) {
    g_currentTransientMode = mode;
    if (!g_upsampler) g_upsampler = new DspUpsampler();
    g_upsampler->setTransientMode(static_cast<TransientMode>(mode));
}

JNIEXPORT void JNICALL
Java_com_example_perfectbitrate_NativeAudioEngine_nativeSetTransientCustomParams(
        JNIEnv *env, jobject thiz, jboolean useGroupDelay, jboolean useLattice) {
    if (!g_upsampler) g_upsampler = new DspUpsampler();
    g_upsampler->setTransientCustomParams(useGroupDelay == JNI_TRUE, useLattice == JNI_TRUE);
}

JNIEXPORT void JNICALL
Java_com_example_perfectbitrate_NativeAudioEngine_nativeSetEqualizer(
        JNIEnv *env, jobject thiz, jboolean enabled, jfloatArray gains) {
    if (!g_upsampler) g_upsampler = new DspUpsampler();
    g_upsampler->getEqualizer().setEnabled(enabled == JNI_TRUE);
    if (gains) {
        jfloat* gainElements = env->GetFloatArrayElements(gains, nullptr);
        if (gainElements) {
            g_upsampler->getEqualizer().setAllGains(gainElements);
            env->ReleaseFloatArrayElements(gains, gainElements, JNI_ABORT);
        }
    }
}

JNIEXPORT void JNICALL
Java_com_example_perfectbitrate_NativeAudioEngine_nativeGetSpectrum(
        JNIEnv *env, jobject thiz, jfloatArray out_array) {
    if (!out_array || !g_upsampler) return;
    jfloat* dst = env->GetFloatArrayElements(out_array, nullptr);
    if (dst) {
        g_upsampler->getSpectrum(dst);
        env->ReleaseFloatArrayElements(out_array, dst, 0);
    }
}

JNIEXPORT jbyteArray JNICALL
Java_com_example_perfectbitrate_NativeAudioEngine_nativeProcessUpsample(
        JNIEnv *env, jobject thiz,
        jbyteArray in_bytes, jint in_length,
        jstring in_bit_mode, jstring out_bit_mode, jint factor) {
    if (!in_bytes || in_length <= 0) return nullptr;
    if (!g_upsampler) g_upsampler = new DspUpsampler();

    const char* inMode = env->GetStringUTFChars(in_bit_mode, nullptr);
    const char* outMode = env->GetStringUTFChars(out_bit_mode, nullptr);

    int effectiveFactor = g_isDirectSource ? 1 : factor;
    if (g_upsampler->getFactor() != effectiveFactor) {
        g_upsampler->configure(effectiveFactor);
    }

    jbyte* src = env->GetByteArrayElements(in_bytes, nullptr);
    if (!src) {
        env->ReleaseStringUTFChars(in_bit_mode, inMode);
        env->ReleaseStringUTFChars(out_bit_mode, outMode);
        return nullptr;
    }

    size_t outSize = g_upsampler->process(
        reinterpret_cast<const uint8_t*>(src),
        static_cast<size_t>(in_length),
        inMode,
        outMode,
        g_outDspBuffer
    );

    env->ReleaseByteArrayElements(in_bytes, src, JNI_ABORT);
    env->ReleaseStringUTFChars(in_bit_mode, inMode);
    env->ReleaseStringUTFChars(out_bit_mode, outMode);

    if (outSize == 0) return nullptr;

    jbyteArray result = env->NewByteArray(static_cast<jsize>(outSize));
    if (result) {
        env->SetByteArrayRegion(result, 0, static_cast<jsize>(outSize), reinterpret_cast<const jbyte*>(g_outDspBuffer.data()));
    }
    return result;
}

JNIEXPORT jint JNICALL
Java_com_example_perfectbitrate_NativeAudioEngine_nativeOpen(
        JNIEnv *env, jobject thiz,
        jint sample_rate, jint channel_count, jint encoding, jint device_id) {
    if (!g_engine) g_engine = new AAudioEngine();
    aaudio_format_t format = AAUDIO_FORMAT_PCM_I16;
    if (encoding == 4) format = AAUDIO_FORMAT_PCM_FLOAT;
    else if (encoding == 2) format = AAUDIO_FORMAT_PCM_I16;
    else if (encoding == 21 || encoding == 3) format = AAUDIO_FORMAT_PCM_I24_PACKED;
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
Java_com_example_perfectbitrate_NativeAudioEngine_nativeWriteByteArray(
        JNIEnv *env, jobject thiz,
        jbyteArray byte_array, jint offset, jint length) {
    if (!g_engine || !byte_array || length <= 0) return 0;
    jbyte* data = env->GetByteArrayElements(byte_array, nullptr);
    if (!data) return 0;
    size_t written = g_engine->write(reinterpret_cast<const uint8_t*>(data + offset), length);
    env->ReleaseByteArrayElements(byte_array, data, JNI_ABORT);
    return static_cast<jint>(written);
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