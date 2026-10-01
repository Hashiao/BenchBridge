#pragma once
#include <atomic>
#include <cstdint>
#include <string>

// 独立曲线内核；常规 RAM 和 GPGPU 内核不变。 / Dedicated curve kernel; RAM/GPGPU kernels stay unchanged.
std::string bb_latency_point(std::atomic<bool>& cancelled, std::atomic<int>& phase,
                            int cpu, std::uint64_t bytes, int stride, std::uint64_t seed);
