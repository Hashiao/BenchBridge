#include "compute_common.h"
#include <algorithm>
#include <bit>
#include <cmath>
#include <cstring>
#include <ctime>
#include <iomanip>
#include <sstream>
#include <stdexcept>

namespace bbcompute {
std::uint64_t now_ns() {
    timespec t{};
    if (clock_gettime(CLOCK_MONOTONIC, &t) != 0) throw std::runtime_error("CLOCK_FAILED");
    return static_cast<std::uint64_t>(t.tv_sec) * 1000000000 + t.tv_nsec;
}
std::string quote(const std::string& text) {
    std::ostringstream s; s << '"';
    for (unsigned char c : text) {
        if (c == '"' || c == '\\') s << '\\' << c;
        else if (c < 32) s << "\\u" << std::hex << std::setw(4) << std::setfill('0') << static_cast<int>(c);
        else s << c;
    }
    return s.str() + '"';
}
const char* unit(int kind) {
    return kind <= MemoryCopy || kind == Aes256 || kind == Sha1 ? "MB/s" : kind <= Fp64 ? "GFLOPS" : kind <= Int64 ? "GIOPS" : "MPix/s";
}
std::uint64_t units_per_item(int kind, std::uint32_t iterations) {
    if (kind == Aes256) return 16;
    if (kind == Sha1) return crypto_message_bytes;
    if (kind >= Julia) return 1;
    return static_cast<std::uint64_t>(iterations) * (kind == Fp64 || kind == Int64 ? 32 : 64);
}
std::uint32_t pattern(std::uint32_t index, std::uint32_t seed) {
    auto x = index * 747796405u + seed * 2891336453u + 277803737u;
    x = ((x >> ((x >> 28u) + 4u)) ^ x) * 277803737u;
    return (x >> 22u) ^ x;
}
void fill_data(std::uint8_t* output, std::size_t bytes, std::size_t byte_offset) {
    for (std::size_t i = 0; i < bytes; i += 4) {
        const auto value = pattern(static_cast<std::uint32_t>((byte_offset + i) / 4), data_seed);
        std::memcpy(output + i, &value, std::min<std::size_t>(4, bytes - i));
    }
}
std::string Result::json() const {
    std::ostringstream s;
    s << "{\"kind\":" << kind << ",\"status\":" << quote(interrupted ? "INTERRUPTED" : verified ? "COMPLETED" : "FAILED")
      << ",\"verified\":" << (verified && !interrupted ? "true" : "false")
      << ",\"work_units\":" << work << ",\"elapsed_ns\":" << elapsed << ",\"wall_elapsed_ns\":" << wall
      << ",\"unit\":" << quote(unit(kind)) << ",\"backend\":" << quote(backend) << ",\"timer\":" << quote(timer)
      << ",\"error\":" << (interrupted ? "\"RUN_CANCELLED\"" : verified ? "null" : "\"COMPUTE_VERIFY_FAILED\"") << extra << '}';
    return s.str();
}

template<class T> Words floating_reference(std::uint32_t id, std::uint32_t iterations, std::uint32_t seed) {
    constexpr int lanes = sizeof(T) == 8 ? 2 : 4;
    T values[8][lanes];
    for (int a = 0; a < 8; ++a) for (int l = 0; l < lanes; ++l)
        values[a][l] = T(1) + T((id + seed + a * 17 + l * 3) & 255u) / T(256);
    const T mul = T(1) + T(1) / T(8388608);
    for (std::uint32_t i = 0; i < iterations; ++i) for (int a = 0; a < 8; ++a) for (int l = 0; l < lanes; ++l)
        values[a][l] = std::fma(values[a][l], mul, T(a + 1) / T(4096));
    Words out{};
    for (int l = 0; l < lanes; ++l) {
        T sum = values[0][l]; for (int a = 1; a < 8; ++a) sum += values[a][l];
        if constexpr (sizeof(T) == 4) out[l] = std::bit_cast<std::uint32_t>(sum);
        else { const auto bits = std::bit_cast<std::uint64_t>(sum); out[l * 2] = bits; out[l * 2 + 1] = bits >> 32; }
    }
    return out;
}
Words reference(int kind, std::uint32_t id, std::uint32_t iterations, std::uint32_t seed) {
    if (kind == Fp32) return floating_reference<float>(id, iterations, seed);
    if (kind == Fp64) return floating_reference<double>(id, iterations, seed);
    Words out{};
    if (kind >= Int24 && kind <= Int64) {
        const int lanes = kind == Int64 ? 2 : 4;
        std::uint64_t x[8][4]{};
        for (int a = 0; a < 8; ++a) for (int l = 0; l < lanes; ++l) x[a][l] = pattern(id + a * 17 + l * 3, seed);
        for (std::uint32_t i = 0; i < iterations; ++i) for (int a = 0; a < 8; ++a) for (int l = 0; l < lanes; ++l) {
            if (kind == Int64) x[a][l] = x[a][l] * 1664525ull + 1013904223ull + a;
            else x[a][l] = static_cast<std::uint32_t>((kind == Int24 ? x[a][l] & 0xffffffu : x[a][l]) * 1664525u + 1013904223u + a);
        }
        for (int l = 0; l < lanes; ++l) {
            std::uint64_t sum = 0; for (const auto& v : x) sum += v[l];
            if (kind == Int64) { out[l * 2] = sum; out[l * 2 + 1] = sum >> 32; } else out[l] = sum;
        }
    } else throw std::invalid_argument("COMPUTE_REFERENCE_KIND");
    return out;
}

// 关闭收缩以稳定分形边界；像素迭代独立于屏幕刷新。
// Disable contraction for stable fractal boundaries; iterations are independent of display refresh.
#pragma clang fp contract(off)
template<class T> std::uint32_t fractal(std::uint32_t pixel, std::uint32_t width, std::uint32_t height, std::uint32_t limit, bool julia) {
    const T x = T(pixel % width) / T(width - 1), y = T(pixel / width) / T(height - 1);
    T zx = julia ? x * T(3) - T(1.5) : T(0), zy = julia ? y * T(2) - T(1) : T(0);
    const T cx = julia ? T(-0.7) : x * T(3) - T(2), cy = julia ? T(0.27015) : y * T(3) - T(1.5);
    std::uint32_t n = 0;
    while (n < limit) {
        const T xx = zx * zx, yy = zy * zy;
        if (xx + yy > T(4)) break;
        zy = T(2) * zx * zy + cy; zx = xx - yy + cx; ++n;
    }
    return n;
}
std::uint32_t fractal_pixel(int kind, std::uint32_t pixel, std::uint32_t width, std::uint32_t height, std::uint32_t limit) {
    return kind == Julia ? fractal<float>(pixel, width, height, limit, true) : fractal<double>(pixel, width, height, limit, false);
}
bool equal_output(int kind, const Words& expected, const Words& actual) {
    if (kind == Fp32 || kind == Fp64) {
        for (int i = 0; i < (kind == Fp32 ? 4 : 2); ++i) {
            const double a = kind == Fp32 ? std::bit_cast<float>(expected[i]) : std::bit_cast<double>((std::uint64_t(expected[i*2+1]) << 32) | expected[i*2]);
            const double b = kind == Fp32 ? std::bit_cast<float>(actual[i]) : std::bit_cast<double>((std::uint64_t(actual[i*2+1]) << 32) | actual[i*2]);
            if (!std::isfinite(b) || std::abs(a-b) > std::abs(a) * (kind == Fp32 ? 0.0005 : 1e-10)) return false;
        }
        return true;
    }
    return expected == actual;
}
}
