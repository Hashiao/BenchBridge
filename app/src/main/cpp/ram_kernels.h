#pragma once
#include <cstddef>
#include <cstdint>

extern "C" {
// words 为 64 位元素数量；缓冲区分区与边界由调用方保证。
// words counts 64-bit elements; the caller provides valid, disjoint buffer regions.
std::uint64_t bb_seq_read(const std::uint64_t* data, std::size_t words);
void bb_seq_write(std::uint64_t* data, std::size_t words, std::uint64_t value);
void bb_copy(std::uint64_t* destination, const std::uint64_t* source, std::size_t words);
std::uint64_t bb_random_read(const std::uint64_t* data, const std::uint32_t* indices, std::size_t words);
void bb_random_write(std::uint64_t* data, const std::uint32_t* indices, std::size_t words, std::uint64_t value);
// 沿有效指针链执行 hops 次依赖读取，并返回最终节点。
// Follow a valid pointer chain for hops dependent loads and return the final node.
std::uintptr_t* bb_chase(std::uintptr_t* node, std::size_t hops);
}
