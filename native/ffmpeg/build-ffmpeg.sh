#!/bin/bash
# build-ffmpeg.sh — FFmpeg minimal LGPL build for Android arm64-v8a
#
# Perfect Bit Rate / Local Music mode (design doc §5.2). FFmpeg 7.1.1 を
# 自前 NDK ビルドで再現するためのスクリプト。生成した .so は
# app/src/main/jniLibs/arm64-v8a/ へ配置する（配置手順は同階層の README.md）。
# Builds ONLY the audio decoders/demuxers the app needs:
#   FLAC / ALAC / MP3 / AAC / Vorbis / Opus / PCM(WAV,AIFF) / DSD(DSF,DFF)
# No GPL components, no external libraries. Decode-only (no encoders/muxers).
#
# NOTE: libswresample must stay ENABLED — FFmpeg 7.x's native Opus decoder
# hard-depends on it (configure: opus_decoder_deps="swresample") and is
# silently dropped from the build if swresample is disabled. The app itself
# still converts sample formats directly without calling swresample
# (design doc §5.2); this is purely FFmpeg's internal dependency.
#
# Usage: ./build-ffmpeg.sh
#   Env overrides: NDK_HOME, FFMPEG_SRC, API, JOBS, PREFIX
#   Defaults expect the FFmpeg source tree next to this script
#   (native/ffmpeg/ffmpeg-7.1.1) and install into native/ffmpeg/dist/arm64-v8a.

set -euo pipefail

API="${API:-26}"
JOBS="${JOBS:-4}"
NDK_HOME="${NDK_HOME:-$HOME/opt/android-ndk-r27c}"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
FFMPEG_SRC="${FFMPEG_SRC:-$SCRIPT_DIR/ffmpeg-7.1.1}"
PREFIX="${PREFIX:-$SCRIPT_DIR/dist/arm64-v8a}"
BUILD_DIR="$SCRIPT_DIR/build/arm64-v8a"

TOOLCHAIN="$NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64"
TARGET="aarch64-linux-android"
CC="$TOOLCHAIN/bin/${TARGET}${API}-clang"
CXX="$TOOLCHAIN/bin/${TARGET}${API}-clang++"

[ -x "$CC" ] || { echo "NDK clang not found: $CC" >&2; exit 1; }
[ -f "$FFMPEG_SRC/configure" ] || { echo "FFmpeg source not found: $FFMPEG_SRC" >&2; exit 1; }

mkdir -p "$BUILD_DIR" "$PREFIX"
cd "$BUILD_DIR"

# PCM decoder set covers what WAV/AIFF containers actually carry.
PCM_DECODERS="pcm_alaw,pcm_bluray,pcm_dvd,pcm_f16le,pcm_f24le,pcm_f32be,pcm_f32le,\
pcm_f64be,pcm_f64le,pcm_mulaw,pcm_s16be,pcm_s16be_planar,pcm_s16le,pcm_s16le_planar,\
pcm_s24be,pcm_s24daud,pcm_s24le,pcm_s24le_planar,pcm_s32be,pcm_s32le,pcm_s32le_planar,\
pcm_s64be,pcm_s64le,pcm_s8,pcm_s8_planar,pcm_u16be,pcm_u16le,pcm_u24be,pcm_u24le,\
pcm_u32be,pcm_u32le,pcm_u8,pcm_vidc"

"$FFMPEG_SRC/configure" \
    --prefix="$PREFIX" \
    --target-os=android \
    --arch=aarch64 \
    --cpu=armv8-a \
    --enable-cross-compile \
    --cc="$CC" \
    --cxx="$CXX" \
    --ar="$TOOLCHAIN/bin/llvm-ar" \
    --ranlib="$TOOLCHAIN/bin/llvm-ranlib" \
    --strip="$TOOLCHAIN/bin/llvm-strip" \
    --nm="$TOOLCHAIN/bin/llvm-nm" \
    --sysroot="$TOOLCHAIN/sysroot" \
    --extra-cflags="-O3 -fPIC" \
    --enable-shared \
    --disable-static \
    --disable-symver \
    --disable-debug \
    --disable-programs \
    --disable-doc \
    --disable-network \
    --disable-avdevice \
    --disable-avfilter \
    --disable-postproc \
    --disable-swscale \
    --enable-swresample \
    --disable-everything \
    --enable-protocol=file \
    --enable-demuxer=aac,aiff,dsf,flac,iff,mov,mp3,ogg,wav \
    --enable-decoder=aac,alac,flac,mp3float,opus,vorbis,dsd_lsbf,dsd_lsbf_planar,dsd_msbf,dsd_msbf_planar,"$PCM_DECODERS" \
    --enable-parser=aac,aac_latm,flac,mpegaudio,opus,vorbis

make -j"$JOBS"
make install

# Strip installed shared libraries (report sizes are post-strip).
for so in "$PREFIX"/lib/*.so*; do
    [ -f "$so" ] && "$TOOLCHAIN/bin/llvm-strip" --strip-unneeded "$so" || true
done

echo "=== Build complete ==="
ls -la "$PREFIX/lib/"
du -ch "$PREFIX"/lib/libavcodec.so* "$PREFIX"/lib/libavformat.so* \
       "$PREFIX"/lib/libavutil.so* "$PREFIX"/lib/libswresample.so* | tail -1
