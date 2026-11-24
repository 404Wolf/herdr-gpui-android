# libherdr_whisper: whisper.cpp (MIT, built from the pinned source in WHISPER_CPP_SRC) and the
# small JNI shim LocalWhisper calls. CPU only, no OpenMP, no examples: one shared library that
# the APK carries; the model is a separate, verified file (see DICTATION.md). The same file
# builds a host library for the JVM tests (JNI from the JDK).
cmake_minimum_required(VERSION 3.16)
project(herdr_whisper C CXX)

if(NOT WHISPER_CPP_SRC)
    message(FATAL_ERROR "Set WHISPER_CPP_SRC to the whisper.cpp source (the flake's android-emu shell does)")
endif()

set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(WHISPER_CURL OFF CACHE BOOL "" FORCE)
set(WHISPER_SDL2 OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_BACKEND_DL OFF CACHE BOOL "" FORCE)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
add_subdirectory(${WHISPER_CPP_SRC} whisper.cpp EXCLUDE_FROM_ALL)

add_library(herdr_whisper SHARED herdr_whisper.c)
target_link_libraries(herdr_whisper PRIVATE whisper)
if(ANDROID)
    find_library(LOG_LIB log)
    target_link_libraries(herdr_whisper PRIVATE ${LOG_LIB})
    # 16 KiB pages (Android 15+ devices), as the GPUI library.
    target_link_options(herdr_whisper PRIVATE -Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384
        -Wl,--no-undefined -static-libstdc++)
else()
    find_package(JNI REQUIRED)
    target_include_directories(herdr_whisper PRIVATE ${JNI_INCLUDE_DIRS})
endif()
