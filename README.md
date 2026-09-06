# Perfect Bit Rate 🎵
### Bit-Perfect Audio Player & Studio-Grade 64-bit DSP for YouTube Music on Android (USB DAC / Bluetooth / Walkman UI)

![Android 14+](https://img.shields.io/badge/Android-14%2B%20%2F%20API%2033%2B-brightgreen.svg)
![Kotlin](https://img.shields.io/badge/Kotlin-2.0-purple.svg)
![C++17](https://img.shields.io/badge/C%2B%2B-17%20(NEON%20%2F%20SIMD)-orange.svg)
![Architecture](https://img.shields.io/badge/Architecture-ARM64%20%2F%20ARMv7-blue.svg)
![License](https://img.shields.io/badge/License-MIT-blue.svg)

**Perfect Bit Rate** は、Android OS 標準ミキサー（AudioFlinger）による非整数倍リサンプリング（SRC劣化）を完全にバイパスし、YouTube Music の音声を **1:1 ビットパーフェクト伝送**、または **スタジオ級 64-bit C++ ネイティブ DSP（ARM NEON ポリフェーズ Sinc アップサンプリング / FREQ 倍音復元 / DC Phase / 10-Band EQ）** を経由して USB DAC や Bluetooth 機器へダイレクト出力する専用ハイエンドオーディオプレイヤーです。

---

## 📌 目次
1. [プロジェクトの目的と解決する課題](#-プロジェクトの目的と解決する課題)
2. [主要機能一覧](#-主要機能一覧)
3. [システムアーキテクチャ & パイプライン](#-システムアーキテクチャ--パイプライン)
4. [C++ ネイティブ DSP エンジン詳細](#-c-ネイティブ-dsp-エンジン詳細)
5. [UI コンポーネント & 操作ガイド](#-ui-コンポーネント--操作ガイド)
6. [Bluetooth / USB DAC 推奨設定ガイド](#-bluetooth--usb-dac-推奨設定ガイド)
7. [リポジトリ構成](#-リポジトリ構成)
8. [ビルド & 導入手順](#-ビルド--導入手順)
9. [ライセンス & 謝辞](#-ライセンス--謝辞)

---

## 🎵 プロジェクトの目的と解決する課題

### 1. Android OS の「強制 SRC（サンプリングレート変換）問題」
一般的な Android OS は、すべてのオーディオ出力を内部ミキサー（AudioFlinger）で固定サンプリングレート（48kHz または 192kHz 等）に強制リサンプリングします。  
YouTube Music に混在する **44.1kHz（AAC）音源を 48kHz に変換する非整数倍補間処理** により、以下の問題が生じます：
* 微小信号の欠落・音場（ステレオイメージ）の平坦化
* 高域の位相ズレ・エイリアシング（折り返し）ノイズの混入
* トランジェント（アタック感・立ち上がり）の鈍化

### 2. 本アプリのアプローチ
* **Android 14+ ハードウェアクロック完全同期 (`setPreferredMixerAttributes`):**  
  USB DAC 接続時に `MIXER_BEHAVIOR_BIT_PERFECT` を要求。音源のネイティブサンプリングレート（44.1kHz / 48kHz）に合わせて **DAC 内部の物理水晶発振器（クロック）をダイレクトに切り替え** ます。
* **C++ AAudio 排他モードフォールバック (`AAudioEngine`):**  
  48kHz 固定 MMAP を回避する `AAUDIO_PERFORMANCE_MODE_NONE` ＋ `AAUDIO_SHARING_MODE_EXCLUSIVE` による Lock-Free リングバッファ直結パイプライン。
* **GeckoView 内部 Web Audio API キャプチャ:**  
  内蔵 WebExtension により、デコードされた非圧縮 32-bit 浮動小数点 PCM をブラウザエンジンから直接インターセプト。OS 標準のオーディオ出力を一切経由しません。
* **スタジオ級 64-bit DSP パイプライン:**  
  ダイレクト再生に加え、ARM NEON 最適化のポリフェーズ Sinc アップサンプラー（最大 8x / 384kHz）、倍精度 10-Band EQ、高域倍音復元（FREQ Engine / Rich Harmonics）、DC Phase Linearizer、ディザリングをリアルタイム適用可能です。

---

## ✨ 主要機能一覧

* **ビットパーフェクト / ネイティブクロック自動追従:**  
  Opus（48kHz）と AAC（44.1kHz）をリアルタイム自動判別し、DAC 側の物理クロックを瞬時にシフト。
* **Direct Source モード (完全バイパス):**  
  DSP パイプラインをすべてパスし、Web Audio キャプチャから DAC までビット単位で無加工ストリーミング。
* **ARM NEON ポリフェーズ Sinc アップサンプラー:**  
  1x（Bypass）、2x（88.2k/96k）、4x（176.4k/192k）、8x（352.8k/384k）。カイザー窓に基づく高タップ数 FIR をリアルタイム展開。
* **多段カスケード FIR (Multistage Cascade FIR):**  
  3段2x接続（255 taps → 63 taps → 39 taps）により、急峻な遮断特性と超低歪み補間を両立。
* **4 種類の FIR フィルター特性切り替え:**  
  Linear Phase Sharp / Slow、ケプストラム解析による Minimum Phase Sharp / Slow。
* **FREQ ENGINE (超高域倍音復元):**  
  圧縮音源でカットされがちな 20kHz〜40kHz+ の帯域を直交多項式展開・適応解析によって外挿生成（Auto AI / 男性ボーカル / 女性ボーカル / パーカッション / ストリングス）。
* **RICH HARMONICS (ふくよか倍音スイッチ):**  
  16kHz〜 へ温かみのある偶数次倍音（2次/4次）をブレンドし、音に厚みと艶を付加。
* **DC Phase Linearizer:**  
  伝統的なアナログアンプ特有の低域位相特性を 64-bit 倍精度 IIR で再現（Type A / Type B、各 2Hz / 4Hz / 8Hz）。
* **64-bit 倍精度 10-Band EQ & オートヘッドルーム:**  
  桁落ちノイズのない 64-bit バイカッドフィルター。帯域干渉を防ぐ Q=1.15 設計に加え、ブースト時のデジタルクリッピングを未然に防ぐ自動アッテネーション機構を内蔵。
* **SONY WALKMAN 実機完全再現 UI:**  
  -∞ 〜 0dBFS 高速減衰（140dB/s）ホワイトバー ＋ ゴールド Peak ホールド（約750ms）レベルメーター。スプライン補間 EQ 曲線 ＋ 32バンド・リアルタイムスペクトラムアナライザー（40kHz ハイレゾ帯域可視化）。
* **リアルタイム有効ビット深度モニター:**  
  伝送中の PCM 振幅変調から、実際に稼働している有効ビット数（`BIT: 16/16`, `24/24`, `32/32 ACTIVE`）をリアルタイム表示。
* **Bluetooth 高度コーデック追従 (A2DP Tracker):**  
  LDAC, aptX Adaptive, aptX Lossless, aptX HD, LHDC, LC3 などのアクティブコーデックを Reflection により高精度検知しバッジ表示。
* **0dB Volume Lock & 即時安全遮断:**  
  USB DAC 接続時は Android OS の音量を 100%（0dB 減衰なし）に固定。音量キーを曲送り/曲戻しにマッピング。イヤホン・DAC 抜去時（`ACTION_AUDIO_BECOMING_NOISY`）は瞬時に音量をゼロにリセット。
* **バックグラウンド再生保護 & 広告自動スキップ (AD CUT):**  
  WakeLock & High-Perf WifiLock、Page Visibility API 偽装、ハートビート同期により消灯時の停止を阻止。YouTube Music の動画広告を自動検知して高速スキップ。
* **ホーム画面プレイヤーウィジェット:**  
  アルバムアート角丸表示、シークバー、トラック操作、ダーク / ライト / System Auto テーマ切替に対応。

---

## 🏗 システムアーキテクチャ & パイプライン

```text
┌─────────────────────────────────────────────────────────────┐
│                    YouTube Music Web                        │
│                (GeckoView Mobile Browser)                   │
└──────────────────────────────┬──────────────────────────────┘
                               │ Web Audio API (MediaElementSource)
┌──────────────────────────────▼──────────────────────────────┐
│            WebExtension Engine (content.js)                 │
│ - itag / Resource Timing 解析による 44.1k/48k 自動判定        │
│ - Float32 PCM インターセプト (ScriptProcessorNode 4096 frames) │
│ - Page Visibility / document.hidden 偽装 (BGM再生維持)      │
│ - 広告要素即時スキップ / スマート反転ホワイトCSS              │
└──────────────────────────────┬──────────────────────────────┘
                               │ Native Messaging (browser port)
┌──────────────────────────────▼──────────────────────────────┐
│          GeckoSessionController.kt / MainActivity.kt         │
│ - Non-blocking Base64 デコード (Float32 PCM)                 │
│ - Bluetooth / USB 出力先デバイスルーティング                 │
│ - UI / レベルメーター / スペクトラム描画更新                 │
└──────────────────────────────┬──────────────────────────────┘
                               │ pushPcm(...)
┌──────────────────────────────▼──────────────────────────────┐
│          BitPerfectPlaybackService.kt (Foreground)          │
│ - スレッド優先度: Thread.MAX_PRIORITY                        │
│ - バッファキューイング (LinkedBlockingQueue + プリロール制御)│
│ - True Peak / 有効ビット深度アクティビティ算出               │
│ - MediaSessionCompat (通知・ロックスクリーン・ウィジェット)  │
└──────────────────────────────┬──────────────────────────────┘
                               │ JNI (nativeProcessUpsample)
┌──────────────────────────────▼──────────────────────────────┐
│                  C++17 Native DSP Engine                    │
│                                                             │
│  [Step 1] Polyphase Sinc FIR Upsampling (NEON 1x/2x/4x/8x)  │
│           - Multistage Cascade (255 → 63 → 39 taps)         │
│           - Linear / Minimum Phase Filter Transform         │
│  [Step 2] Anti-Preecho & Bit Continuity (Ultra HQ)          │
│  [Step 3] Transient Restorer (Lattice PARCOR & Group Delay) │
│  [Step 4] FREQ Engine (直交多項式展開 & Rich Harmonics)     │
│  [Step 5] M/S Spatial Ambience & 32-Band FFT Analysis       │
│  [Step 6] 64-bit Double Precision 10-Band Biquad EQ         │
│  [Step 7] DC Phase Linearizer (Analog Low-End Phase)        │
│  [Step 8] Dithering (TPDF / High-Pass / Psychoacoustic SBM) │
└──────────────────────────────┬──────────────────────────────┘
                               │
               ┌───────────────┴───────────────┐
               ▼                               ▼
┌──────────────────────────────┐ ┌─────────────────────────────┐
│  Android 14+ USB DAC 出力    │ │ Bluetooth 出力 (A2DP)       │
│  - setPreferredMixerAttr     │ │ - OS二重リサンプリング回避   │
│  - BIT_PERFECT (44.1k / 48k) │ │ - 24-bit PCM 直結伝送       │
│  - 0dB Volume Lock (100%固定)│ │ - LDAC / aptX Adaptive 追従 │
└──────────────────────────────┘ └─────────────────────────────┘
```

## 🎛 C++ ネイティブ DSP エンジン詳細

本アプリの音響信号処理コア（`app/src/main/cpp/`）は、すべて C++17 で設計され、コンパイラ最適化（`-O3`, `-ffast-math`, `-flto`）および Android 15 以降を見据えた **16KB ページサイズアライメント**（`-Wl,-z,max-page-size=16384`）を施してビルドされています。また、主要な積和演算ブロックには **ARM NEON SIMD 組み込み関数** を全面的に採用し、超低レイテンシとバッテリー消費の最小化を両立しています。

```text
[Float32 入力]
│
▼
┌─────────────────────────────────────────────────────────────┐
│ 1. Polyphase Sinc FIR Upsampler │
│ - カイザー窓多段カスケード (255 → 63 → 39 taps) │
│ - ARM NEON (vmlaq_f32) 4並列積和演算 │
│ - ケプストラム解析による因果的最小位相化 (Minimum Phase) │
└──────────────────────────────┬──────────────────────────────┘
▼
┌─────────────────────────────────────────────────────────────┐
│ 2. Anti-Preecho & Bit Continuity (Ultra HQ モード) │
│ - 約3.5ms ルックアヘッドによるプリリンギング抑制 │
│ - 2階差分予測による微小振幅ビット段差のスムージング │
└──────────────────────────────┬──────────────────────────────┘
▼
┌─────────────────────────────────────────────────────────────┐
│ 3. Transient Restorer │
│ - Lattice (PARCOR) 格子型適応予測器による過渡応答強調 │
│ - 微分成分に基づく群遅延 (Group Delay) 補正 │
└──────────────────────────────┬──────────────────────────────┘
▼
┌─────────────────────────────────────────────────────────────┐
│ 4. FREQ ENGINE (超高域倍音復元) │
│ - 3.2kHz フォルマント抽出によるブレス/子音サ行判定 │
│ - 直交多項式展開による 2次・3次・4次高調波適応合成 │
│ - RICH HARMONICS: 16kHz〜 偶数次倍音 (78%) ブレンド │
└──────────────────────────────┬──────────────────────────────┘
▼
┌─────────────────────────────────────────────────────────────┐
│ 5. M/S Spatial Ambience & 32-Band FFT Spectrum Analyzer │
│ - Side チャンネル過渡差分強調による残響空間超解像 │
│ - 2048点 FFT デュアル解析 (低域補間 ＋ 40kHz ハイレゾ域) │
└──────────────────────────────┬──────────────────────────────┘
▼
┌─────────────────────────────────────────────────────────────┐
│ 6. 64-bit Double Precision 10-Band Equalizer │
│ - 64bit 倍精度 IIR バイカッド (桁落ちノイズゼロ化) │
│ - 最適化 Q値 (1.15) による隣接帯域干渉排除 │
│ - 自動クリップ防止 (Auto Headroom Attenuation) │
└──────────────────────────────┬──────────────────────────────┘
▼
┌─────────────────────────────────────────────────────────────┐
│ 7. DC Phase Linearizer │
│ - アナログアンプの低域結合トランス位相歪み再現 │
│ - Type A / Type B (Low / Std / High: 2Hz〜8Hz) │
└──────────────────────────────┬──────────────────────────────┘
▼
┌─────────────────────────────────────────────────────────────┐
│ 8. Dithering & Psychoacoustic Noise Shaping │
│ - TPDF / High-Pass / SBM 模倣 Psychoacoustic シェービング│
│ - LR 独立擬似乱数シードによるステレオ空間拡散 │
└──────────────────────────────┬──────────────────────────────┘
▼
┌─────────────────────────────────────────────────────────────┐
[16bit / 24bit Packed / 32bit PCM 出力 -> DAC / Bluetooth]
└──────────────────────────────┬──────────────────────────────┘

```

---

### 1. ポリフェーズ Sinc FIR アップサンプラー (`dsp_upsampler.cpp`, `dsp_fir_stage.cpp`)
* **ARM NEON SIMD 並列積和演算:**  
  変形ベッセル関数 $I_0(x)$ を用いたカイザー窓（Kaiser Window, $\beta=10.5$）による理想低域通過フィルターをポリフェーズ展開。64bit/128bit レジスタ（`float32x4_t`）および `vmlaq_f32` 命令による 4 サンプル並列演算を行い、高タップ数処理を極めて低い CPU 負荷で実行します。
* **多段カスケード FIR (Cascade FIR Stage):**  
  単一の大規模フィルターで一気にリサンプリングするのではなく、$2\times$ 補間フィルターを多段直列に接続（$2\times$: 255 taps $\rightarrow$ $4\times$: 63 taps $\rightarrow$ $8\times$: 39 taps）。後段へ進むほど帯域外遷移領域が広がる特性を利用してタップ数を最適化し、通過帯域リップル $\pm 0.001\text{dB}$ 以下・阻止帯域減衰量 $-100\text{dB}$ 以上の急峻な遮断特性と群遅延の最小化を両立しています。
* **ケプストラム解析による最小位相化 (Minimum Phase Transform):**  
  周波数領域でログマグニチュードを求め、因果的ケプストラム（Causal Cepstrum）を算出・逆変換することで、直線位相フィルターを同等の振幅特性を持つ最小位相（Minimum Phase）フィルターへと変換。アタック音の前に聴こえる不自然な「プリリンギング（前鳴き）」を物理的に排除し、ドラムのスネアやアコースティックギターの立ち上がり（ポストリンギングのみ）を鮮明にします。
* **4 種類の FIR 特性切り替え:**
  * `Linear Phase Sharp`: リファレンス特性。位相の直線性と通過帯域の平坦性を最優先。
  * `Linear Phase Slow`: 緩やかなロールオフ特性（$\beta=6.0$）により、インパルス応答のリンギング自体を低減。
  * `Minimum Phase Sharp`: アタックの直前ノイズを遮断し、現代的なタイトで高解像度な音像を実現。
  * `Minimum Phase Slow`: 最小位相 ＋ 緩やかな減衰による、滑らかで温かみのあるアナログトーン。

---

### 2. FREQ ENGINE & RICH HARMONICS (`dsp_freq_engine.cpp`)
* **直交多項式展開による高調波外挿:**  
  ハイパスフィルター（初期値: 13kHz〜）で抽出した帯域信号を局所 RMS で正規化し、Chebyshev 型直交多項式（2次・3次・4次）を通じて高調波倍音を合成。原音の周波数構造に数学的整合性を持ったまま、ハイレゾ帯域（20kHz〜40kHz+）へ倍音成分を拡張します。
* **適応型フォルマント & ブレスコンテキスト解析:**  
  ボーカルのエネルギーが集まる 3.2kHz 帯（$Q=1.4$）にバンドパスフィルターを配置し、高域フラックス（エネルギー変動）と連動解析。ボーカルの息づかい（ブレス）や子音（サ行・タ行のシビランス）を動的に判定し、耳障りなピーク歪みを防ぎながらエアー感（4次高調波）を適応付加します。
* **RICH HARMONICS（ふくよか倍音）モード:**  
  * **OFF (リアル Hi-Res):** 19.8kHz 以上の超高域に対して自然な倍音を補完。ハイレゾ DAC の周波数上限を引き出すクリア志向のサウンド。
  * **ON (ふくよか倍音):** 高域抽出周波数を 16.0kHz にシフト。真空管アンプやアナログ回路に多く含まれる**温かみのある偶数次倍音（2次・4次）のブレンド比率を 78% まで引き上げ**、ゲインを $+18\%$ ブースト。ボーカルの芯の太さとふくよかな艶、音場の厚みを付加します。

---

### 3. トランジェント復元 (`dsp_transient.cpp`)
* **Lattice (PARCOR) 格子型適応線形予測:**  
  反射係数 $k_1$ を 1 サンプルごとに適応更新する格子型デジタルフィルターを内蔵。先行するサンプルの相関から次の瞬間の波形変化を予測し、非可逆圧縮（Opus / AAC）によって潰れた打楽器のアタック成分（急峻な立ち上がり）を適応復元します。
* **微分群遅延補正 (Group Delay Compensation):**  
  急峻な過渡変化が発生した瞬間のみ、一次微分値に基づく微小な群遅延シフトを注入。撥弦や打鍵の瞬間における空間の定位感を際立たせます。

---

### 4. 64-bit 倍精度 10-Band EQ (`dsp_equalizer.cpp`)
* **64bit 倍精度浮動小数点（`double`）Direct Form II:**  
  一般的な 32bit 単精度（`float`）フィルターで発生しやすい、低域（31.25Hz / 62.5Hz）での丸め誤差・リミットサイクルノイズを完全追放。
* **Walkman 最適化 Q値 ($Q=1.15$):**  
  SONY WALKMAN の音響設計に倣い、隣接するオクターブバンド間の重なり（相互干渉）による音の濁りを排除する $Q=1.15$ を採用。
* **オートヘッドルームリミッター (Auto Headroom):**  
  ユーザーが設定した 10 バンドのゲインを監視し、正方向の最大ブースト量に応じて $\text{Gain} = 10^{-\frac{\text{maxBoost}}{20}}$ の自動アッテネーションを動的に適用。デジタルドメインでのクリッピング・音割れを 100% 回避します。

---

### 5. DC Phase Linearizer (`dsp_dc_phase.cpp`)
* 伝統的なアナログアンプが持つ低域結合トランスやコンデンサによる微小な位相回転特性を、64bit 倍精度 IIR フィルターによって再現。
* **Type A:** 自然でフラットな低域位相の伸び（Low: 32Hz / Std: 48Hz / High: 70Hz）。
* **Type B:** $Q$ 値を高め、押し出し感とパンチを強調したエンハンスド位相特性（Low: 30Hz / Std: 42Hz / High: 60Hz）。

---

### 6. Anti-Preecho & ビット連続性補正 (`dsp_preecho.cpp`)
* **Anti-Preecho (Ultra HQ モード):**  
  約 3.5ms のルックアヘッドリングバッファを常時展開。直後に巨大なアタック（衝撃波）が控えていることを検知した場合、直前の微小信号に含まれる不自然なプリリンギングをピンポイントでアッテネート。
* **Bit Continuity:**  
  微小振幅領域（$-50\text{dB}$ 以下）における 2 階差分 $\Delta_1 - \Delta_2$ の不連続性を監視し、圧縮コーデック特有の階段状量子化ノイズを滑らかなカーブへと微細補間。

---

### 7. ディザリング & ノイズシェービング (`dsp_upsampler.cpp`)
* **TPDF:** 2系統の独立擬似乱数ジェネレータ（線形合同法）を用いた三角確率密度関数ディザー。
* **High-Pass Shaped:** 1 次差分エラーフィードバックにより、量子化ノイズのエネルギーを可聴外の高域端へシフト。
* **Psychoacoustic:** 4 次エラーフィードバックフィルター（係数: $+2.033, -2.165, +1.959, -0.827$）を採用。人間の最小可聴限界（等ラウドネス曲線）に基づき、耳が最も敏感な 2kHz〜5kHz の量子化ノイズを徹底的にマスキング（Walkman SBM 模倣）。
* **LR 独立シードディザリング:** 左右のチャンネルで異なる擬似乱数シードを用いてノイズ相関をゼロ化し、ヘッドホン試聴時の空間の広がり（音場感）を向上。

---

### 8. 32-Band リアルタイム FFT スペクトラム解析 (`dsp_upsampler.cpp`)
* 2048 点の Hanning 窓 FFT をデュアル展開。
* 低域（31.25Hz〜396Hz）は 2 倍間引き平均（実効分解能 2 倍）で精密解析し、高域は 2048 点フルレートで解析。
* 31.25Hz から 40.0kHz までの全 32 バンドについて真の対数実効値（RMS dBFS）を算出し、ハイレゾ再生時の 20kHz 超の倍音成分まで UI 上にリアルタイム描画します。

---

## 📱 UI コンポーネント & 操作ガイド

### 1. メイン画面トップインフォメーションパネル

| 表示項目 | 仕様・動作説明 |
| :--- | :--- |
| **ステータスバッジ** | ・`DIRECT SOURCE`: 全 DSP を完全バイパスした 1:1 ビットパーフェクト出力<br>・`DIRECT STREAM [DSP nx]`: USB DAC ハードウェア同期 ＋ アップサンプリング動作中<br>・`BT [コーデック名]`: Bluetooth 高音質接続中（例: `BT [LDAC]`, `BT [aptX Adaptive]`）<br>・`STANDARD MIX`: 端末内蔵スピーカー出力中 |
| **DAC / デバイス名** | Android システムから取得した接続先ハードウェアの製品名を表示（例: `Sony Walkman`, `FiiO KA13`, `Bose QC Ultra`）。 |
| **レート & ビット** | 現在 DAC / Bluetooth ドライバへ送出されている PCM レートとビット幅（例: `44.1 kHz / 16 bit`, `96.0 kHz / 24 bit`, `192.0 kHz / 32 bit`）。 |
| **PEAK (L/R)** | 左右チャンネル独立の True Peak レベル（dBFS）。$-50\text{dBFS}$ 以下または停止時は `-inf` 表示。 |
| **BIT: ACTIVE** | 伝送中の PCM 信号の振幅変化から、**現在実際に動作しているビット幅（有効ビット深度）** をリアルタイム算出（例: 24bit 出力時に下位ビットまでデータが乗っているかを判定）。 |
| **WALKMAN アナログピークメーター** | -∞ 〜 0dBFS の対数スケール。高速減衰（140dB/s）ホワイトバー ＋ 約750ms ゴールド Peak ホールド ＋ 0dB 超過時の赤色クリップ警告表示。 |

---

### 2. DSP オーディオ設定ダイアログ（∿ ボタン）

画面右上の **`∿`（Sin波）ボタン** をタップすると、底面から WALKMAN スタイルの DSP 設定シートが展開します。

* **DIRECT SOURCE スイッチ:**  
  全 DSP エンジン（アップサンプラー、EQ、DSEE、DC Phase、ディザー等）を完全にバイパスし、無加工の PCM データを直接 DAC へ送出します。
* **10-BAND EQUALIZER & スペクトラム:**  
  * スプライン補間による滑らかな EQ 曲線と、背景に 32 バンド・リアルタイムスペクトラムアナライザーを同時描画。
  * 右側の **「調整」** ボタンをタップすると編集モードに入り、各バンドの中心周波数（31Hz〜16kHz）を直接ドラッグ、または「＋ / －」ボタン（0.5dB 刻み）で調律可能。
  * **「FLAT」** ボタンで全バンドを一括して 0.0dB にリセット。
  * `SPEC` スイッチでスペクトラムの背景アニメーションを個別に ON/OFF 可能。
* **BIT DEPTH FORMAT:**  
  DAC への PCM 送出フォーマットを `16-bit (Std)` / `24-bit (Hi-Res)` / `32-bit (Int32)` から選択。
* **DITHERING ALGORITHM:**  
  `TPDF (Studio Standard)` / `High-Pass Shaped (Clear)` / `Psychoacoustic (Walkman SBM)` / `None (Direct Bypass)`。
* **DC PHASE LINEARIZER:**  
  `OFF` / `Type A (Low / Std / High)` / `Type B (Low / Std / High)`。
* **FREQ ENGINE (超高域倍音復元):**  
  `OFF` / `Auto AI` / `男性ボーカル` / `女性ボーカル` / `パーカッション` / `ストリングス`。
* **PERFORMANCE PROFILE:**  
  `Eco (省電力)` / `普通 (標準)` / `超高音質 (フルスペック倍音外挿 ＋ プリエコー抑制)`。
* **RICH HARMONICS (ふくよか倍音):**  
  16kHz〜 へ温かみのある偶数次倍音をブレンドし、音に厚みを与えるスイッチ（FREQ が有効な場合のみ動作）。
* **ULTRA HQ UPSAMPLING:**  
  `1x Direct (Bypass)` / `2x Hi-Res (88k/96k)` / `4x Ultra (176k/192k)` / `8x Master (352k/384k)`。
* **MULTISTAGE CASCADE FIR:**  
  3段直列接続（255 $\rightarrow$ 63 $\rightarrow$ 39 taps）による超低歪み補間の ON/OFF。
* **0dB VOLUME LOCK:**  
  USB DAC 接続時のみ有効化可能。Android OS のデジタルアッテネーションを排してシステム音量を 100%（0dB）に固定。音量 UP/DOWN キーがトラックの「次へ / 前へ」操作に切り替わります。

---

### 3. 外観 & システム設定ダイアログ（⚙ ボタン）

画面右上の **`⚙`（歯車）ボタン** をタップして開きます。

* **UI THEME COLOR:**  
  * `Dark (ダーク)`: WALKMAN の伝統的な漆黒基調テーマ。
  * `Light (ライト)`: アルミニウムシルバーを思わせる白・ライトグレー基調テーマ。
  * `Auto (端末設定連動)`: Android システムのダークテーマ設定に完全追従。
  * *※ ライトモード選択時は、YouTube Music の Web 画面も内蔵 CSS によりスマート反転（アルバムジャケットや動画のフルカラーを 100% 維持したまま完全白基調化）されます。*
* **BACKGROUND PROTECTION (バッテリー最適化無効化):**  
  「バッテリー使用量を『無制限』に設定」ボタンをタップすることで、OS の省電力機能による画面消灯時の再生停止を一括解除できます。
* **AD CUT (広告自動カット):**  
  YouTube Music の動画広告要素を検知し、音声を瞬時にミュートして高速スキップを実行します。

---

### 4. 隠し機能: 開発者プリセットチューナー（∿ ボタン長押し）

メイン画面右上の **`∿`（Sin波）ボタンを長押し** すると、FREQ プリセットの内部パラメータを完全解放する **`DEVELOPER PRESET TUNER`** が起動します。

* **TARGET PRESET:** チューニング対象のプリセット（Auto AI / 各楽器・ボーカル）を選択。
* **調整可能パラメータ:**  
  * FIR Filter Character（Linear / Minimum, Sharp / Slow）
  * Transient Recovery Mode（Natural / Punch / Acoustic）
  * LPC Spectral Algorithm（DSEE HX AI / K2 LPC / Adaptive Exciter）
  * Gain（0.16 〜 0.26） / Extract Frequency（10,000Hz 〜 13,800Hz）
  * QMF サブバンド Noise-to-Tone 分離スイッチ
  * トランジェント群遅延補正（Group Delay）スイッチ
  * 格子型適応過渡予測（Lattice PARCOR）スイッチ
  * LR 独立シードディザリング（空間拡散）スイッチ
  * M/S 空間・残響超解像（Spatial Ambience）スイッチ
* **📋 全プリセット設定コードをコピー:**  
  チューニングした全パラメータを、Kotlin の `FreqPresetDef(...)` コード形式でクリップボードへ一括書き出し可能（ソースコードへの組み込みが容易）。

---

### 5. ホーム画面プレイヤーウィジェット (`PlayerWidgetProvider`)

* **サイズ:** 4x1（リサイズ可能）。
* **表示内容:** アルバムアートワーク（角丸）、楽曲タイトル、アーティスト名、現在時間 / 総時間、ゴールドプログレスバー。
* **操作ボタン:** 前へ、再生 / 一時停止、次へ。タップでメイン画面を即時呼び出し。
* **ウィジェット個別テーマ設定:**  
  ウィジェット配置時に表示される `PlayerWidgetConfigureActivity` により、ウィジェットごとに個別で `Dark` / `Light` / `System Auto` のカラーリングを指定可能。

---

## 🎧 Bluetooth / USB DAC 推奨設定ガイド

### 1. USB DAC 接続時（ビットパーフェクト最優先）
1. 端末に USB DAC を接続し、メイン画面のバッジが **`DIRECT STREAM`** または **`DIRECT SOURCE`** に変化することを確認。
2. 設定メニュー（`∿`）を開き、原音重視（完全 1:1 伝送）の場合は **`DIRECT SOURCE`** を ON。
3. アップサンプリングを行いたい場合は `DIRECT SOURCE` を OFF、`ULTRA HQ UPSAMPLING` を **`4x (176k/192k)`** または **`8x (352k/384k)`**、`MULTISTAGE CASCADE FIR` を ON に設定。
4. **`0dB VOLUME LOCK`** を ON に設定（Android 内部のデジタルボリューム減衰を排除し、USB DAC 本体のハードウェアボリュームで音量を調整）。

### 2. Bluetooth 接続時（LDAC / aptX Adaptive / aptX Lossless 推奨）
1. Android の `設定` ＞ `接続済みのデバイス` ＞ 接続先イヤホンの設定（歯車）を開き、**「LDAC」** または **「HD オーディオ」** を有効化。
2. メーカー専用コンパニオンアプリ（Sony Sound Connect、Technics Audio Connect 等）で、Bluetooth 接続品質を **「音質優先（Priority on Sound Quality: 990kbps / 96kHz）」** に指定。
3. 本アプリを開き、ステータスバッジが **`BT [LDAC]`** や **`BT [aptX Adaptive]`** と表示されていることを確認。
4. 設定メニューで `BIT DEPTH FORMAT` を **`24-bit (Hi-Res)`** に設定。
5. `ULTRA HQ UPSAMPLING` を **`2x (88.2k/96k)`**、`RICH HARMONICS` を ON に設定することで、Bluetooth 伝送上限（96kHz / 24bit）を限界まで活用した厚みのあるサウンドが得られます。

---

## 📂 リポジトリ構成

```text
Perfect_Bit_Rate/
├── app/
│   ├── build.gradle.kts                 # NDK, CMake, GeckoView, Media3 依存関係
│   ├── src/main/
│   │   ├── AndroidManifest.xml          # Foreground Service, Bluetooth, WakeLock 権限
│   │   ├── assets/yt_capture_extension/
│   │   │   ├── content.js               # Web Audio API キャプチャ & 広告スキップ & 反転CSS
│   │   │   └── manifest.json            # GeckoView 組み込み WebExtension 定義
│   │   ├── cpp/                         # C++17 ARM NEON ネイティブ DSP エンジン
│   │   │   ├── CMakeLists.txt           # -O3, -ffast-math, -flto, 16KB Page Alignment
│   │   │   ├── aaudio_engine.cpp / .h   # AAudio 排他モード & Lock-Free RingBuffer
│   │   │   ├── dsp_types.h              # アライメントアロケータ, 共通定数・列挙型
│   │   │   ├── dsp_equalizer.cpp / .h   # 64-bit 倍精度 10-Band Biquad EQ (Auto Headroom)
│   │   │   ├── dsp_fir_stage.cpp / .h   # 単段 2x NEON FIR (多段カスケード用)
│   │   │   ├── dsp_freq_engine.cpp / .h # FREQ ENGINE (超高域倍音復元 & Rich Harmonics)
│   │   │   ├── dsp_transient.cpp / .h   # トランジェント復元 (Lattice PARCOR & Group Delay)
│   │   │   ├── dsp_preecho.cpp / .h     # プリエコー抑制 & 微小振幅ビット連続性補正
│   │   │   ├── dsp_dc_phase.cpp / .h    # DC Phase Linearizer (低域アナログ位相)
│   │   │   ├── dsp_upsampler.cpp / .h   # 統合アップサンプラー, FFT スペクトラム, ディザ
│   │   │   └── jni_bridge.cpp           # Kotlin ↔ C++ JNI インターフェース
│   │   ├── java/com/example/perfectbitrate/
│   │   │   ├── AppPreferences.kt        # SharedPreferences シングルトン管理
│   │   │   ├── BitPerfectPlaybackService.kt # Foreground 再生サービス, AudioTrack 制御
│   │   │   ├── BluetoothCodecTracker.kt # A2DP コーデックリアルタイム解析 (Reflection)
│   │   │   ├── DevPresetsDialog.kt      # 開発者向け DSP チューニングダイアログ
│   │   │   ├── DspSettingsDialog.kt     # DSP オーディオ設定 BottomSheet
│   │   │   ├── FreqPresetManager.kt     # プリセット定義 & JNI 反映 & UI 同期
│   │   │   ├── GeckoSessionController.kt# GeckoView & WebExtension Native Messaging 制御
│   │   │   ├── MainActivity.kt          # メイン画面, デバイス検知, UI ループ
│   │   │   ├── NativeAudioEngine.kt     # JNI external メソッド宣言シングルトン
│   │   │   ├── PlayerDialogController.kt# ダイアログプレイヤー共通インターフェース
│   │   │   ├── PlayerWidgetConfigureActivity.kt # ウィジェット設定画面
│   │   │   ├── PlayerWidgetProvider.kt  # ホーム画面プレイヤーウィジェット
│   │   │   ├── UiSettingsDialog.kt      # 外観 & システム設定 BottomSheet
│   │   │   ├── WalkmanEqView.kt         # SONY WALKMAN 風 EQ 曲線 & スペクトラムカスタム View
│   │   │   └── WalkmanLevelMeterView.kt # 実機再現高速減衰ピークレベルメーター
│   │   └── res/                         # レイアウト, ドローアブル, テーマ, カラー
└── gradle/                              # Gradle Wrapper & バージョンカタログ (libs.versions.toml)

```

---
## 📦 ビルド & 導入手順

### 1. 前提環境
本プロジェクトのビルドには、以下の環境が必要です。

* **OS:** macOS / Linux / Windows
* **Android Studio:** Ladybug (2024.2.1) 以降推奨
* **Android SDK:**
  * **Compile SDK:** 34 (Android 14)
  * **Target SDK:** 34
  * **Min SDK:** 33 (Android 13)
* **Android NDK:** 25.x 〜 27.x（Side-by-Side NDK）
* **CMake:** 3.22.1 以上
* **JDK:** 17（Java 11 コンパイルターゲット）
* **ネットワーク環境:** GeckoView Maven リポジトリ（`maven.mozilla.org`）および JitPack へのアクセス環境

---

### 2. クローン & ビルド手順

#### ① リポジトリの取得
```bash
git clone https://github.com/<your-username>/Perfect_Bit_Rate.git
cd Perfect_Bit_Rate
```

#### ② Gradle ビルド（コマンドライン）
```bash
# macOS / Linux
./gradlew assembleDebug

# Windows (PowerShell / CMD)
.\gradlew.bat assembleDebug
```

#### ③ 接続端末へのインストール
USB デバッグを有効化した Android 端末を PC に接続し、以下のコマンドを実行します。
```bash
# macOS / Linux
./gradlew installDebug

# Windows
.\gradlew.bat installDebug
```
※ ビルドされた APK は app/build/outputs/apk/debug/app-debug.apk に出力されます。

---

### 3. 初回起動時の初期設定・推奨手順

#### ① システム権限の付与
アプリを初めて起動した際、以下のダイアログが表示されたら許可してください。
* **通知の送信 (`POST_NOTIFICATIONS`):**  
  バックグラウンド再生コントロール通知（再生/一時停止、トラック情報、シークバー）を表示し、OS によるサービス強制終了を防ぐために必須です。
* **付近のデバイスへの接続 (`BLUETOOTH_CONNECT`):**  
  接続されている Bluetooth オーディオ機器の高度なコーデック状態（LDAC / aptX Adaptive / AAC 等）をリアルタイム判定・バッジ表示するために必要です。

#### ② バックグラウンド再生保護（バッテリー最適化の無制限化）
画面消灯時や別アプリ使用時に再生が途切れるのを完全に防ぐため、以下の設定を行ってください。
1. 画面右上の **`⚙`（設定）ボタン** をタップして外観＆システム設定ダイアログを開きます。
2. 「BACKGROUND PROTECTION」内にある **「バッテリー使用量を『無制限』に設定」** ボタンをタップします。
3. システム設定画面が開いたら、本アプリのバッテリー使用プロファイルを **「無制限 (Unrestricted)」** に設定してください。

#### ③ USB DAC 接続時の推奨設定（ビットパーフェクト / 0dB ロック）
1. 端末の USB-C ポートに USB DAC を接続します。
2. 上部インフォメーションパネルのバッジが **`DIRECT STREAM`** または **`DIRECT SOURCE`** に変化し、接続先 DAC 名（例: `WALKMAN`, `FiiO KA13` 等）が表示されていることを確認します。
3. 画面右上の **`∿`（DSP設定）ボタン** をタップします。
4. 原音そのままの 1:1 出力を行いたい場合は **`DIRECT SOURCE`** を ON にします。
5. **`0dB VOLUME LOCK`** を ON に設定します。  
   *※ Android OS 内部のデジタルアッテネーション（ビット落ち）が排除され、システム音量が 100%（0dB）に固定されます。音量調整は USB DAC 本体のハードウェアボリュームで行ってください。なお、DAC やイヤホンを抜去した際は `ACTION_AUDIO_BECOMING_NOISY` セーフガードにより音量が瞬時に「0」へ自動リセットされます。*

#### ④ Bluetooth 接続時の推奨設定（LDAC / aptX 高音質化）
1. 接続先イヤホン・ヘッドホンのコンパニオンアプリ（Sony Sound Connect、Technics Audio Connect 等）で、接続モードを **「音質優先（Priority on Sound Quality: 990kbps / 96kHz）」** に指定します。
2. Android の `設定` ＞ `接続済みのデバイス` ＞ 接続先デバイスの歯車アイコンから **「LDAC」** または **「HD オーディオ」** が有効になっていることを確認します。
3. 本アプリを開き、ステータスバッジに **`BT [LDAC]`** や **`BT [aptX Adaptive]`** と表示されていることを確認します。
4. 画面右上の **`∿`（DSP設定）ボタン** を開き、`BIT DEPTH FORMAT` を **`24-bit (Hi-Res)`** に設定。お好みで `RICH HARMONICS (ふくよか倍音)` を ON にすることで、Bluetooth 伝送帯域を活かした厚みのあるサウンドが得られます。

#### ⑤ ホーム画面ウィジェットの配置
1. 端末のホーム画面の空きスペースを長押しし、「ウィジェット」一覧から **「Perfect Bit Rate」** を選択して配置します。
2. ウィジェット設定画面（`PlayerWidgetConfigureActivity`）が起動したら、お好みの外観テーマ（`Dark` / `Light` / `System Auto`）を選択して「設定を保存して適用」をタップします。

---

### 4. 開発・トラブルシューティング

> [!IMPORTANT]
> **WebExtension キャッシュの更新について:**  
> `app/src/main/assets/yt_capture_extension/content.js` などの拡張機能スクリプトを改修した場合、GeckoView 内部のキャッシュが保持されることがあります。コード変更を確実に反映させるため、**端末から古いアプリを一度完全にアンインストールしてから再インストール** してください。

> [!NOTE]
> **GeckoView の依存関係解決エラーが発生する場合:**  
> 本プロジェクトは `settings.gradle.kts` にて Mozilla 公式 Maven リポジトリ（`https://maven.mozilla.org/maven2/`）を指定しています。初回ビルド時に GeckoView ライブラリ（約 150MB）のダウンロードが行われるため、安定した通信環境でビルドを実行してください。

> [!TIP]
> **Android 15+ 16KB ページサイズ対応について:**  
> 本プロジェクトの `CMakeLists.txt` には `-Wl,-z,max-page-size=16384` が組み込まれており、次世代の 16KB ページサイズ環境（Android 15 以降のフラグシップ端末等）でもネイティブライブラリ（`.so`）がエラーなくロードされるよう設計されています。

---

## ⚠️ 免責事項
* 本ソフトウェアは個人利用および学術研究・音質検証を目的として開発されたオープンソースソフトウェアです。
* YouTube および YouTube Music は Google LLC の登録商標です。本プロジェクトは Google LLC とは一切関係ありません。
* 本アプリに搭載されている広告スキップ機能等は、ユーザー環境における Web ページの表示・再生補助を目的とした技術的実装です。

---

## 📜 ライセンス & 謝辞

本プロジェクトは **MIT License** のもとで公開されています。

```text
MIT License

Copyright (c) 2026 Perfect Bit Rate Contributors

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```
---

## 謝辞

本アプリケーションに搭載されている多段カスケード FIR アップサンプラーおよび関連するデジタル音響信号処理アルゴリズムの一部は、**Michihito Takami** 氏のオープンソースプロジェクト『**totton-audio-de-mirroring**』で公開されている先進的な信号処理技術に深く影響を受けています。  
素晴らしい研究成果と有益なコードをオープンソースとして公開してくださった作者に、心より敬意と感謝を表します。

```text
* **totton-audio-de-mirroring** by Michihito Takami
  * **GitHub:** [https://github.com/michihitoTakami/totton-audio-de-mirroring](https://github.com/michihitoTakami/totton-audio-de-mirroring)
  * **License:** MIT License
```
