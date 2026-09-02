# Perfect Bit Rate 🎵
### Android Bit-Perfect Audio Player for YouTube Music (USB DAC / Bluetooth / Internal DSP)

![Android 14+](https://img.shields.io/badge/Android-14%2B%20%2F%20API%2033%2B-brightgreen.svg)
![Kotlin](https://img.shields.io/badge/Kotlin-2.0-purple.svg)
![C++17](https://img.shields.io/badge/C%2B%2B-17%20(NEON%20%2F%20SIMD)-orange.svg)
![License](https://img.shields.io/badge/License-MIT-blue.svg)

**Perfect Bit Rate** は、Android OS 標準ミキサー（AudioFlinger）による非整数倍リサンプリング（SRC劣化）を完全にバイパスし、YouTube Music の音声を **1:1 ビットパーフェクト伝送** または **スタジオ級 64bit 高品位 DSP（アップサンプリング / DSEE / DC Phase / 10-Band EQ）** を通して USB DAC や Bluetooth 機器へ出力するための専用オーディオプレイヤーアプリケーションです。

---

## 📌 目次
1. [プロジェクト概要と解決する課題](#-プロジェクト概要と解決する課題)
2. [主要機能一覧](#-主要機能一覧)
3. [システムアーキテクチャ & パイプライン](#-システムアーキテクチャ--パイプライン)
4. [C++ DSP エンジン詳細](#-c-dsp-エンジン詳細)
5. [主要コンポーネント構成](#-主要コンポーネント構成)
6. [UI & 操作ガイド](#-ui--操作ガイド)
7. [Bluetooth / USB DAC 最適設定](#-bluetooth--usb-dac-最適設定)
8. [ビルド & 導入手順](#-ビルド--導入手順)

---

## 🎵 プロジェクト概要と解決する課題

### 1. Android OS の「強制 SRC（サンプリングレート変換）問題」
一般的な Android OS は、すべてのオーディオ出力を内部ミキサー（AudioFlinger）で固定サンプリングレート（48kHz または Galaxy 等の 192kHz）に強制リサンプリングします。
YouTube Music に多い **44.1kHz（AAC）音源を 48kHz や 192kHz に変換する非整数倍補間処理** により、以下の問題が発生します。
* 微小信号の欠落・音場（ステレオイメージ）の平坦化
* 高域の位相ズレ・エイリアシング（折り返し）ノイズ
* トランジェント（アタック感・立ち上がり）の鈍化

### 2. 本アプリのアプローチ
* **Android 14+ ハードウェアクロック同期 (`setPreferredMixerAttributes`):**  
  USB DAC 接続時に `MIXER_BEHAVIOR_BIT_PERFECT` を要求し、音源のネイティブサンプリングレート（44.1kHz / 48kHz）に合わせて DAC 内部の物理クロックを切り替えます。
* **C++ AAudio 排他モードフォールバック:**  
  レガシー環境や直接出力時、`AAUDIO_SHARING_MODE_EXCLUSIVE` かつ `AAUDIO_PERFORMANCE_MODE_NONE`（低遅延 MMAP の 48kHz 固定を回避）で ALSA/HAL へ直結します。
* **スタジオ級 64bit DSP パイプライン:**  
  ダイレクト再生だけでなく、ARM NEON 最適化のポリフェーズ Sinc アップサンプリング（最大 8x / 384kHz）、倍精度 10-Band EQ、高域倍音補完（DSEE / K2 模倣）、DC Phase Linearizer をリアルタイム適用可能です。

---

## ✨ 主要機能一覧

* **ビットパーフェクト / ネイティブクロック自動追従:**  
  Opus（48kHz）と AAC（44.1kHz）をリアルタイムに自動判定し、DAC のクロックを自動シフト。
* **Direct Source モード (完全バイパス):**  
  DSP パイプラインをすべてパスし、Web Audio キャプチャから DAC まで無加工・無劣化でストリーミング。
* **ARM NEON ポリフェーズ Sinc アップサンプラー:**  
  1x（Bypass）、2x（88.2k/96k）、4x（176.4k/192k）、8x（352.8k/384k）の超高タップ数リアルタイムリサンプリング。
* **4 種類の FIR フィルター特性切り替え:**  
  直線位相（Linear Phase Sharp / Slow）および最小位相（Minimum Phase Sharp / Slow）から音色を選択可能。
* **高域倍音復元（HIGH-FREQ RESTORATION）:**  
  圧縮音源でカットされがちな 20kHz〜40kHz+ の超高域成分を線形予測（LPC）および過渡応答解析から適応生成（DSEE HX AI / K2 LPC / Adaptive Exciter）。
* **DC Phase Linearizer:**  
  伝統的なアナログアンプ特有の低域位相シフトを 64bit IIR で再現（Type A / Type B、各 Low / Std / High）。
* **64bit 倍精度 10-Band イコライザー & オートヘッドルーム:**  
  音質劣化や桁落ちノイズのない 64bit 浮動小数点フィルター。ブースト時の音割れを防止する自動アッテネーション機構を内蔵。
* **SONY WALKMAN 実機再現 UI:**  
  ホワイトバー ＋ ゴールド Peak ホールド（-∞ 〜 0dBFS）レベルメーター（低負荷 60fps 描画）と、スプライン補間による 10-Band EQ コントローラー。
* **0dB Volume Lock & 即時安全遮断:**  
  USB DAC 接続時は Android のデジタル減衰を排した 0dB（100%）固定出力。DAC やイヤホン抜去時（`ACTION_AUDIO_BECOMING_NOISY`）は瞬時に音量を「0」へ強制リセット。
* **バックグラウンド再生 & 広告自動スキップ (AD CUT):**  
  画面消灯時も GeckoView をアクティブ偽装してバックグラウンド再生を維持。システム通知・ロックスクリーンからの完全メディア操作対応。

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
│ - Opus (48kHz) / AAC (44.1kHz) リアルタイム判定           　　│
│ - Float32 PCM キャプチャ (ScriptProcessorNode)               │
│ - 広告要素スキップ / 再生維持ハンドシェイク             　　    │
└──────────────────────────────┬──────────────────────────────┘
                               │ Native Messaging (IPC Port)
┌──────────────────────────────▼──────────────────────────────┐
│                    MainActivity.kt                          │
│ - ノンブロッキング Base64 デコード                            │
│ - オーディオデバイス優先度ルーティング判定                      │
│ - WALKMAN 風 UI / レベルメーター更新 (100ms スロットル)        │
└──────────────────────────────┬──────────────────────────────┘
                               │ pushPcm(...)
┌──────────────────────────────▼──────────────────────────────┐
│          BitPerfectPlaybackService.kt (Service)             │
│ - バックグラウンドスレッド (Thread.MAX_PRIORITY)              │
│ - AudioTrack (MODE_STREAM) 制御                             │
│ - MediaSessionCompat (通知・ロックスクリーン操作)             │
└──────────────────────────────┬──────────────────────────────┘
                               │ JNI (nativeProcessUpsample)
┌──────────────────────────────▼──────────────────────────────┐
│                 C++ Native DSP Engine                       │
│  [Step 1] Polyphase Sinc FIR Upsampling (NEON 1x/2x/4x/8x)  │
│  [Step 2] 64-bit 10-Band IIR Equalizer (Auto Headroom)      │
│  [Step 3] DC Phase Linearizer (Analog Low-End Phase)        │
│  [Step 4] High-Freq Restoration (DSEE / K2 LPC 20k~40kHz+)  │
│  [Step 5] Dithering (TPDF / High-Pass / Psychoacoustic SBM) │
└──────────────────────────────┬──────────────────────────────┘
                               │
               ┌───────────────┴───────────────┐
               ▼                               ▼
┌──────────────────────────────┐ ┌─────────────────────────────┐
│  Android 14+ USB DAC 出力    │ │ Bluetooth (LDAC / aptX等)    │
└───────────────────────────────────────────────────────────── ┘
│ - setPreferredMixerAttributes│ │ - OS二重SRCバイパス          │
│ - 44.1k / 48k ハードクロック  │ │ - 24-bit PCM 直結            │
└──────────────────────────────┘ └─────────────────────────────┘

## 🎛 C++ DSP エンジン詳細

### 1. ポリフェーズ Sinc FIR アップサンプラー (`dsp_upsampler.cpp`)
* カイザー窓（Kaiser Window）に基づく理想ローパスフィルターをポリフェーズ展開。
* ARM NEON SIMD 命令（`vmlaq_f32`）を用いて 4 サンプル並列積和演算を実行し、モバイル端末での超低レイテンシ・低発熱動作を実現。
* **FIR特性切り替え:**
  * `Linear Phase Sharp`: リファレンス特性。周波数応答の平坦性を最優先。
  * `Linear Phase Slow`: 緩やかなロールオフでプリリンギングを抑制。
  * `Minimum Phase Sharp`: ケプストラム解析により最小位相化。ポストリンギングのみとし、音の立ち上がり（アタック）を強化。
  * `Minimum Phase Slow`: 最小位相 ＋ 緩やかな減衰によるウォームなアナログトーン。

### 2. DSEE HX AI / K2 LPC 超高域補完 (`dsp_upsampler.cpp`)
* 10kHz 以上の高域成分をハイパス抽出し、過渡応答（Transient Flux）と線形予測係数（LPC Alpha）をリアルタイム解析。
* アップサンプリングで拡張された高域空間（20kHz〜40kHz+）に対して、原音の倍音構造に調和する倍音を動的に外挿生成。

### 3. DC Phase Linearizer (`dsp_upsampler.cpp`)
* アナログトランスやコンデンサ結合を持つ真空管・トランジスタアンプの低域位相特性を 64bit 倍精度バイカッドフィルターで再現。
* Type A（標準的な位相特性）/ Type B（エンハンス特性）の各カットオフ（2Hz / 4Hz / 8Hz）を選択可能。

### 4. ディザリング & ノイズシェービング (`dsp_upsampler.cpp`)
* 32bit 浮動小数点から 16bit / 24bit PCM への丸め込み時に発生する量子化歪みを排除。
* **TPDF:** 三角分布ディザー。
* **High-Pass Shaped:** 可聴帯域外（高域）へ量子化ノイズをシフト。
* **Psychoacoustic:** 人間の等ラウドネス曲線を考慮し、聴感上の残留ノイズ感を極限まで抑える Walkman SBM 模倣アルゴリズム。

---

## 📂 主要コンポーネント構成

```text
app/src/main/
├── AndroidManifest.xml                  # 権限 (Foreground Service, BT Connect, WakeLock)
├── assets/yt_capture_extension/
│   ├── content.js                       # Web Audio API PCMキャプチャ & 広告スキップ & 偽装スクリプト
│   └── manifest.json                    # GeckoView WebExtension 定義
├── cpp/
│   ├── CMakeLists.txt                   # C++17, -O3, ARM NEON, LTO ビルド定義
│   ├── aaudio_engine.cpp / .h           # C++ AAudio 排他モードエンジン (Lock-Free RingBuffer)
│   ├── dsp_equalizer.cpp / .h           # 64-bit 倍精度 10-Band Biquad EQ (Auto Headroom)
│   ├── dsp_upsampler.cpp / .h           # ポリフェーズアップサンプラー, DSEE, DC Phase, ディザリング
│   └── jni_bridge.cpp                   # Kotlin ↔ C++ JNI インターフェース
├── java/com/example/perfectbitrate/
│   ├── BitPerfectPlaybackService.kt     # バックグラウンド再生, AudioTrack 制御, DAC クロック同期
│   ├── DirectPcmAudioSink.kt            # Media3 (ExoPlayer) 用カスタム AudioSink 実装
│   ├── MainActivity.kt                  # GeckoView 制御, UI イベント, デバイスルーティング
│   ├── NativeAudioEngine.kt             # JNI 宣言シングルトン
│   ├── PlayerManager.kt                 # ExoPlayer / DirectAudioSink 管理
│   ├── WalkmanEqView.kt                 # SONY WALKMAN 風 10-Band EQ スプライン曲線カスタム View
│   ├── WalkmanLevelMeterView.kt         # 実機完全再現ホワイト/ゴールド高速減衰レベルメーター
│   └── YouTubeStreamHelper.kt           # InnerTube API クライアント (フォールバック用)
└── res/
    ├── layout/                          # メイン画面, DSP設定 BottomSheet, スピナー用レイアウト
    └── drawable/                        # WALKMAN 風トグルスイッチ, バッジ, ボタン背景
