cmake_minimum_required(VERSION 3.22.1)
project(jarvis_llama LANGUAGES C CXX)

include(FetchContent)

# Mobile CPU optimizations: KleidiAI selects safe ARM kernels at runtime.
set(GGML_CPU_KLEIDIAI ON CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE OFF CACHE BOOL "" FORCE)

# Enable Vulkan only when the host build image has the shader toolchain and
# the Android NDK exposes the target Vulkan loader. Otherwise keep CPU usable.
set(GGML_VULKAN OFF CACHE BOOL "" FORCE)
if(ANDROID)
    set(_jarvis_vulkan_loader
        "${CMAKE_ANDROID_NDK}/toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/lib/aarch64-linux-android/26/libvulkan.so")
    if(EXISTS "/usr/include/vulkan/vulkan.hpp"
       AND EXISTS "/usr/bin/glslc"
       AND EXISTS "/usr/share/cmake/SPIRV-Headers/SPIRV-HeadersConfig.cmake"
       AND EXISTS "${_jarvis_vulkan_loader}")
        set(GGML_VULKAN ON CACHE BOOL "" FORCE)
        set(Vulkan_INCLUDE_DIR "/usr/include" CACHE PATH "" FORCE)
        set(Vulkan_LIBRARY "${_jarvis_vulkan_loader}" CACHE FILEPATH "" FORCE)
        set(Vulkan_GLSLC_EXECUTABLE "/usr/bin/glslc" CACHE FILEPATH "" FORCE)
        set(SPIRV-Headers_DIR "/usr/share/cmake/SPIRV-Headers" CACHE PATH "" FORCE)
        message(STATUS "JARVIS: Vulkan backend enabled for Android arm64")
    else()
        message(WARNING "JARVIS: Vulkan build dependencies unavailable; using optimized ARM CPU backend")
    endif()
endif()

set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_CURL OFF CACHE BOOL "" FORCE)
set(LLAMA_OPENSSL OFF CACHE BOOL "" FORCE)

FetchContent_Declare(
    llama_cpp
    GIT_REPOSITORY https://github.com/ggml-org/llama.cpp.git
    GIT_TAG v0.9.0
    GIT_SHALLOW TRUE
)
FetchContent_MakeAvailable(llama_cpp)

add_library(jarvis_llama SHARED jarvis_llama.cpp)
target_compile_features(jarvis_llama PRIVATE cxx_std_17)
target_compile_options(jarvis_llama PRIVATE -O3 -fexceptions -frtti)
target_compile_definitions(jarvis_llama PRIVATE JARVIS_KLEIDIAI_ENABLED=1)
target_link_libraries(jarvis_llama PRIVATE llama log)
