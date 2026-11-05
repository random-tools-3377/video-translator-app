cmake_minimum_required(VERSION 3.22.1)
project(videotranslator C CXX)

set(WHISPER_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/whisper.cpp)
include(${CMAKE_CURRENT_SOURCE_DIR}/whisper-build.cmake)

add_library(vtwhisper SHARED whisper_jni.cpp)
target_compile_features(vtwhisper PRIVATE cxx_std_17)
target_link_libraries(vtwhisper PRIVATE whisper android log)
target_link_options(vtwhisper PRIVATE
    -Wl,--gc-sections
    -Wl,--exclude-libs,ALL
    -Wl,-z,max-page-size=16384)
