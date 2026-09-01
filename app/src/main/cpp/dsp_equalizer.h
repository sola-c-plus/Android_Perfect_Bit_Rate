#pragma once

#include <vector>
#include <cmath>
#include <array>

class DspEqualizer {
public:
    static constexpr int NUM_BANDS = 10;
    static constexpr float FREQUENCIES[NUM_BANDS] = {
        31.25f, 62.5f, 125.0f, 250.0f, 500.0f, 1000.0f, 2000.0f, 4000.0f, 8000.0f, 16000.0f
    };

    DspEqualizer();
    ~DspEqualizer() = default;

    void setSampleRate(float sampleRate);
    void setBandGain(int band, float gainDb);
    void setAllGains(const float* gains);
    void setEnabled(bool enabled);
    bool isEnabled() const { return enabled_; }

    void processStereo(float* left, float* right, size_t numFrames);
    void reset();

private:
    struct Biquad {
        float b0 = 1.0f, b1 = 0.0f, b2 = 0.0f;
        float a1 = 0.0f, a2 = 0.0f;
        float s1_L = 0.0f, s2_L = 0.0f;
        float s1_R = 0.0f, s2_R = 0.0f;
        bool isBypass = true;

        void update(float f0, float gainDb, float q, float fs);
        void resetState();
        
        inline void process(float& inL, float& inR) {
            if (isBypass) return;
            float outL = b0 * inL + s1_L;
            s1_L = b1 * inL - a1 * outL + s2_L;
            s2_L = b2 * inL - a2 * outL;
            inL = outL;

            float outR = b0 * inR + s1_R;
            s1_R = b1 * inR - a1 * outR + s2_R;
            s2_R = b2 * inR - a2 * outR;
            inR = outR;
        }
    };

    bool enabled_ = false;
    float sampleRate_ = 48000.0f;
    std::array<float, NUM_BANDS> gainsDb_{};
    std::array<Biquad, NUM_BANDS> filters_{};
};
