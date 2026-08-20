cmake_minimum_required(VERSION 3.22.1)
project(edgedroid_whisper C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# whisper.cpp vendors ggml in-tree (whisper.cpp/ggml) and builds the static `whisper`
# library. The whisper.cpp CMake controls GGML_* options; keep everything CPU-only.
add_subdirectory(whisper.cpp)

add_library(edgedroid_whisper SHARED whisper_jni.cpp)

target_include_directories(edgedroid_whisper PRIVATE
    whisper.cpp/include
    whisper.cpp/src)

target_link_libraries(edgedroid_whisper PRIVATE whisper)

find_library(log-lib log)
target_link_libraries(edgedroid_whisper PRIVATE ${log-lib})

# whisper.cpp's ggml uses POSIX threads; make sure we link pthreads explicitly.
find_package(Threads REQUIRED)
target_link_libraries(edgedroid_whisper PRIVATE Threads::Threads)
