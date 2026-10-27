#include <jni.h>
#include "dsp_upsampler.h"
#include <vector>
#include <string>
#include <mutex>

static DspUpsampler* g_upsampler = nullptr;
static std::vector<uint8_t> g_outDspBuffer;
static std::mutex g_dspMutex;

static int g_currentDitherMode = 1;
static int g_currentFirFilterType = 2;
static int g_currentDcPhaseType = 2;
static int g_currentFreqMode = 1;
static int g_currentTransientMode = 3;
static bool g_isDirectSource = false;
static bool g_isCascadeFir = true;
static bool g_isRichHarmonics = false;
static float g_currentSampleRate = 48000.0f;

// 再生成時に値を失わないためのキャッシュ（初期値は DspUpsampler のデフォルトと一致させる）
static int g_currentPerfMode = 1;
static bool g_isLrIndependentDither = true;
static float g_currentFreqGain = 0.22f;
static float g_currentFreqExtractFreq = 13000.0f;
static bool g_useGroupDelay = true;
static bool g_useLattice = false;
static bool g_isMsSpatial = false;
static bool g_isDynamicSbr = false;
static int g_currentLpcAlgo = 0;
static bool g_isQmfEnabled = false;
static float g_qmfCrossoverHz = 16900.0f;
static bool g_isEqEnabled = false;
static float g_currentEqGains[10] = {0.0f};
static bool g_isTruePeakGuardEnabled = true;
static float g_truePeakCeilingDb = -1.0f;

