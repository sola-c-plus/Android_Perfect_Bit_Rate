#include "dsp_true_peak_guard.h"
#include <algorithm>
#include <cmath>

// 設計の要点は dsp_true_peak_guard.h のコメントと design-true-peak.md §2/§3 を参照。
// 本ファイルで特に注意する不変条件:
//   - サンプル j の必要ゲイン g_req は、サンプル j+1 の到着時に確定する
//     (放物線補間には前後 3 点が要るため)。出力は先読み長ぶん遅れるので、
//     j が出力される時点 (j + lookahead) では必ず確定済みであり、アタック漏れはない。
//   - 適用ゲインの窓は [outIdx, outIdx + lookahead] (出力サンプル自身を含む)。
//     自身を含めないと、単独で立つピーク (インパルス等) が素通りしてしまう。

DspTruePeakGuard::DspTruePeakGuard() {
    // 単体デフォルトは推奨セット (96kHz / -1.0dBTP)。実運用では DspUpsampler が
    // 実績レートと保持設定で configure し直す。
    configure(96000.0, -1.0);
}

double DspTruePeakGuard::estimatePeak3(double a, double b, double c) {
    // a, b, c は |x| の連続 3 点。b を中心とする放物線 y = b + p*t + q*t^2 の
    // 頂点 (t* = 0.5*(a-c) / (a-2b+c)) の値を返す。上に凸でない場合は b そのもの。
    const double denom = a - 2.0 * b + c;
    if (denom >= 0.0) return b;
    double delta = 0.5 * (a - c) / denom;
    if (delta > 1.0) delta = 1.0;
    if (delta < -1.0) delta = -1.0;
    const double peak = b - 0.25 * (a - c) * delta;
    // NaN が混入した場合は「ピークなし」(0) として扱い、ゲイン計算へ伝播させない。
    if (!(peak >= 0.0)) return 0.0;
    return peak > b ? peak : b;
}

double DspTruePeakGuard::requiredGain(double peakEst, bool& capped) const {
    capped = false;
    // NaN は比較が常に false になるため、ここで「超過なし」として落ちる。
    if (!(peakEst > ceilingLin_)) return 1.0;
    double g = ceilingLin_ / peakEst;
    if (g < kMaxReductionGain) {
        // 最大低減キャップ -6dB。これを超える超過は変換段のクランプに委ね、
        // 異常な入力で曲全体が沈むのを防ぐ (キャップ到達は統計で可視化する)。
        g = kMaxReductionGain;
        capped = true;
    }
    return g;
}

void DspTruePeakGuard::configure(double sampleRate, double ceilingDb) {
    sampleRate_ = (sampleRate > 0.0) ? sampleRate : 48000.0;
    ceilingLin_ = std::pow(10.0, ceilingDb / 20.0);
    size_t la = static_cast<size_t>(std::llround(kLookaheadSec * sampleRate_));
    if (la < 2) la = 2;
    if (la > kCapacity - 2) la = kCapacity - 2;
    lookahead_ = la;
    releaseCoeff_ = std::exp(-1.0 / (kReleaseSec * sampleRate_));
    delayL_.assign(kCapacity, 0.0f);
    delayR_.assign(kCapacity, 0.0f);
    gainReq_.assign(kCapacity, 1.0);
    reset();
}

void DspTruePeakGuard::setCeilingDb(double ceilingDb) {
    ceilingLin_ = std::pow(10.0, ceilingDb / 20.0);
}

void DspTruePeakGuard::reset() {
    if (!delayL_.empty()) {
        std::fill(delayL_.begin(), delayL_.end(), 0.0f);
        std::fill(delayR_.begin(), delayR_.end(), 0.0f);
        std::fill(gainReq_.begin(), gainReq_.end(), 1.0);
    }
    minQueue_.clear();
    written_ = 0;
    prev1L_ = 0.0;
    prev1R_ = 0.0;
    prev2L_ = 0.0;
    prev2R_ = 0.0;
    hasPrev_ = false;
    gain_ = 1.0;
    cappedEpisode_ = false;
    maxReductionDb_ = 0.0;
    activationCount_ = 0;
    capHitCount_ = 0;
    reducing_ = false;
}

