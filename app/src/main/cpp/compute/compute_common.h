#pragma once
#include <array>
#include <atomic>
#include <cstdint>
#include <string>
#include <vector>

namespace bbcompute {
enum Operation { MemoryRead, MemoryWrite, MemoryCopy, Fp32, Fp64, Int24, Int32, Int64, Aes256, Sha1, Julia, Mandel };
using Words = std::array<std::uint32_t, 8>;
std::uint64_t now_ns();
std::string quote(const std::string& text);
const char* unit(int kind);
std::uint64_t units_per_item(int kind, std::uint32_t iterations);
std::uint32_t pattern(std::uint32_t index, std::uint32_t seed);
Words reference(int kind, std::uint32_t id, std::uint32_t iterations, std::uint32_t seed);
std::uint32_t fractal_pixel(int kind, std::uint32_t pixel, std::uint32_t width, std::uint32_t height, std::uint32_t limit);
bool equal_output(int kind, const Words& expected, const Words& actual);
void aes_block(const std::uint8_t* input, std::uint8_t* output);
void aes_fast_batch(std::uint32_t id, std::uint32_t count, std::uint32_t seed, Words& output);
void sha_message(const std::uint8_t* input, std::size_t length, std::uint32_t* output, bool accelerated);
bool crypto_self_test();
bool aes_accelerated();
bool sha_accelerated();
std::string cpu_capabilities();
std::string cpu_round(int kind, const std::vector<int>& cpus, int warmup_ms, int duration_ms,
                      int width, int height, std::atomic<bool>& cancelled);

struct Result {
    int kind = 0;
    std::uint64_t work = 0, elapsed = 0, wall = 0;
    bool verified = false, interrupted = false;
    std::string backend, timer = "CLOCK_MONOTONIC", extra;
    std::string json() const;
};
}
