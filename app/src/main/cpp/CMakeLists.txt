cmake_minimum_required(VERSION 3.22.1)
project("perfectbitrate")

add_library(
    native_audio_engine
    SHARED
    dsp_equalizer.cpp
    dsp_fir_stage.cpp
    dsp_transient.cpp
    dsp_freq_engine.cpp
    dsp_dc_phase.cpp
    dsp_true_peak_guard.cpp
    dsp_upsampler.cpp
    jni_bridge.cpp
)

target_compile_options(native_audio_engine PRIVATE -std=c++17 -O3 -ffast-math -flto)
target_link_options(native_audio_engine PRIVATE "-Wl,-z,max-page-size=16384" -flto)

find_library(log-lib log)

target_link_libraries(
    native_audio_engine
    ${log-lib}
)

# ★ Local Music モード用 FFmpeg ブリッジ（仕様書 §5）。
#   FFmpeg の .so は app/src/main/jniLibs/${ANDROID_ABI}/ に手動配置する前提で
#   imported library として参照する（配置手順は native/ffmpeg/README.md）。
#   既存の native_audio_engine（DSP チェーン）とは分離し、PCM の供給係としてのみ
#   機能する小さなブリッジに閉じ込める。
#   対応 ABI は arm64-v8a のみ。他 ABI ではブリッジをビルドせず、アプリ側は
#   FfmpegDecoder.isAvailable = false として Local 再生を無効化する。
if(ANDROID_ABI STREQUAL "arm64-v8a")
    set(FFMPEG_JNILIBS_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/${ANDROID_ABI})
    set(FFMPEG_INCLUDE_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../../native/ffmpeg/include)

    foreach(ffmpeg_lib avcodec avformat avutil swresample)
        add_library(ffmpeg_${ffmpeg_lib} SHARED IMPORTED)
        set_target_properties(ffmpeg_${ffmpeg_lib} PROPERTIES
            IMPORTED_LOCATION ${FFMPEG_JNILIBS_DIR}/lib${ffmpeg_lib}.so)
    endforeach()

    add_library(
        ffmpeg_bridge
        SHARED
        ffmpeg_decoder.cpp
    )

    target_include_directories(ffmpeg_bridge PRIVATE ${FFMPEG_INCLUDE_DIR})
    target_compile_options(ffmpeg_bridge PRIVATE -std=c++17 -O2)
    target_link_options(ffmpeg_bridge PRIVATE "-Wl,-z,max-page-size=16384")

    target_link_libraries(
        ffmpeg_bridge
        ffmpeg_avformat
        ffmpeg_avcodec
        ffmpeg_swresample
        ffmpeg_avutil
        ${log-lib}
    )
endif()