void DspTruePeakGuard::processStereo(float* left, float* right, size_t numFrames) {
    if (!left || !right || numFrames == 0) return;

    for (size_t i = 0; i < numFrames; ++i) {
        const float inL = left[i];
        const float inR = right[i];
        const double l = static_cast<double>(inL);
        const double r = static_cast<double>(inR);
        const uint64_t idx = written_;

        // (1) 1 つ前のサンプル (idx-1) の必要ゲインを確定させる。
        //     そのサンプルの前後 3 点が初めて揃うのがこの時点。
        //     リセット直後の最初のサンプルでは prev2 を 0 (信号開始前の無音) として扱う。
        if (hasPrev_) {
            const double peak = std::max(
                estimatePeak3(std::abs(prev2L_), std::abs(prev1L_), std::abs(l)),
                estimatePeak3(std::abs(prev2R_), std::abs(prev1R_), std::abs(r)));
            bool capped = false;
            const double g = requiredGain(peak, capped);
            const uint64_t gIdx = idx - 1;
            gainReq_[static_cast<size_t>(gIdx % kCapacity)] = g;
            // 単調キュー: 末尾の「g 以上」を落としてから追加し、先頭を最小値に保つ。
            while (!minQueue_.empty() && minQueue_.back().second >= g) {
                minQueue_.pop_back();
            }
            minQueue_.emplace_back(gIdx, g);
            // キャップ統計は「張り付き開始」の遷移でのみ数える。
            if (capped && !cappedEpisode_) {
                capHitCount_++;
            }
            cappedEpisode_ = capped;
        }

        // (2) 現在のサンプルを遅延線へ。g_req は暫定 1.0 とし、次サンプルの到着時に
        //     (1) で確定値へ上書きされる。
        const size_t slot = static_cast<size_t>(idx % kCapacity);
        delayL_[slot] = inL;
        delayR_[slot] = inR;
        gainReq_[slot] = 1.0;

        // (3) 読み出し。先読み長ぶん溜まるまでは無音を出す
        //     (EQ 内蔵リミッタの isPrimed_ と同じ割り切り。5ms で知覚不能)。
        if (idx < lookahead_) {
            left[i] = 0.0f;
            right[i] = 0.0f;
        } else {
            const uint64_t outIdx = idx - lookahead_;
            // 窓は [outIdx, outIdx + lookahead]。左端より前のエントリを捨てる。
            while (!minQueue_.empty() && minQueue_.front().first < outIdx) {
                minQueue_.pop_front();
            }
            const double windowMin = minQueue_.empty() ? 1.0 : minQueue_.front().second;
            // リリース平滑: 適用ゲイン = min(窓最小, 前フレームのゲインから 1.0 へ
            // 指数回復した値)。下降は窓が担うためピーク到達前に下がり切り、
            // 回復は時定数 150ms で緩やかになる。段差のあるゲイン変化は生じない。
            const double released = 1.0 - (1.0 - gain_) * releaseCoeff_;
            const double g = std::min(windowMin, released);
            gain_ = g;

            const size_t outSlot = static_cast<size_t>(outIdx % kCapacity);
            left[i] = static_cast<float>(static_cast<double>(delayL_[outSlot]) * g);
            right[i] = static_cast<float>(static_cast<double>(delayR_[outSlot]) * g);

            // 統計 (エピソード = ゲインが 1.0 未満に下がっている連続区間)。
            const bool nowReducing = (g < 1.0);
            if (nowReducing && !reducing_) {
                activationCount_++;
            }
            reducing_ = nowReducing;
            if (nowReducing) {
                const double redDb = -20.0 * std::log10(g);
                if (redDb > maxReductionDb_) {
                    maxReductionDb_ = redDb;
                }
            }
        }

        // (4) ピーク推定用の履歴を進める。
        prev2L_ = prev1L_;
        prev1L_ = l;
        prev2R_ = prev1R_;
        prev1R_ = r;
        hasPrev_ = true;
        written_ = idx + 1;
    }
}

double DspTruePeakGuard::currentReductionDb() const {
    if (gain_ >= 1.0 || gain_ <= 0.0) return 0.0;
    return -20.0 * std::log10(gain_);
}
