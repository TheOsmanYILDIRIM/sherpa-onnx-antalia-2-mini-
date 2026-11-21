cmake_minimum_required(VERSION 3.22.1)
project(zonos3_jni LANGUAGES C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

if(NOT LLAMA_CPP_DIR OR NOT EXISTS "${LLAMA_CPP_DIR}/CMakeLists.txt")
    message(FATAL_ERROR "LLAMA_CPP_DIR must point to the pinned llama.cpp checkout")
endif()

set(LLAMA_BUILD_COMMON ON CACHE BOOL "" FORCE)
set(LLAMA_BUILD_MTMD ON CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_APP OFF CACHE BOOL "" FORCE)
set(LLAMA_OPENSSL OFF CACHE BOOL "" FORCE)
set(LLAMA_SUBPROCESS OFF CACHE BOOL "" FORCE)
set(MTMD_VIDEO OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)

add_subdirectory("${LLAMA_CPP_DIR}" "${CMAKE_BINARY_DIR}/llama")

add_library(zonos3_jni SHARED zonos3_jni.cpp)
target_include_directories(zonos3_jni PRIVATE
    "${LLAMA_CPP_DIR}"
    "${LLAMA_CPP_DIR}/common"
    "${LLAMA_CPP_DIR}/include"
    "${LLAMA_CPP_DIR}/ggml/include"
    "${LLAMA_CPP_DIR}/tools/mtmd")
target_link_libraries(zonos3_jni PRIVATE llama llama-common mtmd android log)
target_compile_options(zonos3_jni PRIVATE -O3 -ffast-math)
