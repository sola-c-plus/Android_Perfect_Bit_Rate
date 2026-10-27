#pragma once

#include <cstddef>
#include <cstdint>
#include <deque>
#include <utility>
#include <vector>

// True Peak ガード (独立最終段)。
//
// アップサンプリングや DSP で生じるインターサンプルピーク (サンプル間ピーク) が
// 出力変換段のハードクランプで平らに潰れて歪む問題への対策として、
// 全 DSP の後段・出力変換 (ディザ/量子化) の直前に置く独立モジュール。
// EQ 内蔵の先読みリミッタ (DspEqualizer) とは独立に動作し、EQ OFF でも効く。
//
// 構造 (設計書 design-true-peak.md §3):
//   1. 入力サンプルを遅延線 (リングバッファ) へ書く際、放物線補間で推定した
//      真ピークから必要ゲイン g_req = min(1, 天井 / peakEst) を同じ位置に記録する。
//      ピーク推定には次のサンプルが必要なため、g_req は 1 サンプル遅れで確定する
//      (先読み長に対して無視でき、確定は出力より十分前に完了する)。
//   2. 読み出し時点で、そのサンプルより後ろ先読み長ぶんの g_req は既知であり、
//      適用ゲインは窓内最小値 (sliding-window minimum、単調キューで維持) を取る。
//      これによりピークが遅延線を出る時点でゲインは既に下がり切っている。
//   3. 窓最小値にリリース平滑 (時定数 150ms の指数回復) を重ねる。
//      下降は窓の効果でピーク到達前から単調に始まり、回復は緩やかになる。
//
// 設計固定値 (ユーザー条件から導かれるため設定項目にしない):
//   先読み 5ms / リリース 150ms / L/R 連動 / 最大低減キャップ -6dB。
// 有効・無効の管理は呼び出し側 (DspUpsampler) が行い、OFF 時は本モジュールを
// 一切通さないことでバイト同一を保証する。
class DspTruePeakGuard {
public:
    DspTruePeakGuard();

    // 出力サンプルレートと天井で初期化する。内部状態はリセットされる。
    void configure(double sampleRate, double ceilingDb);
    // 天井だけを変更する (状態は保持され、次のブロックから反映される)。
    void setCeilingDb(double ceilingDb);
    void reset();

    // in-place 処理。出力長は入力と同一で、先読み長ぶんの遅延が付く
    // (再生開始・リセット直後の先読み長ぶんは無音)。
    void processStereo(float* left, float* right, size_t numFrames);

    // 統計 (dB は低減量を正の値で表す)。表示専用であり、設定へは書き戻さない。
    double currentReductionDb() const;   // 現在の適用ゲインの低減量
    double maxReductionDb() const { return maxReductionDb_; }  // リセット以来の最大低減
    uint64_t activationCount() const { return activationCount_; }  // 低減が始まった回数
    uint64_t capHitCount() const { return capHitCount_; }  // 最大低減キャップに到達した回数

private:
    static constexpr size_t kCapacity = 4096;         // 遅延線容量 (5ms @768kHz=3840 を内包)
    static constexpr double kLookaheadSec = 0.005;    // 先読み 5ms
    static constexpr double kReleaseSec = 0.150;      // リリース時定数 150ms
    static constexpr double kMaxReductionGain = 0.5011872336272722;  // -6dB (線形)

    // 放物線補間によるピーク推定 (案 B)。|x| の連続 3 点から頂点を推定する。
    // 将来の案 C (検出専用オーバーサンプリング) への差し替えはこの関数の置換で行う。
    static double estimatePeak3(double prevAbs, double curAbs, double nextAbs);
    // 推定ピークから必要ゲインを求める。キャップ到達時は capped に true を返す。
    double requiredGain(double peakEst, bool& capped) const;

    double sampleRate_ = 96000.0;
    double ceilingLin_ = 1.0;
    size_t lookahead_ = 480;
    double releaseCoeff_ = 0.0;

    std::vector<float> delayL_;
    std::vector<float> delayR_;
    std::vector<double> gainReq_;  // スロット毎の必要ゲイン (確定前は暫定 1.0)
    uint64_t written_ = 0;         // 書き込んだ総フレーム数 (絶対インデックス)

    // ピーク推定用の直近サンプル (生値)。prev1 が 1 つ前の入力サンプル。
    double prev1L_ = 0.0;
    double prev1R_ = 0.0;
    double prev2L_ = 0.0;
    double prev2R_ = 0.0;
    bool hasPrev_ = false;  // prev1 / prev2 が有効か

    // sliding-window minimum 用の単調キュー (絶対インデックス, g_req)。
    std::deque<std::pair<uint64_t, double>> minQueue_;
    double gain_ = 1.0;  // 前フレームの適用ゲイン (リリース平滑の起点)

    bool cappedEpisode_ = false;  // g_req がキャップに張り付いている最中か

    double maxReductionDb_ = 0.0;
    uint64_t activationCount_ = 0;
    uint64_t capHitCount_ = 0;
    bool reducing_ = false;  // 現在、低減エピソード中か
};
