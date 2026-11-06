# FFmpeg 同梱資産（Local Music モード用）

仕様書 `design-local-music-mode.md` §5 に基づく、FFmpeg 7.1.1 の自前 NDK
ビルド資産です。構成は LGPL 2.1（GPL コンポーネント・外部ライブラリなし）。

## ⚠ .so バイナリはこのリポジトリ管理の都合で PR に含まれていません

ビルド・実行には、以下の 4 ファイルを **`app/src/main/jniLibs/arm64-v8a/`** に
配置する必要があります（別途 zip で配布されたものを使ってください）。

| ファイル | 備考 |
|---|---|
| `libavcodec.so` | デコーダ本体 |
| `libavformat.so` | demuxer / メタデータ |
| `libavutil.so` | 共通ユーティリティ |
| `libswresample.so` | Opus デコーダの内部依存＋DSD 変換レート段でのみ使用 |

`.so` が無い状態では CMake の構成（imported library の解決）に失敗し、
ビルドできません。これは想定された前提条件です。

CMake（`app/src/main/cpp/CMakeLists.txt`）は `jniLibs/${ANDROID_ABI}` の
`.so` を imported library として参照し、JNI ブリッジ `libffmpeg_bridge.so` を
リンクします。対応 ABI は **arm64-v8a のみ**です（仕様書 §5.2:
arm64-v8a 必須）。他 ABI ではブリッジ自体をビルドせず、アプリ側は
「Local 再生を利用できない」状態として扱います。

## 再ビルド手順

1. FFmpeg 7.1.1 のソースを `native/ffmpeg/ffmpeg-7.1.1/` に展開する。
2. Android NDK r27c を用意し、`NDK_HOME` を設定する。
3. `./build-ffmpeg.sh` を実行する → `native/ffmpeg/dist/arm64-v8a/` に
   `lib/*.so` と `include/` が生成される。
4. `lib/` の 4 つの `.so` を `app/src/main/jniLibs/arm64-v8a/` へコピーする。
   ヘッダを更新した場合は `include/` をこのディレクトリの `include/` へ
   そのまま差し替える。

## ライセンス

- `LICENSE.md` / `COPYING.LGPLv2.1`: FFmpeg のライセンス文。
- 有効化しているデコーダ等は `build-ffmpeg.sh` の configure オプションが正。
- アプリ内のクレジット表記は設定画面の「ライセンス表記」に集約する
  （別 PR で FFmpeg 分を追加予定）。
