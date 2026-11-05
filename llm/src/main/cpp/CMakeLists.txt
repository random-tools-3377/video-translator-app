cmake_minimum_required(VERSION 3.22.1)
project(videotranslator_llm C CXX)

set(LLAMA_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/llama.cpp)
include(${CMAKE_CURRENT_SOURCE_DIR}/llama-build.cmake)

add_library(vtllama SHARED llm_jni.cpp)
target_compile_features(vtllama PRIVATE cxx_std_17)
target_link_libraries(vtllama PRIVATE llama-common llama android log)
# --exclude-libs,ALL keeps this library's ggml (llama.cpp's version) private, so it cannot clash
# with the ggml inside libvtwhisper.so (whisper.cpp's version) in the same process.
target_link_options(vtllama PRIVATE
    -Wl,--gc-sections
    -Wl,--exclude-libs,ALL
    -Wl,-z,max-page-size=16384)
