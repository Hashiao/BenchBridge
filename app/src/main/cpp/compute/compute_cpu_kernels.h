#pragma once
#include "compute_common.h"
namespace bbcompute {
void cpu_kernel(int kind, std::uint32_t id, std::uint32_t iterations, std::uint32_t seed, Words& output);
void cpu_frame(int kind, std::uint32_t width, std::uint32_t height, std::uint32_t* pixels, std::atomic<bool>& cancelled);
}
