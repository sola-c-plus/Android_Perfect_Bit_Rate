#pragma once

#include <vector>
#include <cmath>
#include <array>
#include <algorithm>

class DspEqualizer {
public:
    static constexpr int NUM_BANDS = 10;
    static constexpr double FREQUENCIES[NUM_BANDS] = {
        31.25, 62.5, 125.0, 250.0, 500.0, 1000.0, 2000.0, 4000.0, 8000.0, 16000.0
    };

    DspEqualizer();
    ~DspEqualizer() = default;

    void setSampleRate(double sampleRate);
    void setBandGain(int band, float gainDb);
    void setAllGains(const float* gains);
    void setEnabled(bool enabled);
    bool isEnabled() const { return enabled_; }

    void processStereo(float* left, float* right, size_t numFrames);
    void reset();

private:
    // ★ 64-bit 倍精度浮動小数点 IIR バイカッドフィルター (桁落ちノイズ皆無)
    struct Biquad64 {
        double b0 = 1.0, b1 = 0.0, b2 = 0.0;
        double a1 = 0.0, a2 = 0.0;
        double s1_L = 0.0, s2_L = 0.0;
        double s1_R = 0.0, s2_R = 0.0;
        bool isBypass = true;

        void update(double f0, double gainDb, double q, double fs);
        void resetState();

        inline void process(double& inL, double& inR) {
            if (isBypass) return;
            double outL = b0 * inL + s1_L;
            s1_L = b1 * inL - a1 * outL + s2_L;
            s2_L = b2 * inL - a2 * outL;
            inL = outL;

            double outR = b0 * inR + s1_R;
            s1_R = b1 * inR - a1 * outR + s2_R;
            s2_R = b2 * inR - a2 * outR;
            inR = outR;
        }
    };

    bool enabled_ = false;
    double sampleRate_ = 48000.0;
    std::array<float, NUM_BANDS> gainsDb_{};
    std::array<Biquad64, NUM_BANDS> filters_{};

    // ★ オートヘッドルームゲイン (ブースト時のデジタルクリップ・音割れを完全防止)
    double headRoomGain_ = 1.0;
    void recalculateHeadroom();
};
