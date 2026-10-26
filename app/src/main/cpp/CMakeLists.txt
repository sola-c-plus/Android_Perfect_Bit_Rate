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