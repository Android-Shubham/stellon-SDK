cmake_minimum_required(VERSION 3.22.1)

project(stellon_bitnet)

set(BITNET_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/BitNet)
set(LLAMA_BUILD_COMMON OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_CURL OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE OFF CACHE BOOL "" FORCE)

if(ANDROID_ABI STREQUAL "arm64-v8a")
    set(GGML_BITNET_ARM_TL1 ON CACHE BOOL "" FORCE)
    # Disable Dot Product instructions to ensure compatibility with all arm64-v8a devices and emulators.
    # These instructions (sdot/udot) are optional in ARMv8.2-A and cause SIGILL on many devices.
    add_compile_definitions(GGML_NO_ACCELERATE)
    add_compile_options(-mno-outline-atomics)
    add_definitions(-U__ARM_FEATURE_DOTPROD)
elseif(ANDROID_ABI STREQUAL "x86_64")
    set(GGML_BITNET_ARM_TL1 OFF CACHE BOOL "" FORCE)
    # Enable AVX2 for x86_64 emulators (standard on most modern dev machines)
    add_compile_options(-mavx -mavx2 -mfma -mf16c)
else()
    set(GGML_BITNET_ARM_TL1 OFF CACHE BOOL "" FORCE)
endif()

set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)

add_subdirectory(${BITNET_DIR}/3rdparty/llama.cpp ${CMAKE_CURRENT_BINARY_DIR}/llama EXCLUDE_FROM_ALL)

add_library(
    stellon_bitnet
    SHARED
    jni_bridge.cpp
    runtime/bitnet_runtime.cpp
)

target_include_directories(
    stellon_bitnet
    PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/runtime
    ${BITNET_DIR}/include
    ${BITNET_DIR}/3rdparty/llama.cpp/include
)

target_compile_features(stellon_bitnet PRIVATE cxx_std_17)

target_link_options(
    stellon_bitnet
    PRIVATE
    "-Wl,-z,max-page-size=16384"
    "-Wl,-z,common-page-size=16384"
)

if(ANDROID_ABI MATCHES "arm64-v8a|armeabi-v7a")
    target_compile_definitions(stellon_bitnet PRIVATE STELLON_ARM_NEON=1)
endif()

target_link_libraries(
    stellon_bitnet
    llama
    android
    log
)
