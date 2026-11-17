# Media3's FFmpeg JNI bridge (ffmpeg_jni.cc, Apache-2.0, from androidx/media 1.9.4), linked
# dynamically to FFmpeg's LGPL audio libraries built by scripts/build-ffmpeg.sh. Dynamic linking keeps
# FFmpeg replaceable, as the LGPL asks. Nothing is built for an ABI whose FFmpeg isn't there.
cmake_minimum_required(VERSION 3.22.1 FATAL_ERROR)
set(CMAKE_CXX_STANDARD 11)
project(libffmpegJNI C CXX)

set(ffmpeg_abi "${FFMPEG_DIR}/${ANDROID_ABI}")
if(NOT EXISTS "${ffmpeg_abi}/lib/libavcodec.so")
    message(WARNING "No FFmpeg for ${ANDROID_ABI} in ${ffmpeg_abi}: run scripts/build-ffmpeg.sh. Building without it.")
    return()
endif()

foreach(ffmpeg_lib avutil swresample avcodec)
    add_library(${ffmpeg_lib} SHARED IMPORTED)
    set_target_properties(${ffmpeg_lib} PROPERTIES IMPORTED_LOCATION "${ffmpeg_abi}/lib/lib${ffmpeg_lib}.so")
endforeach()

include_directories("${ffmpeg_abi}/include")
find_library(android_log_lib log)

add_library(ffmpegJNI SHARED ffmpeg_jni.cc)
target_link_libraries(ffmpegJNI PRIVATE android swresample avcodec avutil ${android_log_lib})
if(ANDROID_ABI STREQUAL "arm64-v8a")
    target_link_options(ffmpegJNI PRIVATE "-Wl,-Bsymbolic")
endif()
# 16 KB pages (Android 15+ devices).
target_link_options(ffmpegJNI PRIVATE "-Wl,-z,max-page-size=16384")
