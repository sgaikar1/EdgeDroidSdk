cmake_minimum_required(VERSION 3.22.1)
project(edgedroid_llama C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# ggml-vulkan links the "Vulkan::Vulkan" imported target from find_package(Vulkan).
# On macOS/Homebrew that target ships headers but NO library, so the Android target
# would fail to link vk* symbols. Point it at the NDK's system libvulkan for this ABI.
if (ANDROID)
    find_library(EDGEDROID_VULKAN_LIB vulkan)
    if (EDGEDROID_VULKAN_LIB)
        set(Vulkan_LIBRARY ${EDGEDROID_VULKAN_LIB} CACHE FILEPATH "NDK libvulkan" FORCE)
        set(Vulkan_LIBRARIES ${EDGEDROID_VULKAN_LIB} CACHE STRING "NDK libvulkan" FORCE)
    endif()
endif()

add_subdirectory(llama.cpp)

# ggml-vulkan links vkGetPhysicalDeviceFeatures2 directly but the NDK's libvulkan
# stub (Vulkan 1.0) doesn't export it. Provide the shim inside that library.
if (TARGET ggml-vulkan)
    target_sources(ggml-vulkan PRIVATE vulkan_shim.cpp)
endif()

add_library(edgedroid_llama SHARED llama_jni.cpp)

target_include_directories(edgedroid_llama PRIVATE
    llama.cpp/include
    llama.cpp/ggml/include)

target_link_libraries(edgedroid_llama PRIVATE llama)

find_library(log-lib log)
target_link_libraries(edgedroid_llama PRIVATE ${log-lib})

# Vulkan backend (ggml-vulkan) needs Android's system libvulkan for vk* symbols.
find_library(vulkan-lib vulkan)
if (vulkan-lib)
    target_link_libraries(edgedroid_llama PRIVATE ${vulkan-lib})
endif()
