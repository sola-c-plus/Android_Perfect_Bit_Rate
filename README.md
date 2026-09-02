# Perfect Bit Rate 🎵
### Android Bit-Perfect Audio Player for YouTube Music (USB DAC / Bluetooth / Internal DSP)

![Android 14+](https://img.shields.io/badge/Android-14%2B%20%2F%20API%2033%2B-brightgreen.svg)
![Kotlin](https://img.shields.io/badge/Kotlin-2.0-purple.svg)
![C++17](https://img.shields.io/badge/C%2B%2B-17%20(NEON%20%2F%20SIMD)-orange.svg)
![License](https://img.shields.io/badge/License-MIT-blue.svg)

**Perfect Bit Rate** は、Android OS 標準ミキサー（AudioFlinger）による非整数倍リサンプリング（SRC劣化）を完全にバイパスし、YouTube Music の音声を **1:1 ビットパーフェクト伝送** または **スタジオ級 64bit 高品位 DSP（アップサンプリング / DSEE / DC Phase / 10-Band EQ）** を通して USB DAC や Bluetooth 機器へ出力するための専用オーディオプレイヤーアプリケーションです。

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
* **0dB Volume Lock & 音量キースキップ:**  
  USB DAC 接続時は Android のデジタル減衰を排した 0dB（100%）固定出力。音量アップ/ダウンキーが「次へ / 前へ」のトラック操作キーとして動作。DAC やイヤホン抜去時（`ACTION_AUDIO_BECOMING_NOISY`）は瞬時に音量を「0」へ強制リセット。
* **バックグラウンド再生 & 広告自動スキップ (AD CUT):**  
  画面消灯時も GeckoView をアクティブ偽装してバックグラウンド再生を維持。システム通知・ロックスクリーンからの完全メディア操作対応。