extern "C" {

JNIEXPORT void JNICALL
Java_com_example_perfectbitrate_NativeAudioEngine_nativeInit(JNIEnv *env, jobject thiz) {
    std::lock_guard<std::mutex> lock(g_dspMutex);
    if (!g_upsampler) {
        g_upsampler = new DspUpsampler();
        g_upsampler->setDirectSource(g_isDirectSource);
        g_upsampler->setCascadeFir(g_isCascadeFir);
        g_upsampler->setDitherMode(static_cast<DitherMode>(g_currentDitherMode));
        g_upsampler->setFirFilterType(static_cast<FirFilterType>(g_currentFirFilterType));
        g_upsampler->setDcPhaseType(static_cast<DcPhaseType>(g_currentDcPhaseType));
        g_upsampler->setFreqMode(static_cast<FreqMode>(g_currentFreqMode));
        g_upsampler->setTransientMode(static_cast<TransientMode>(g_currentTransientMode));
        g_upsampler->setRichHarmonics(g_isRichHarmonics);
        g_upsampler->setPerformanceMode(static_cast<PerformanceMode>(g_currentPerfMode));
        g_upsampler->setLrIndependentDither(g_isLrIndependentDither);
        g_upsampler->setFreqCustomParams(g_currentFreqGain, g_currentFreqExtractFreq);
        g_upsampler->setTransientCustomParams(g_useGroupDelay, g_useLattice);
        g_upsampler->setMsSpatial(g_isMsSpatial);
        g_upsampler->setDynamicSbr(g_isDynamicSbr);
        g_upsampler->setFreqAdvancedParams(g_currentLpcAlgo, g_isQmfEnabled, static_cast<double>(g_qmfCrossoverHz));
        g_upsampler->getEqualizer().setEnabled(g_isEqEnabled);
        g_upsampler->getEqualizer().setAllGains(g_currentEqGains);
        g_upsampler->setTruePeakGuard(g_isTruePeakGuardEnabled, static_cast<double>(g_truePeakCeilingDb));
    }
}

JNIEXPORT void JNICALL
Java_com_example_perfectbitrate_NativeAudioEngine_nativeConfigureUpsampler(
        JNIEnv *env, jobject thiz, jint factor, jint sample_rate) {
    std::lock_guard<std::mutex> lock(g_dspMutex);
    g_currentSampleRate = static_cast<float>(sample_rate);
    if (!g_upsampler) g_upsampler = new DspUpsampler();
    g_upsampler->configure(factor, g_currentSampleRate);
}

JNIEXPORT void JNICALL
Java_com_example_perfectbitrate_NativeAudioEngine_nativeResetUpsampler(
        JNIEnv *env, jobject thiz) {
    std::lock_guard<std::mutex> lock(g_dspMutex);
    if (g_upsampler) g_upsampler->reset();
}

JNIEXPORT void JNICALL
Java_com_example_perfectbitrate_NativeAudioEngine_nativeSetDirectSource(
        JNIEnv *env, jobject thiz, jboolean enabled) {
    std::lock_guard<std::mutex> lock(g_dspMutex);
    g_isDirectSource = (enabled == JNI_TRUE);
    if (!g_upsampler) g_upsampler = new DspUpsampler();
    g_upsampler->setDirectSource(g_isDirectSource);
}

JNIEXPORT void JNICALL
Java_com_example_perfectbitrate_NativeAudioEngine_nativeSetCascadeFir(
        JNIEnv *env, jobject thiz, jboolean enabled) {
    std::lock_guard<std::mutex> lock(g_dspMutex);
    g_isCascadeFir = (enabled == JNI_TRUE);
    if (!g_upsampler) g_upsampler = new DspUpsampler();
    g_upsampler->setCascadeFir(g_isCascadeFir);
}

JNIEXPORT void JNICALL
Java_com_example_perfectbitrate_NativeAudioEngine_nativeSetDitherMode(
        JNIEnv *env, jobject thiz, jint mode) {
    std::lock_guard<std::mutex> lock(g_dspMutex);
    g_currentDitherMode = mode;
    if (!g_upsampler) g_upsampler = new DspUpsampler();
    g_upsampler->setDitherMode(static_cast<DitherMode>(mode));
}

JNIEXPORT void JNICALL
Java_com_example_perfectbitrate_NativeAudioEngine_nativeSetLrIndependentDither(
        JNIEnv *env, jobject thiz, jboolean enabled) {
    std::lock_guard<std::mutex> lock(g_dspMutex);
    g_isLrIndependentDither = (enabled == JNI_TRUE);
    if (!g_upsampler) g_upsampler = new DspUpsampler();
    g_upsampler->setLrIndependentDither(g_isLrIndependentDither);
}

JNIEXPORT void JNICALL
Java_com_example_perfectbitrate_NativeAudioEngine_nativeSetFirFilterType(
        JNIEnv *env, jobject thiz, jint type) {
    std::lock_guard<std::mutex> lock(g_dspMutex);
    g_currentFirFilterType = type;
    if (!g_upsampler) g_upsampler = new DspUpsampler();
    g_upsampler->setFirFilterType(static_cast<FirFilterType>(type));
}

JNIEXPORT void JNICALL
Java_com_example_perfectbitrate_NativeAudioEngine_nativeSetDcPhaseType(
        JNIEnv *env, jobject thiz, jint type) {
    std::lock_guard<std::mutex> lock(g_dspMutex);
    g_currentDcPhaseType = type;
    if (!g_upsampler) g_upsampler = new DspUpsampler();
    g_upsampler->setDcPhaseType(static_cast<DcPhaseType>(type));
}

JNIEXPORT void JNICALL
Java_com_example_perfectbitrate_NativeAudioEngine_nativeSetPerformanceMode(
        JNIEnv *env, jobject thiz, jint mode) {
    std::lock_guard<std::mutex> lock(g_dspMutex);
    g_currentPerfMode = mode;
    if (!g_upsampler) g_upsampler = new DspUpsampler();
    g_upsampler->setPerformanceMode(static_cast<PerformanceMode>(mode));
}

JNIEXPORT void JNICALL
Java_com_example_perfectbitrate_NativeAudioEngine_nativeSetFreqMode(
        JNIEnv *env, jobject thiz, jint mode) {
    std::lock_guard<std::mutex> lock(g_dspMutex);
    g_currentFreqMode = mode;
    if (!g_upsampler) g_upsampler = new DspUpsampler();
    g_upsampler->setFreqMode(static_cast<FreqMode>(mode));
}

JNIEXPORT void JNICALL
Java_com_example_perfectbitrate_NativeAudioEngine_nativeSetRichHarmonics(
        JNIEnv *env, jobject thiz, jboolean enabled) {
    std::lock_guard<std::mutex> lock(g_dspMutex);
    g_isRichHarmonics = (enabled == JNI_TRUE);
    if (!g_upsampler) g_upsampler = new DspUpsampler();
    g_upsampler->setRichHarmonics(g_isRichHarmonics);
}

JNIEXPORT void JNICALL
Java_com_example_perfectbitrate_NativeAudioEngine_nativeSetFreqCustomParams(
        JNIEnv *env, jobject thiz, jfloat gain, jfloat extractFreq) {
    std::lock_guard<std::mutex> lock(g_dspMutex);
    g_currentFreqGain = gain;
    g_currentFreqExtractFreq = extractFreq;
    if (!g_upsampler) g_upsampler = new DspUpsampler();
    g_upsampler->setFreqCustomParams(gain, extractFreq);
}

JNIEXPORT void JNICALL
Java_com_example_perfectbitrate_NativeAudioEngine_nativeSetFreqAdvancedParams(
        JNIEnv *env, jobject thiz, jint lpcAlgo, jboolean useQmf, jfloat qmfCrossoverHz) {
    std::lock_guard<std::mutex> lock(g_dspMutex);
    g_currentLpcAlgo = lpcAlgo;
    g_isQmfEnabled = (useQmf == JNI_TRUE);
    g_qmfCrossoverHz = qmfCrossoverHz;
    if (!g_upsampler) g_upsampler = new DspUpsampler();
    g_upsampler->setFreqAdvancedParams(g_currentLpcAlgo, g_isQmfEnabled, static_cast<double>(g_qmfCrossoverHz));
}

JNIEXPORT void JNICALL
Java_com_example_perfectbitrate_NativeAudioEngine_nativeSetTransientMode(
        JNIEnv *env, jobject thiz, jint mode) {
    std::lock_guard<std::mutex> lock(g_dspMutex);
    g_currentTransientMode = mode;
    if (!g_upsampler) g_upsampler = new DspUpsampler();
    g_upsampler->setTransientMode(static_cast<TransientMode>(mode));
}

JNIEXPORT void JNICALL
Java_com_example_perfectbitrate_NativeAudioEngine_nativeSetTransientCustomParams(
        JNIEnv *env, jobject thiz, jboolean useGroupDelay, jboolean useLattice) {
    std::lock_guard<std::mutex> lock(g_dspMutex);
    g_useGroupDelay = (useGroupDelay == JNI_TRUE);
    g_useLattice = (useLattice == JNI_TRUE);
    if (!g_upsampler) g_upsampler = new DspUpsampler();
    g_upsampler->setTransientCustomParams(g_useGroupDelay, g_useLattice);
}

JNIEXPORT void JNICALL
Java_com_example_perfectbitrate_NativeAudioEngine_nativeSetMsSpatial(
        JNIEnv *env, jobject thiz, jboolean enabled) {
    std::lock_guard<std::mutex> lock(g_dspMutex);
    g_isMsSpatial = (enabled == JNI_TRUE);
    if (!g_upsampler) g_upsampler = new DspUpsampler();
    g_upsampler->setMsSpatial(g_isMsSpatial);
}

JNIEXPORT jboolean JNICALL
Java_com_example_perfectbitrate_NativeAudioEngine_nativeGetMsSpatial(
        JNIEnv *env, jobject thiz) {
    std::lock_guard<std::mutex> lock(g_dspMutex);
    return (g_upsampler ? g_upsampler->isMsSpatial() : g_isMsSpatial) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_example_perfectbitrate_NativeAudioEngine_nativeSetDynamicSbr(
        JNIEnv *env, jobject thiz, jboolean enabled) {
    std::lock_guard<std::mutex> lock(g_dspMutex);
    g_isDynamicSbr = (enabled == JNI_TRUE);
    if (!g_upsampler) g_upsampler = new DspUpsampler();
    g_upsampler->setDynamicSbr(g_isDynamicSbr);
}

JNIEXPORT jboolean JNICALL
Java_com_example_perfectbitrate_NativeAudioEngine_nativeGetDynamicSbr(
        JNIEnv *env, jobject thiz) {
    std::lock_guard<std::mutex> lock(g_dspMutex);
    return (g_upsampler ? g_upsampler->isDynamicSbr() : g_isDynamicSbr) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_example_perfectbitrate_NativeAudioEngine_nativeSetEqualizer(
        JNIEnv *env, jobject thiz, jboolean enabled, jfloatArray gains) {
    std::lock_guard<std::mutex> lock(g_dspMutex);
    if (!g_upsampler) g_upsampler = new DspUpsampler();
    g_isEqEnabled = (enabled == JNI_TRUE);
    g_upsampler->getEqualizer().setEnabled(g_isEqEnabled);
    if (gains) {
        jfloat* gainElements = env->GetFloatArrayElements(gains, nullptr);
        if (gainElements) {
            jsize gainCount = env->GetArrayLength(gains);
            for (jsize i = 0; i < gainCount && i < 10; ++i) {
                g_currentEqGains[i] = gainElements[i];
            }
            g_upsampler->getEqualizer().setAllGains(gainElements);
            env->ReleaseFloatArrayElements(gains, gainElements, JNI_ABORT);
        }
    }
}

JNIEXPORT void JNICALL
Java_com_example_perfectbitrate_NativeAudioEngine_nativeGetSpectrum(
        JNIEnv *env, jobject thiz, jfloatArray out_array) {
    if (!out_array) return;
    std::lock_guard<std::mutex> lock(g_dspMutex);
    if (!g_upsampler) return;
    jfloat* dst = env->GetFloatArrayElements(out_array, nullptr);
    if (dst) {
        g_upsampler->getSpectrum(dst);
        env->ReleaseFloatArrayElements(out_array, dst, 0);
    }
}

JNIEXPORT void JNICALL
Java_com_example_perfectbitrate_NativeAudioEngine_nativeSetTruePeakGuard(
        JNIEnv *env, jobject thiz, jboolean enabled, jfloat ceiling_db) {
    std::lock_guard<std::mutex> lock(g_dspMutex);
    g_isTruePeakGuardEnabled = (enabled == JNI_TRUE);
    g_truePeakCeilingDb = ceiling_db;
    if (!g_upsampler) g_upsampler = new DspUpsampler();
    g_upsampler->setTruePeakGuard(g_isTruePeakGuardEnabled, static_cast<double>(g_truePeakCeilingDb));
}

JNIEXPORT void JNICALL
Java_com_example_perfectbitrate_NativeAudioEngine_nativeGetTruePeakStats(
        JNIEnv *env, jobject thiz, jfloatArray out_array) {
    if (!out_array) return;
    std::lock_guard<std::mutex> lock(g_dspMutex);
    jfloat* dst = env->GetFloatArrayElements(out_array, nullptr);
    if (dst) {
        // [現在低減 dB, 最大低減 dB, 発動回数, キャップ到達回数] (表示専用)
        float stats[4] = {0.0f, 0.0f, 0.0f, 0.0f};
        if (g_upsampler) g_upsampler->getTruePeakStats(stats);
        jsize len = env->GetArrayLength(out_array);
        for (jsize i = 0; i < len && i < 4; ++i) {
            dst[i] = stats[i];
        }
        env->ReleaseFloatArrayElements(out_array, dst, 0);
    }
}

JNIEXPORT jbyteArray JNICALL
Java_com_example_perfectbitrate_NativeAudioEngine_nativeProcessUpsample(
        JNIEnv *env, jobject thiz,
        jbyteArray in_bytes, jint in_length,
        jstring in_bit_mode, jstring out_bit_mode, jint factor) {
    if (!in_bytes || in_length <= 0) return nullptr;
    
    std::lock_guard<std::mutex> lock(g_dspMutex);
    if (!g_upsampler) g_upsampler = new DspUpsampler();

    const char* inMode = env->GetStringUTFChars(in_bit_mode, nullptr);
    const char* outMode = env->GetStringUTFChars(out_bit_mode, nullptr);

    int effectiveFactor = g_isDirectSource ? 1 : factor;
    if (g_upsampler->getFactor() != effectiveFactor) {
        g_upsampler->configure(effectiveFactor, g_currentSampleRate);
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

}