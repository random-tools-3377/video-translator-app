# vt-translate: the app's language-model step (llm/src/main/cpp/vt_translate.h) on lines of text,
# built with the app's options (llama-build.cmake). CI uses it to translate Whisper's transcripts
# of the public test clips on a PC, and to run the phone's arm64 code under an ARM emulator. It is
# a test tool; it is not part of the app.
cmake_minimum_required(VERSION 3.22.1)
project(vt_translate C CXX)

set(LLM_CPP ${CMAKE_CURRENT_SOURCE_DIR}/../../llm/src/main/cpp)
set(LLAMA_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../third_party/llama.cpp)
include(${LLM_CPP}/llama-build.cmake)

add_executable(vt-translate main.cpp)
target_compile_features(vt-translate PRIVATE cxx_std_17)
target_include_directories(vt-translate PRIVATE ${LLM_CPP})
target_link_libraries(vt-translate PRIVATE llama-common llama)

option(VT_STATIC "link statically (for running under qemu-aarch64)" OFF)
if (VT_STATIC)
    target_link_options(vt-translate PRIVATE -static)
endif()
