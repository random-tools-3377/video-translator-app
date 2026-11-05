# vt-transcribe: runs whisper.cpp on raw 16 kHz mono float audio with the app's exact settings
# (app/src/main/cpp/vt_params.h) and build options (whisper-build.cmake). CI uses it to
# translate public test clips with the medium model on a PC, and to run the phone's arm64 code
# under an ARM emulator. It is a test tool; it is not part of the app.
cmake_minimum_required(VERSION 3.22.1)
project(vt_transcribe C CXX)

set(APP_CPP ${CMAKE_CURRENT_SOURCE_DIR}/../../app/src/main/cpp)
set(WHISPER_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../third_party/whisper.cpp)
include(${APP_CPP}/whisper-build.cmake)

add_executable(vt-transcribe main.cpp)
target_compile_features(vt-transcribe PRIVATE cxx_std_17)
target_include_directories(vt-transcribe PRIVATE ${APP_CPP})
target_link_libraries(vt-transcribe PRIVATE whisper)

option(VT_STATIC "link statically (for running under qemu-aarch64)" OFF)
if (VT_STATIC)
    target_link_options(vt-transcribe PRIVATE -static)
endif()
