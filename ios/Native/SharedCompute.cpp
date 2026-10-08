// Windows 仅替换时钟适配，参考计算与 Apple 构建使用同一源文件。
// Windows adapts only the clock; reference calculations use the same source as Apple builds.
#if defined(_WIN32)
#include <chrono>
#include <ctime>
#define CLOCK_MONOTONIC 1
static int bb_host_clock_gettime(int, timespec* value) {
    const auto ns=std::chrono::duration_cast<std::chrono::nanoseconds>(std::chrono::steady_clock::now().time_since_epoch()).count();
    value->tv_sec=ns/1000000000;value->tv_nsec=long(ns%1000000000);return 0;
}
#define clock_gettime bb_host_clock_gettime
#endif
#include "../../app/src/main/cpp/compute/compute_common.cpp"
