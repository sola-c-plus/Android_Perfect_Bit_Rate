#include "dsp_transient.h"
#include <algorithm>

DspTransientRestorer::DspTransientRestorer() {
    configure(TransientMode::ACOUSTIC, 48000.0, true, false);
}

void DspTransientRestorer::configure(TransientMode mode, double sampleRate, bool useGroupDelay, bool useLattice) {
    mode_ = mode;
    sampleRate_ = std::max(8000.0, sampleRate);
    useGroupDelay_ = useGroupDelay;
    useLattice_ = useLattice;

    // 群遅延補正オールパスの係数 (標準の強さ・ユーザー調整済み): 1次オールパス
    // H(z) = (a + z^-1) / (1 + a*z^-1) の群遅延は
    // τ(ω) = (1 - a^2) / (1 + a^2 + 2a*cos ω) で、DC では τ(0) = (1-a)/(1+a)。
    // 1段あたり τ(0) = 3.0 サンプルを目標に a = (1-τ0)/(1+τ0) = -0.5 とする。
    // τ(π/2) = 0.6、τ(π) = 1/3 ≈ 0.333 で、分散は τ(0) - τ(π) ≈ 2.67 サンプル/段
    // (2段で約5.33サンプル、DC の合計は6.0サンプル)。
    // 振幅特性は平坦で位相のみが変わる。固定係数では群遅延 (サンプル数) は
    // サンプルレートに依存しないため、レートによらず同じ係数を使う。
    static constexpr double kGroupDelayTau0 = 3.0;
    gdA_ = (1.0 - kGroupDelayTau0) / (1.0 + kGroupDelayTau0);

    reset();

    if (mode_ == TransientMode::OFF) {
        isBypass_ = true;
        return;
    }

    isBypass_ = false;
    double timeScale = 48000.0 / sampleRate_;

    switch (mode_) {
        case TransientMode::NATURAL:
            attackGain_ = 1.05;
            fastAlpha_ = std::clamp(0.04 * timeScale, 0.005, 0.2);
            slowAlpha_ = std::clamp(0.002 * timeScale, 0.0002, 0.02);
            break;
        case TransientMode::PUNCH:
            attackGain_ = 1.18;
            fastAlpha_ = std::clamp(0.06 * timeScale, 0.008, 0.25);
            slowAlpha_ = std::clamp(0.0015 * timeScale, 0.0001, 0.015);
            break;
        case TransientMode::ACOUSTIC:
            attackGain_ = 1.10;
            fastAlpha_ = std::clamp(0.08 * timeScale, 0.01, 0.3);
            slowAlpha_ = std::clamp(0.003 * timeScale, 0.0003, 0.03);
            break;
        default:
            attackGain_ = 1.05;
            fastAlpha_ = 0.04;
            slowAlpha_ = 0.002;
            break;
    }
}

void DspTransientRestorer::reset() {
    envFastL_ = 0.0; envSlowL_ = 0.0;
    envFastR_ = 0.0; envSlowR_ = 0.0;
    prevSampleL_ = 0.0; prevSampleR_ = 0.0;
    latK1_L_ = 0.0; latK2_L_ = 0.0;
    latK1_R_ = 0.0; latK2_R_ = 0.0;
    latB1_L_ = 0.0; latB2_L_ = 0.0;
    latB1_R_ = 0.0; latB2_R_ = 0.0;
    gdX1L_ = 0.0; gdY1L_ = 0.0;
    gdX2L_ = 0.0; gdY2L_ = 0.0;
    gdX1R_ = 0.0; gdY1R_ = 0.0;
    gdX2R_ = 0.0; gdY2R_ = 0.0;
}

void DspTransientRestorer::processStereo(float* left, float* right, size_t numFrames) {
    if (isBypass_ || !left || !right || numFrames == 0) return;

    for (size_t i = 0; i < numFrames; ++i) {
        double inL = static_cast<double>(left[i]);
        double absInL = std::abs(inL);
        envFastL_ = envFastL_ * (1.0 - fastAlpha_) + absInL * fastAlpha_;
        envSlowL_ = envSlowL_ * (1.0 - slowAlpha_) + absInL * slowAlpha_;

        double diffL = std::max(0.0, envFastL_ - envSlowL_);
        double transientRatioL = std::min(diffL / (envSlowL_ + 1e-4), 1.5);

        // 群遅延補正: delta 経路の入力だけをオールパスに通し、アタック成分の
        // 位相を進める。包絡検出と出力の基準は元の inL のまま (振幅特性は不変)。
        double srcL = inL;
        if (useGroupDelay_) {
            double y1 = gdA_ * inL + gdX1L_ - gdA_ * gdY1L_;
            gdX1L_ = inL; gdY1L_ = y1;
            double y2 = gdA_ * y1 + gdX2L_ - gdA_ * gdY2L_;
            gdX2L_ = y1; gdY2L_ = y2;
            srcL = y2;
        }

        double predL = srcL;
        if (useLattice_) {
            double f1 = srcL - latK1_L_ * latB1_L_;
            double b1 = latB1_L_ - latK1_L_ * srcL;
            latB1_L_ = srcL;
            latK1_L_ = std::clamp(latK1_L_ * 0.995 + (f1 * b1) * 0.005, -0.9, 0.9);
            predL = srcL + f1 * 0.25;
        }

        double deltaL = predL - prevSampleL_;
        prevSampleL_ = srcL;

        // ★ 定常音への常時高域ブーストを排除し、過渡アタック時のみ適切にダイナミクス復元
        double outL = inL + (deltaL * (attackGain_ - 1.0) * transientRatioL * 0.5);
        left[i] = static_cast<float>(std::clamp(outL, -1.0, 1.0));

        double inR = static_cast<double>(right[i]);
        double absInR = std::abs(inR);
        envFastR_ = envFastR_ * (1.0 - fastAlpha_) + absInR * fastAlpha_;
        envSlowR_ = envSlowR_ * (1.0 - slowAlpha_) + absInR * slowAlpha_;

        double diffR = std::max(0.0, envFastR_ - envSlowR_);
        double transientRatioR = std::min(diffR / (envSlowR_ + 1e-4), 1.5);

        double srcR = inR;
        if (useGroupDelay_) {
            double y1 = gdA_ * inR + gdX1R_ - gdA_ * gdY1R_;
            gdX1R_ = inR; gdY1R_ = y1;
            double y2 = gdA_ * y1 + gdX2R_ - gdA_ * gdY2R_;
            gdX2R_ = y1; gdY2R_ = y2;
            srcR = y2;
        }

        double predR = srcR;
        if (useLattice_) {
            double f1 = srcR - latK1_R_ * latB1_R_;
            double b1 = latB1_R_ - latK1_R_ * srcR;
            latB1_R_ = srcR;
            latK1_R_ = std::clamp(latK1_R_ * 0.995 + (f1 * b1) * 0.005, -0.9, 0.9);
            predR = srcR + f1 * 0.25;
        }

        double deltaR = predR - prevSampleR_;
        prevSampleR_ = srcR;

        double outR = inR + (deltaR * (attackGain_ - 1.0) * transientRatioR * 0.5);
        right[i] = static_cast<float>(std::clamp(outR, -1.0, 1.0));
    }
}