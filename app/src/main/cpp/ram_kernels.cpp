#include "ram_kernels.h"
#if defined(__aarch64__)
#include <arm_neon.h>
#elif defined(__x86_64__)
#include <emmintrin.h>
#else
#error Unsupported architecture
#endif

#define BB_NOINLINE __attribute__((noinline))

extern "C" BB_NOINLINE std::uint64_t bb_seq_read(const std::uint64_t* data, std::size_t words) {
    asm volatile("" ::: "memory");
    std::size_t i = 0;
    std::uint64_t sum = 0;
#if defined(__aarch64__)
    uint64x2_t a = vdupq_n_u64(0), b = a, c = a, d = a;
    for (; i + 8 <= words; i += 8) {
        a = vaddq_u64(a, vld1q_u64(data + i));
        b = vaddq_u64(b, vld1q_u64(data + i + 2));
        c = vaddq_u64(c, vld1q_u64(data + i + 4));
        d = vaddq_u64(d, vld1q_u64(data + i + 6));
    }
    sum = vaddvq_u64(vaddq_u64(vaddq_u64(a, b), vaddq_u64(c, d)));
#else
    __m128i a = _mm_setzero_si128(), b = a, c = a, d = a;
    for (; i + 8 <= words; i += 8) {
        a = _mm_add_epi64(a, _mm_loadu_si128(reinterpret_cast<const __m128i*>(data + i)));
        b = _mm_add_epi64(b, _mm_loadu_si128(reinterpret_cast<const __m128i*>(data + i + 2)));
        c = _mm_add_epi64(c, _mm_loadu_si128(reinterpret_cast<const __m128i*>(data + i + 4)));
        d = _mm_add_epi64(d, _mm_loadu_si128(reinterpret_cast<const __m128i*>(data + i + 6)));
    }
    alignas(16) std::uint64_t lanes[2];
    _mm_store_si128(reinterpret_cast<__m128i*>(lanes), _mm_add_epi64(_mm_add_epi64(a, b), _mm_add_epi64(c, d)));
    sum = lanes[0] + lanes[1];
#endif
    for (; i < words; ++i) sum += data[i];
    asm volatile("" : "+r"(sum) : : "memory");
    return sum;
}

extern "C" BB_NOINLINE void bb_seq_write(std::uint64_t* data, std::size_t words, std::uint64_t value) {
    asm volatile("" ::: "memory");
    std::size_t i = 0;
#if defined(__aarch64__)
    const auto vector = vdupq_n_u64(value);
    for (; i + 8 <= words; i += 8) {
        vst1q_u64(data + i, vector); vst1q_u64(data + i + 2, vector);
        vst1q_u64(data + i + 4, vector); vst1q_u64(data + i + 6, vector);
    }
#else
    const auto vector = _mm_set1_epi64x(static_cast<long long>(value));
    for (; i + 8 <= words; i += 8) {
        _mm_storeu_si128(reinterpret_cast<__m128i*>(data + i), vector);
        _mm_storeu_si128(reinterpret_cast<__m128i*>(data + i + 2), vector);
        _mm_storeu_si128(reinterpret_cast<__m128i*>(data + i + 4), vector);
        _mm_storeu_si128(reinterpret_cast<__m128i*>(data + i + 6), vector);
    }
#endif
    for (; i < words; ++i) data[i] = value;
    asm volatile("" ::: "memory");
}

extern "C" BB_NOINLINE void bb_copy(std::uint64_t* destination, const std::uint64_t* source, std::size_t words) {
    asm volatile("" ::: "memory");
    std::size_t i = 0;
    for (; i + 8 <= words; i += 8) {
#if defined(__aarch64__)
        const auto a = vld1q_u64(source + i), b = vld1q_u64(source + i + 2);
        const auto c = vld1q_u64(source + i + 4), d = vld1q_u64(source + i + 6);
        vst1q_u64(destination + i, a); vst1q_u64(destination + i + 2, b);
        vst1q_u64(destination + i + 4, c); vst1q_u64(destination + i + 6, d);
#else
        const auto a = _mm_loadu_si128(reinterpret_cast<const __m128i*>(source + i));
        const auto b = _mm_loadu_si128(reinterpret_cast<const __m128i*>(source + i + 2));
        const auto c = _mm_loadu_si128(reinterpret_cast<const __m128i*>(source + i + 4));
        const auto d = _mm_loadu_si128(reinterpret_cast<const __m128i*>(source + i + 6));
        _mm_storeu_si128(reinterpret_cast<__m128i*>(destination + i), a);
        _mm_storeu_si128(reinterpret_cast<__m128i*>(destination + i + 2), b);
        _mm_storeu_si128(reinterpret_cast<__m128i*>(destination + i + 4), c);
        _mm_storeu_si128(reinterpret_cast<__m128i*>(destination + i + 6), d);
#endif
    }
    for (; i < words; ++i) destination[i] = source[i];
    asm volatile("" ::: "memory");
}

extern "C" BB_NOINLINE std::uint64_t bb_random_read(const std::uint64_t* data, const std::uint32_t* indices, std::size_t words) {
    asm volatile("" ::: "memory");
    std::uint64_t a = 0, b = 0, c = 0, d = 0, e = 0, f = 0, g = 0, h = 0;
    std::size_t i = 0;
    for (; i + 8 <= words; i += 8) {
        a += data[indices[i]]; b += data[indices[i + 1]];
        c += data[indices[i + 2]]; d += data[indices[i + 3]];
        e += data[indices[i + 4]]; f += data[indices[i + 5]];
        g += data[indices[i + 6]]; h += data[indices[i + 7]];
    }
    auto sum = a + b + c + d + e + f + g + h;
    for (; i < words; ++i) sum += data[indices[i]];
    asm volatile("" : "+r"(sum) : : "memory");
    return sum;
}

extern "C" BB_NOINLINE void bb_random_write(std::uint64_t* data, const std::uint32_t* indices, std::size_t words, std::uint64_t value) {
    asm volatile("" ::: "memory");
    std::size_t i = 0;
    for (; i + 8 <= words; i += 8) {
        data[indices[i]] = value; data[indices[i + 1]] = value;
        data[indices[i + 2]] = value; data[indices[i + 3]] = value;
        data[indices[i + 4]] = value; data[indices[i + 5]] = value;
        data[indices[i + 6]] = value; data[indices[i + 7]] = value;
    }
    for (; i < words; ++i) data[indices[i]] = value;
    asm volatile("" ::: "memory");
}

extern "C" BB_NOINLINE std::uintptr_t* bb_chase(std::uintptr_t* node, std::size_t hops) {
    std::size_t i = 0;
    for (; i + 8 <= hops; i += 8) {
        node = reinterpret_cast<std::uintptr_t*>(*node);
        node = reinterpret_cast<std::uintptr_t*>(*node);
        node = reinterpret_cast<std::uintptr_t*>(*node);
        node = reinterpret_cast<std::uintptr_t*>(*node);
        node = reinterpret_cast<std::uintptr_t*>(*node);
        node = reinterpret_cast<std::uintptr_t*>(*node);
        node = reinterpret_cast<std::uintptr_t*>(*node);
        node = reinterpret_cast<std::uintptr_t*>(*node);
    }
    for (; i < hops; ++i) node = reinterpret_cast<std::uintptr_t*>(*node);
    asm volatile("" : "+r"(node) : : "memory");
    return node;
}
