cmake_minimum_required(VERSION 3.22.1)
project(edgedroid_llama C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_subdirectory(llama.cpp)

add_library(edgedroid_llama SHARED llama_jni.cpp)

target_include_directories(edgedroid_llama PRIVATE
    llama.cpp/include
    llama.cpp/ggml/include)

target_link_libraries(edgedroid_llama PRIVATE llama)

find_library(log-lib log)
target_link_libraries(edgedroid_llama PRIVATE ${log-lib})
