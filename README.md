# Perfect Bit Rate (Android UAC1 / Bluetooth Bit-Perfect Audio Player) 🎵

![Android 14+](https://img.shields.io/badge/Android-14%2B-brightgreen.svg)
![License](https://img.shields.io/badge/License-MIT-blue.svg)
![Kotlin](https://img.shields.io/badge/Kotlin-1.9%2B-purple.svg)
![C++17](https://img.shields.io/badge/C%2B%2B-17-orange.svg)

Android 端末と USB DAC（UAC1/UAC2）および高音質 Bluetooth 機器を組み合わせ、**YouTube Music を Android OS の SRC（サンプリングレートコンバーター）をバイパスして最高音質（ビットパーフェクト / ハードウェアクロック同期）で再生するための専用アプリケーション**です。

---

## 📌 目次
1. [プロジェクトの背景と解決する課題](#-プロジェクトの背景と解決する課題)
2. [主要機能](#-主要機能)
3. [システムアーキテクチャ・動作ロジック](#-システムアーキテクチャ動作ロジック)
4. [各部の詳細設計](#-各部の詳細設計)
   - [1. 音声キャプチャエンジン (`content.js`)](#1-音声キャプチャエンジン-contentjs)
   - [2. 再生エンジン & DACクロック同期 (`BitPerfectPlaybackService.kt`)](#2-再生エンジン--dacクロック同期-bitperfectplaybackservicekt)
   - [3. デバイス自動ルーティング & 安全遮断保護](#3-デバイス自動ルーティング--安全遮断保護)
   - [4. SONY WALKMAN 風 グラフィカル・レベルメーター (`WalkmanLevelMeterView.kt`)](#4-sony-walkman-風-グラフィカルレベルメーター-walkmanlevelmeterviewkt)
5. [UI解説と設定ガイド](#-ui解説と設定ガイド)
6. [Bluetooth 最高音質設定ガイド (LDAC / aptX)](#-bluetooth-最高音質設定ガイド-ldac--aptx)
7. [動作確認済み環境](#-動作確認済み環境)
8. [ビルド & インストール手順](#-ビルド--インストール手順)

---

## 🎵 プロジェクトの背景と解決するもの

### Androidオーディオの「SRC劣化問題」
通常の Android OS は、すべての音声を内部ミキサー（AudioFlinger）で固定サンプリングレート（通常 48kHz または Galaxy 等の 192kHz）に強制リサンプリングします。
**44.1kHz 音源を 48kHz や 192kHz に変換する処理は「非整数倍補間（割り切れない計算）」** となり、以下の深刻な音質劣化を引き起こします：
* 微細な音情報の欠落・音場の平坦化
* 高域の位相ズレ・濁り（エイリアシングノイズ）
* トランジェント（音の立ち上がり・アタック感）の鈍化

### 本アプリの解決アプローチ
本アプリは **Android 14+（API 34）の最新オーディオ API `setPreferredMixerAttributes`** および **C++ AAudio 排他モード** を使用し、音源のネイティブサンプリングレート（44.1kHz / 48kHz）に合わせて **USB DAC を完全同期** させます。
これにより、YouTube Music の音声を一切のデジタル劣化なく 1:1（ビットパーフェクト）で DAC チップへ届けることを実現しています。

---

## ✨ 主要機能

* **44.1kHz / 48kHz ネイティブ自動クロック切り替え:**  
  Opus（48kHz）と AAC（44.1kHz）をリアルタイムに自動判別し、DAC のハードウェアクロックを瞬時にシフト。
* **16bit / 24bit Hi-Res / 32bit Float 出力モード:**  
  24bit Packed および 32bit Float 伝送に対応し、デジタルクリッピング（音割れ）を完全排除。
* **Bluetooth 高音質ダイレクト伝送:**  
  Android の二重 SRC を排除し、綺麗な PCM ストリームを生のまま LDAC / aptX 等のエンコーダーへ直結。
* **SONY WALKMAN 実機完全再現レベルメーター:**  
  実機の USB DAC 画面に準拠したホワイトバー ＋ ゴールド Peak ホールド（-∞ 〜 0 dBFS）を低負荷 Canvas で 60fps 描画。
* **0dB Volume Lock & 即時安全遮断（ゼロ音量保護）:**  
  DAC 接続時はビット落ちのないフルスケール（1.0 / 0dB）出力。DAC やイヤホンが抜けた瞬間は即座に音量を「0」にして大音量を防止。
* **シームレス・ホットプラグ対応:**  
  音楽再生中に DAC を挿抜しても、アプリがフリーズすることなく Bluetooth ↔ USB DAC ↔ スピーカー間をシームレスに自動移行。
* **広告スキップ & バックグラウンド再生:**  
  YouTube Music の広告自動スキップおよび通知パネル・ロックスクリーンでの完全メディアコントロール。

---

## 🏗 システムアーキテクチャ・動作ロジック

```text
┌─────────────────────────────────────────────────────────────┐
│                    YouTube Music Web                        │
│                (GeckoView Mobile Browser)                   │
└──────────────────────────────┬──────────────────────────────┘
                               │ Web Audio API (createMediaElementSource)
┌──────────────────────────────▼──────────────────────────────┐
│               WebExtension Engine (content.js)              │
│ - コーデック/レート即時スキャン (Opus 48k / AAC 44.1k)      │
│ - 16bit / 24bit / 32bit Float PCM エンコード                │
│ - 超高速 Base64 パケット生成 (8KB チャンク)                 │
└──────────────────────────────┬──────────────────────────────┘
                               │ Native Messaging (IPC Port)
┌──────────────────────────────▼──────────────────────────────┐
│                       MainActivity.kt                       │
│ - スレッド完全分離 (UIスレッドはノンブロッキング)           │
│ - 300ms スマート・スロットリング UI更新                     │
│ - デバイス優先度判定 (USB DAC > Bluetooth > スピーカー)     │
└──────────────────────────────┬──────────────────────────────┘
                               │ pushPcm(pcmBytes)
┌──────────────────────────────▼──────────────────────────────┐
│             BitPerfectPlaybackService.kt (Service)          │
│ - バックグラウンド再生スレッド (MAX_PRIORITY)               │
│ - True Peak リアルタイム高速計算                            │
│ - NOISY 即時安全遮断レシーバー (音量0化)                    │
└──────────────┬──────────────────────────────┬───────────────┘
               │ (Android 14+)                │ (Android 13以下)
┌──────────────▼──────────────┐ ┌─────────────▼───────────────┐
│   AudioTrack (MODE_STREAM)  │ │ NativeAudioEngine (C++ JNI) │
│ - setPreferredMixer         │ │ - AAUDIO_SHARING_EXCLUSIVE │
│ - setPreferredDevice        │ │ - Lock-Free RingBuffer      │
└──────────────┬──────────────┘ └─────────────┬───────────────┘
               │                              │
┌──────────────▼──────────────────────────────▼───────────────┐
│                   USB DAC / Bluetooth / 出力                │
└─────────────────────────────────────────────────────────────┘
```

---

## 🔧 各部の詳細設計

### 1. 音声キャプチャエンジン (`content.js`)
* **完全な親フレーム限定バインド**: `window.self !== window.top` ガードにより、広告用 iframe による通信ポートの横取りを完全防止。
* **動的周波数シフト**:
  * **Opus音源検知時**: `48,000Hz PCM` を 1:1 ダイレクト転送。
  * **AAC音源検知時**: `44,100Hz` 用の PCM サンプルレート補間を行い、DAC へ 44.1kHz データを供給。
* **自動再接続ポート**: `onDisconnect` を監視し、セッション開始やページ遷移時も切断ゼロで自動再ハンドシェイク。

### 2. 再生エンジン & DACクロック同期 (`BitPerfectPlaybackService.kt`)
* **Android 14+ ハードウェアクロックロック (`setPreferredMixerAttributes`)**:
  * USB DAC 接続時、対象デバイスに対して `MIXER_BEHAVIOR_BIT_PERFECT` を要求。OS のミキサーを音源レート（44.1kHz / 48kHz）へ強制ロック。
* **Android 13 以下 C++ AAudio 排他モードフォールバック**:
  * `AAUDIO_SHARING_MODE_EXCLUSIVE` かつ `AAUDIO_PERFORMANCE_MODE_NONE`（低遅延 MMAP の 48k 固定を回避）で ALSA / HAL へ直結。
* **ノンブロッキング非同期スレッド設計**:
  * デバイス初期化を専用バックグラウンドエグゼキュータ (`trackExecutor`) へ移管し、再生中ホットプラグ時のスレッド間デッドロックを完全排除。

### 3. デバイス自動ルーティング & 安全遮断保護

#### 【出力デバイスの優先順位】
1. **`[第1優先]` USB DAC (`TYPE_USB_DEVICE` / `TYPE_USB_HEADSET`)**
   * └─► **DIRECT STREAM バッジ (Gold点灯)**, ハードウェアクロック完全同期
2. **`[第2優先]` Bluetooth (`TYPE_BLUETOOTH_A2DP` / `TYPE_BLE_HEADSET`)**
   * └─► **BLUETOOTH バッジ (Cyan点灯)**, OS二重SRCバイパス高音質伝送
3. **`[第3優先]` 内蔵スピーカー (未接続時)**
   * └─► **STANDARD MIX バッジ (Gray点灯)**, 音量ゼロ安全待機

> [!CAUTION]
> **即時安全遮断 (`ACTION_AUDIO_BECOMING_NOISY`)**:
> DAC や Bluetooth が抜けた瞬間（0.001秒）にカーネル割り込みをフックし、スピーカーから音が出る前に音量を「0」へ強制変更＆ 0dB スイッチを自動 OFF にします。

---

### 4.  グラフィカル・レベルメーター (`WalkmanLevelMeterView.kt`)
* **実機準拠のカラーリング**:
  * **バー本体**: ピュアホワイト (`#FFFFFF`)
  * **Peak ホールド頂点**: ゴールド (`#E5A93C`、約750msホールド)
  * **0dB 超過（クリップ）**: 警告レッド (`#FF4444`)
  * **消灯グリッド**: ディープグレー (`#161616`)
* **True Peak & 高速減衰 (130 dB/s)**:
  * 音源本来のピーク（0.0dB）に正確に到達しつつ、ビートの合間ではキビキビと素早く沈み込む、実機さながらの躍動的な動きを実現。
* **座標事前キャッシュ**:
  * 全セグメント矩形を `onSizeChanged` で事前計算。毎フレームのオブジェクト生成（GCゴミ）をゼロにし、超軽量 60fps 描画を維持。

---

## 🎛 UI解説と設定ガイド

| UI要素 | 説明・動作仕様 |
| :--- | :--- |
| **DIRECT / BT バッジ** | 現在の接続状態を表示。<br>・**DIRECT STREAM（ゴールド）**: USB DAC接続・クロック同期中<br>・**BLUETOOTH（シアン）**: Bluetooth高音質出力中<br>・**STANDARD MIX（グレー）**: スピーカー出力中 |
| **出力デバイス名** | 接続中の USB DAC 名（例: `WALKMAN`）や Bluetooth イヤホン名（例: `WH-1000XM5`）を表示。 |
| **ビット深度スピナー** | 出力 PCM フォーマットをリアルタイム切り替え。<br>・**16-bit (Std)**: 標準 CD 音質<br>・**24-bit (Hi-Res)**: 24bit ハイレゾ出力（推奨）<br>・**32-bit (Float)**: 浮動小数点出力（クリッピング完全耐性） |
| **AD CUT スイッチ** | YouTube Music の広告パス・動画広告自動スキップ機能の有効/無効。 |
| **0dB スイッチ** | Android のデジタル音量減衰（ビット落ち）を回避する最大音量固定モード。<br>※USB DAC 接続時のみ有効化可能。DAC 抜去時は自動で OFF になります。 |
| **↻ リロードボタン** | ストリームバッファをクリアし、再生をリセット。 |
| **L/R PEAK & BIT** | リアルタイムの dBFS ピーク数値および稼働中のアクティブビット深度を表示。 |

---

## 🎧 Bluetooth 最高音質設定ガイド (LDAC / aptX)

Bluetooth イヤホンで本アプリの性能を 100% 引き出すための推奨設定です。

1. **スマホ本体の Bluetooth 設定**:
   * `設定` ＞ `接続済みのデバイス` ＞ 接続中のイヤホンの `[⚙️設定]` を開く。
   * **「LDAC」** または **「HDオーディオ」** を **ON** にする。
2. **メーカー専用アプリ (Sony Sound Connect等)**:
   * 接続品質を **「音質優先（Priority on Sound Quality）」** に設定（LDAC 990kbps固定）。
3. **本アプリ側の推奨設定**:
   * アプリ右上のスピナーを **`24-bit (Hi-Res)`** に設定。

> [!TIP]
> 上記の設定を行うことで Android の二重 SRC がバイパスされ、生 PCM がそのまま LDAC エンコーダーに供給されるため、圧倒的な音場と解像度で再生されます。

---

## 📱 動作確認済み環境

### Android 端末
* **Sony Xperia 1 シリーズ** (Android 14 / 15)
* **Samsung Galaxy S25 / S24 シリーズ** (Android 14 / 15 One UI 6+)

### USB DAC / DDC
* **SONY Walkman NW-A50 / A55 シリーズ**（USB DAC モード）
* **RP2350 Seeed USB DAC / DDC**
* **OPPO HA-2 / HA-2SE**

### Bluetooth オーディオ
* **SONY WF-1000XM5 / WH-1000XM5** (LDAC 990kbps)
* **各種 aptX / AAC 対応ワイヤレスレシーバー**

---

## 📦 ビルド & インストール手順

### 開発環境要件
* **Android Studio**: Ladybug / Koala / Jellyfish 以降
* **Android SDK Platform**: 34 (Android 14)
* **NDK**: 25.x 以上 (C++17 CMake 3.22.1)
* **Gradle**: 8.9

### 手順

1. リポジトリをクローン:
   ```bash
   git clone https://github.com/<your-username>/Perfect_Bit_Rate.git
   ```
2. Android Studio でプロジェクトを開き、`Gradle Sync` を完了させます。
3. 実機（USBデバッグ有効）を接続し、`[Run ▶]` を実行します。

> [!NOTE]
> **アップデート時の注意点:**  
> GeckoView の拡張機能キャッシュをリフレッシュするため、コード改修後に再インストールする際は、一度端末からアプリをアンインストールしてから新規インストールしてください。
