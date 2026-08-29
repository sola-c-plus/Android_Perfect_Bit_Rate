cmake_minimum_required(VERSION 3.22.1)
project("perfectbitrate")

add_library(
    native_audio_engine
    SHARED
    aaudio_engine.cpp
    jni_bridge.cpp
)

# Android 15 (Galaxy S25) 16KB アライメント対応（警告を消す設定）
target_link_options(native_audio_engine PRIVATE "-Wl,-z,max-page-size=16384")

find_library(log-lib log)
find_library(aaudio-lib aaudio)

target_link_libraries(
    native_audio_engine
    ${aaudio-lib}
    ${log-lib}
)