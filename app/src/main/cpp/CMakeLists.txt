cmake_minimum_required(VERSION 3.22.1)
project(jarvis_llama LANGUAGES C CXX)

include(FetchContent)

# Build only the embedded inference library; keep the upstream version fixed.
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_CURL OFF CACHE BOOL "" FORCE)
set(LLAMA_OPENSSL OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE OFF CACHE BOOL "" FORCE)
set(GGML_VULKAN OFF CACHE BOOL "" FORCE)

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
target_link_libraries(jarvis_llama PRIVATE llama log)
