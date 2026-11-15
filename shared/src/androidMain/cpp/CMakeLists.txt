cmake_minimum_required(VERSION 3.22)
project(ezfiles-llama C CXX)

set(CMAKE_CXX_STANDARD 17)

set(LLAMA_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../../engine/llama.cpp)

# Build ggml + llama as static libraries
set(GGML_VULKAN ON CACHE BOOL "Enable Vulkan GPU backend" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)

add_subdirectory(${LLAMA_DIR} ${CMAKE_CURRENT_BINARY_DIR}/llama.cpp)

add_library(ezfiles-llama SHARED llama-bridge.cpp)

target_include_directories(ezfiles-llama PRIVATE
    ${LLAMA_DIR}/include
    ${LLAMA_DIR}/ggml/include
)

target_link_libraries(ezfiles-llama
    llama
    ggml
    common
    android
    log
)
