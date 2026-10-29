cmake_minimum_required(VERSION 3.22)
project(wenqu_llm CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# llama.cpp 源码由 CI / 构建脚本 clone 到本目录（见 .github/workflows/android.yml）
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(GGML_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(GGML_BUILD_TESTS OFF CACHE BOOL "" FORCE)

add_subdirectory(llama)

add_library(wenqu_llm SHARED llama-android.cpp)
target_link_libraries(wenqu_llm PRIVATE llama)
