# Builds whisper.cpp and ggml as static libraries with the app's settings. Included by the app's
# CMakeLists.txt and by tools/vt-transcribe, so CI's tests compile the same way as the app.
# Expects WHISPER_DIR.

if (NOT EXISTS ${WHISPER_DIR}/include/whisper.h)
    message(FATAL_ERROR "whisper.cpp is missing. Run: git submodule update --init")
endif()

# Optimised in every build type, debug included: an unoptimised whisper.cpp is many times
# slower and reads as "the app is broken". These come after CMake's per-build-type flags on
# every compile line, so they win over a Debug -O0.
add_compile_options(-O3 -DNDEBUG -ffunction-sections -fdata-sections)

set(BUILD_SHARED_LIBS      OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_TESTS    OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER   OFF CACHE BOOL "" FORCE)
set(WHISPER_CURL           OFF CACHE BOOL "" FORCE)
set(WHISPER_SDL2           OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP            OFF CACHE BOOL "" FORCE)
set(GGML_CCACHE            OFF CACHE BOOL "" FORCE)
set(GGML_BACKEND_DL        OFF CACHE BOOL "" FORCE)
set(GGML_BUILD_TESTS       OFF CACHE BOOL "" FORCE)
set(GGML_BUILD_EXAMPLES    OFF CACHE BOOL "" FORCE)

if (ANDROID)
    set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
    if (ANDROID_ABI STREQUAL "arm64-v8a")
        # Snapdragon 8 Gen 3 (X4, A720, A520 cores): dot product, half-precision and int8 matrix
        # multiply are on every core.
        set(GGML_CPU_ARM_ARCH "armv8.2-a+dotprod+fp16+i8mm" CACHE STRING "" FORCE)
    elseif (ANDROID_ABI STREQUAL "x86_64")
        # Only the emulator test runs x86_64. Android's x86_64 baseline includes SSE4.2.
        set(GGML_SSE42 ON CACHE BOOL "" FORCE)
    endif()
endif()

add_subdirectory(${WHISPER_DIR} whisper EXCLUDE_FROM_ALL)
