cmake_minimum_required(VERSION 3.22)
project(ezfiles-llama C CXX)

set(CMAKE_CXX_STANDARD 17)

set(LLAMA_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../../engine/llama.cpp)

# Build ggml + llama as static libraries
# Vulkan GPU needs a host C++ compiler (MSVC/MinGW) to build the shader generator.
# Install one, then flip this to ON.
set(SPIRV-Headers_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../../../engine/SPIRV-Headers-cmake" CACHE PATH "" FORCE)
# NDK ships glslc for shader compilation
find_program(_ndk_glslc glslc PATHS "${CMAKE_ANDROID_NDK}/../ndk/29.0.14206865/shader-tools/windows-x86_64" "${CMAKE_ANDROID_NDK}/shader-tools/windows-x86_64" NO_DEFAULT_PATH)
if (_ndk_glslc)
    set(Vulkan_GLSLC_EXECUTABLE "${_ndk_glslc}" CACHE FILEPATH "" FORCE)
else()
    set(Vulkan_GLSLC_EXECUTABLE "C:/Users/kisho/AppData/Local/Android/Sdk/ndk/29.0.14206865/shader-tools/windows-x86_64/glslc.exe" CACHE FILEPATH "" FORCE)
endif()
# Vulkan C++ headers from cloned Vulkan-Headers
set(VULKAN_HEADERS_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../../../engine/Vulkan-Headers/include")
set(Vulkan_INCLUDE_DIR "${VULKAN_HEADERS_DIR}" CACHE PATH "" FORCE)
set(GGML_VULKAN ON CACHE BOOL "" FORCE)

# Pre-built vulkan-shaders-gen: copy into the install dir that ExternalProject expects
set(_prebuilt_shaders_gen "${CMAKE_CURRENT_SOURCE_DIR}/../../../../engine/vulkan-shaders-gen-build/vulkan-shaders-gen.exe")
if(EXISTS ${_prebuilt_shaders_gen})
    file(COPY ${_prebuilt_shaders_gen} DESTINATION "${CMAKE_BINARY_DIR}/Debug")
    file(COPY ${_prebuilt_shaders_gen} DESTINATION "${CMAKE_BINARY_DIR}/Release")
    file(COPY ${_prebuilt_shaders_gen} DESTINATION "${CMAKE_BINARY_DIR}/RelWithDebInfo")
    file(COPY ${_prebuilt_shaders_gen} DESTINATION "${CMAKE_BINARY_DIR}/MinSizeRel")
endif()

# Use a dummy toolchain that just points to the pre-built binary location
set(GGML_VULKAN_SHADERS_GEN_TOOLCHAIN "${CMAKE_CURRENT_SOURCE_DIR}/host-toolchain.cmake" CACHE FILEPATH "" FORCE)
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
    android
    log
)
