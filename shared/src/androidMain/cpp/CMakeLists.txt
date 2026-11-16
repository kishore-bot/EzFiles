cmake_minimum_required(VERSION 3.22)
project(ezfiles-llama C CXX)

set(CMAKE_CXX_STANDARD 17)

set(ENGINE_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../../engine)
set(LLAMA_DIR ${ENGINE_DIR}/llama.cpp)

# Backends are separate libraries loaded at runtime: a phone without OpenCL loses only the GPU,
# and the CPU library that best fits the cores (armv8.0 up to armv9.2) is chosen on the device.
set(BUILD_SHARED_LIBS ON CACHE BOOL "" FORCE)
set(GGML_BACKEND_DL ON CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_VULKAN OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)

if(ANDROID_ABI STREQUAL "arm64-v8a")
    # One CPU library per Arm level; llama-bridge.cpp loads the best one the cores support.
    # x86_64 is only the emulator, so it gets a single generic CPU library.
    set(GGML_CPU_ALL_VARIANTS ON CACHE BOOL "" FORCE)
    # Arm's matmul kernels; they choose dotprod/i8mm paths at runtime.
    set(GGML_CPU_KLEIDIAI ON CACHE BOOL "" FORCE)
    # Adreno GPUs. Kernels are embedded at build time, which needs Python.
    set(GGML_OPENCL ON CACHE BOOL "" FORCE)
    # Built only to link against; the APK leaves it out so the phone's own libOpenCL.so
    # (declared with uses-native-library in the manifest) is the one loaded.
    set(OPENCL_ICD_LOADER_HEADERS_DIR ${ENGINE_DIR}/OpenCL-Headers CACHE PATH "" FORCE)
    set(OPENCL_ICD_LOADER_BUILD_TESTING OFF CACHE BOOL "" FORCE)
    add_subdirectory(${ENGINE_DIR}/OpenCL-ICD-Loader ${CMAKE_CURRENT_BINARY_DIR}/opencl-icd-loader EXCLUDE_FROM_ALL)
    set(OpenCL_INCLUDE_DIR ${ENGINE_DIR}/OpenCL-Headers CACHE PATH "" FORCE)
    set(OpenCL_LIBRARY OpenCL CACHE STRING "" FORCE)
else()
    set(GGML_OPENCL OFF CACHE BOOL "" FORCE)
endif()

add_subdirectory(${LLAMA_DIR} ${CMAKE_CURRENT_BINARY_DIR}/llama.cpp)

add_library(ezfiles-llama SHARED llama-bridge.cpp)

target_include_directories(ezfiles-llama PRIVATE
    ${LLAMA_DIR}/include
    ${LLAMA_DIR}/ggml/include
)

target_link_libraries(ezfiles-llama
    llama
    ggml
    android
    log
)
