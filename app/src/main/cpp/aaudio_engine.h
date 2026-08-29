#pragma once

#include <aaudio/AAudio.h>
#include <atomic>
#include <vector>
#include <cstdint>
#include <algorithm>
#include <android/log.h>

#define LOG_TAG "BitPerfectEngine"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

class LockFreeRingBuffer {
public:
    explicit LockFreeRingBuffer(size_t capacity)
        : buffer_(capacity), capacity_(capacity), head_(0), tail_(0) {}

    size_t write(const uint8_t* data, size_t bytes) {
        size_t head = head_.load(std::memory_order_relaxed);
        size_t tail = tail_.load(std::memory_order_acquire);
        size_t available = capacity_ - (head - tail);
        size_t toWrite = std::min(bytes, available);

        for (size_t i = 0; i < toWrite; ++i) {
            buffer_[(head + i) % capacity_] = data[i];
        }
        head_.store(head + toWrite, std::memory_order_release);
        return toWrite;
    }

    size_t read(uint8_t* dest, size_t bytes) {
        size_t head = head_.load(std::memory_order_acquire);
        size_t tail = tail_.load(std::memory_order_relaxed);
        size_t available = head - tail;
        size_t toRead = std::min(bytes, available);

        for (size_t i = 0; i < toRead; ++i) {
            dest[i] = buffer_[(tail + i) % capacity_];
        }
        tail_.store(tail + toRead, std::memory_order_release);
        return toRead;
    }

    void clear() {
        head_.store(0, std::memory_order_relaxed);
        tail_.store(0, std::memory_order_relaxed);
    }

    size_t availableRead() const {
        return head_.load(std::memory_order_relaxed) - tail_.load(std::memory_order_relaxed);
    }

private:
    std::vector<uint8_t> buffer_;
    size_t capacity_;
    std::atomic<size_t> head_;
    std::atomic<size_t> tail_;
};

class AAudioEngine {
public:
    AAudioEngine();
    ~AAudioEngine();

    int32_t openStream(int32_t sampleRate, int32_t channelCount, aaudio_format_t format, int32_t deviceId = AAUDIO_UNSPECIFIED);
    void closeStream();
    bool start();
    bool stop();
    void flush();
    size_t write(const uint8_t* data, size_t sizeInBytes);

    static aaudio_data_callback_result_t dataCallback(
        AAudioStream *stream, void *userData, void *audioData, int32_t numFrames
    );

    static void errorCallback(
        AAudioStream *stream, void *userData, aaudio_result_t error
    );

    double phase_ = 0.0;

private:
    AAudioStream* stream_ = nullptr;
    LockFreeRingBuffer ringBuffer_;
    int32_t sampleRate_ = 48000;
    int32_t channelCount_ = 2;
    aaudio_format_t format_ = AAUDIO_FORMAT_PCM_I16;
    int32_t bytesPerFrame_ = 4;
};