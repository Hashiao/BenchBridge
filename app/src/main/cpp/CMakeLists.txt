cmake_minimum_required(VERSION 3.22.1)
project(benchbridge LANGUAGES CXX)

add_library(benchbridge SHARED native_probe.cpp ram_engine.cpp ram_kernels.cpp storage_engine.cpp)
target_compile_features(benchbridge PRIVATE cxx_std_20)
set_target_properties(benchbridge PROPERTIES CXX_EXTENSIONS OFF)
target_compile_options(benchbridge PRIVATE -Wall -Wextra -Werror -fvisibility=hidden)
target_compile_definitions(benchbridge PRIVATE BENCHBRIDGE_CMAKE_VERSION="${CMAKE_VERSION}")
# 调试包同样优化测量内核；禁用跨编译单元 LTO，以保留调用、屏障及校验工作量。
# Optimize measurement kernels in Debug as well; disable cross-unit LTO to preserve calls, barriers and checksums.
set_source_files_properties(ram_engine.cpp ram_kernels.cpp storage_engine.cpp PROPERTIES COMPILE_OPTIONS "-O3;-fno-lto")
set_target_properties(benchbridge PROPERTIES INTERPROCEDURAL_OPTIMIZATION FALSE)
