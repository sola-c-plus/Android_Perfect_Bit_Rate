#include "aaudio_engine.h"
#include <cstring>
#include <cmath>

constexpr size_t RING_BUFFER_SIZE = 2 * 1024 * 1024; // 2MB バッファ

AAudioEngine::AAudioEngine() : ringBuffer_(RING_BUFFER_SIZE) {}

AAudioEngine::~AAudioEngine() {
    closeStream();
}

int32_t AAudioEngine::openStream(int32_t sampleRate, int32_t channelCount, aaudio_format_t format, int32_t deviceId) {
    closeStream();

    sampleRate_ = sampleRate;
    channelCount_ = channelCount;
    format_ = format;

    size_t bytesPerSample = (format == AAUDIO_FORMAT_PCM_I16) ? 2 : (format == AAUDIO_FORMAT_PCM_FLOAT ? 4 : 3);
    bytesPerFrame_ = channelCount_ * bytesPerSample;

    AAudioStreamBuilder* builder = nullptr;
    aaudio_result_t result = AAudio_createStreamBuilder(&builder);
    if (result != AAUDIO_OK) return 0;

    if (deviceId > 0) {
        AAudioStreamBuilder_setDeviceId(builder, deviceId);
    }
    AAudioStreamBuilder_setDirection(builder, AAUDIO_DIRECTION_OUTPUT);
    AAudioStreamBuilder_setSharingMode(builder, AAUDIO_SHARING_MODE_EXCLUSIVE);

    // ★重要: LOW_LATENCY (48k固定MMAP) を避け、USB DAC本来の物理クロックを叩く
    AAudioStreamBuilder_setPerformanceMode(builder, AAUDIO_PERFORMANCE_MODE_NONE);

    AAudioStreamBuilder_setUsage(builder, AAUDIO_USAGE_MEDIA);
    AAudioStreamBuilder_setContentType(builder, AAUDIO_CONTENT_TYPE_MUSIC);

    AAudioStreamBuilder_setSampleRate(builder, sampleRate_);
    AAudioStreamBuilder_setChannelCount(builder, channelCount_);
    AAudioStreamBuilder_setFormat(builder, format_);
    AAudioStreamBuilder_setDataCallback(builder, dataCallback, this);
    AAudioStreamBuilder_setErrorCallback(builder, errorCallback, this);

    result = AAudioStreamBuilder_openStream(builder, &stream_);
    AAudioStreamBuilder_delete(builder);

    if (result != AAUDIO_OK) {
        LOGE("AAudio Open Failed: %s", AAudio_convertResultToText(result));
        return 0;
    }

    aaudio_sharing_mode_t actualMode = AAudioStream_getSharingMode(stream_);
    LOGI("AAudio Opened: Rate=%d Hz, Format=%d, Mode=%d (1=EXCLUSIVE, 2=SHARED)",
         AAudioStream_getSampleRate(stream_), format_, actualMode);

    return (actualMode == AAUDIO_SHARING_MODE_EXCLUSIVE) ? 1 : 2;
}

void AAudioEngine::closeStream() {
    if (stream_ != nullptr) {
        AAudioStream_requestStop(stream_);
        AAudioStream_close(stream_);
        stream_ = nullptr;
        ringBuffer_.clear();
    }
}

bool AAudioEngine::start() {
    if (!stream_) return false;
    return (AAudioStream_requestStart(stream_) == AAUDIO_OK);
}

bool AAudioEngine::stop() {
    if (!stream_) return false;
    return (AAudioStream_requestStop(stream_) == AAUDIO_OK);
}

void AAudioEngine::flush() {
    ringBuffer_.clear();
}

size_t AAudioEngine::write(const uint8_t* data, size_t sizeInBytes) {
    return ringBuffer_.write(data, sizeInBytes);
}

aaudio_data_callback_result_t AAudioEngine::dataCallback(
    AAudioStream *stream, void *userData, void *audioData, int32_t numFrames
) {
    auto* engine = static_cast<AAudioEngine*>(userData);
    size_t bytesNeeded = numFrames * engine->bytesPerFrame_;

    if (engine->ringBuffer_.availableRead() >= bytesNeeded) {
        engine->ringBuffer_.read(reinterpret_cast<uint8_t*>(audioData), bytesNeeded);
        return AAUDIO_CALLBACK_RESULT_CONTINUE;
    }

    // アンダーラン時は無音（0）を出力
    std::memset(audioData, 0, bytesNeeded);
    return AAUDIO_CALLBACK_RESULT_CONTINUE;
}

void AAudioEngine::errorCallback(AAudioStream *stream, void *userData, aaudio_result_t error) {
    LOGE("AAudio Error: %s", AAudio_convertResultToText(error));
}