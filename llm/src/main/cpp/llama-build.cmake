# Builds llama.cpp, its own copy of ggml, and llama.cpp's common library (chat templates,
# JSON-schema grammars, the sampler) as static libraries with the app's settings. Included by the
# llm module's CMakeLists.txt and by tools/vt-translate, so CI's tests compile the same way as the
# app. Expects LLAMA_DIR.
#
# llama.cpp's ggml is a different version from whisper.cpp's, so the language model lives in its
# own library (libvtllama.so) with its ggml symbols hidden, beside libvtwhisper.so.

if (NOT EXISTS ${LLAMA_DIR}/include/llama.h)
    message(FATAL_ERROR "llama.cpp is missing. Run: git submodule update --init")
endif()

# Optimised in every build type, as for whisper.cpp (see app/src/main/cpp/whisper-build.cmake).
add_compile_options(-O3 -DNDEBUG -ffunction-sections -fdata-sections)

set(BUILD_SHARED_LIBS      OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON     ON  CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS      OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS      OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES   OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER     OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_APP        OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_UI         OFF CACHE BOOL "" FORCE)
set(LLAMA_USE_PREBUILT_UI  OFF CACHE BOOL "" FORCE)
set(LLAMA_CONNECT          OFF CACHE BOOL "" FORCE)
set(LLAMA_OPENSSL          OFF CACHE BOOL "" FORCE)
set(LLAMA_SUBPROCESS       OFF CACHE BOOL "" FORCE)
set(LLAMA_LLGUIDANCE       OFF CACHE BOOL "" FORCE)
set(LLAMA_ALL_WARNINGS     OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP            OFF CACHE BOOL "" FORCE)
set(GGML_CCACHE            OFF CACHE BOOL "" FORCE)
set(GGML_BACKEND_DL        OFF CACHE BOOL "" FORCE)
set(GGML_CPU_KLEIDIAI      OFF CACHE BOOL "" FORCE)  # would download KleidiAI while building
set(GGML_BUILD_TESTS       OFF CACHE BOOL "" FORCE)
set(GGML_BUILD_EXAMPLES    OFF CACHE BOOL "" FORCE)

if (ANDROID)
    set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
    if (ANDROID_ABI STREQUAL "arm64-v8a")
        # The same instruction set as Whisper's build: every core of the Snapdragon 8 Gen 3.
        set(GGML_CPU_ARM_ARCH "armv8.2-a+dotprod+fp16+i8mm" CACHE STRING "" FORCE)
    elseif (ANDROID_ABI STREQUAL "x86_64")
        set(GGML_SSE42 ON CACHE BOOL "" FORCE)
    endif()
endif()

add_subdirectory(${LLAMA_DIR} llama EXCLUDE_FROM_ALL)
