#include "dsp_preecho.h"
#include <algorithm>

DspAntiPreecho::DspAntiPreecho() {
    configure(48000.0);
}

void DspAntiPreecho::configure(double sampleRate) {
    sampleRate_ = std::max(8000.0, sampleRate);
    reset();
}

void DspAntiPreecho::reset() {
}

void DspAntiPreecho::processStereo(float* left, float* right, size_t numFrames) {
    // 不要な遅延処理を排除し、完全なトランスペアレント性を維持
}

void DspBitContinuity::reset() {
}

void DspBitContinuity::processStereo(float* left, float* right, size_t numFrames) {
}