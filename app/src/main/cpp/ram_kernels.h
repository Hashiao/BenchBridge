#pragma once
#include <cstddef>
#include <cstdint>

extern "C" {
// words 为 64 位元素数量；缓冲区分区与边界由调用方保证。
// words counts 64-bit elements; the caller provides valid, disjoint buffer regions.
std::uint64_t bb_seq_read(const std::uint64_t* data, std::size_t words);
void bb_seq_write(std::uint64_t* data, std::size_t words, std::uint64_t value);
void bb_copy(std::uint64_t* destination, const std::uint64_t* source, std::size_t words);
// 小工作集在同一次调用中重复访问，减少计时器与函数边界开销。
// Repeat small working sets within one call to reduce timer and call-boundary overhead.
std::uint64_t bb_cached_read(const std::uint64_t* data, std::size_t words, std::size_t passes);
void bb_cached_write(std::uint64_t* data, std::size_t words, std::uint64_t value, std::size_t passes);
void bb_cached_copy(std::uint64_t* destination, const std::uint64_t* source, std::size_t words, std::size_t passes);
std::uint64_t bb_random_read(const std::uint64_t* data, const std::uint32_t* indices, std::size_t words);
void bb_random_write(std::uint64_t* data, const std::uint32_t* indices, std::size_t words, std::uint64_t value);
// 沿有效指针链执行 hops 次依赖读取，并返回最终节点。
// Follow a valid pointer chain for hops dependent loads and return the final node.
std::uintptr_t* bb_chase(std::uintptr_t* node, std::size_t hops);
// 32 位索引依赖链，包含索引地址计算成本。 / Dependent 32-bit index chain, including address-generation cost.
std::uint32_t bb_chase_index(const std::uint32_t* data, std::uint32_t index, std::size_t hops);
}
