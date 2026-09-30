#include "compute_cpu_kernels.h"
#include <bit>
#include <cstring>
#if defined(__aarch64__)
#include <arm_neon.h>
#else
#include <emmintrin.h>
#endif

namespace bbcompute {
// 多个独立累加器保持运算吞吐，结果写回供正式计时后校验。
// Independent accumulators exercise arithmetic throughput; results are checked after timing.
__attribute__((noinline)) void cpu_kernel(int kind, std::uint32_t id, std::uint32_t iterations, std::uint32_t seed, Words& output) {
    output.fill(0);
    if (kind == Fp32) {
#if defined(__aarch64__)
        float32x4_t v[8]; const auto mul=vdupq_n_f32(1.0f+1.0f/8388608.0f);
#else
        __m128 v[8]; const auto mul=_mm_set1_ps(1.0f+1.0f/8388608.0f);
#endif
        for (int a=0;a<8;++a) {
            alignas(16) float f[4];for(int l=0;l<4;++l)f[l]=1.0f+float((id+seed+a*17+l*3)&255u)/256.0f;
#if defined(__aarch64__)
            v[a]=vld1q_f32(f);
#else
            v[a]=_mm_load_ps(f);
#endif
        }
        for(std::uint32_t i=0;i<iterations;++i) {
#pragma unroll
            for(int a=0;a<8;++a) {
#if defined(__aarch64__)
                v[a]=vfmaq_f32(vdupq_n_f32(float(a+1)/4096.0f),v[a],mul);
#else
                v[a]=_mm_add_ps(_mm_mul_ps(v[a],mul),_mm_set1_ps(float(a+1)/4096.0f));
#endif
            }
        }
        for(int a=1;a<8;++a) {
#if defined(__aarch64__)
            v[0]=vaddq_f32(v[0],v[a]);
#else
            v[0]=_mm_add_ps(v[0],v[a]);
#endif
        }
        float result[4];
#if defined(__aarch64__)
        vst1q_f32(result,v[0]);
#else
        _mm_storeu_ps(result,v[0]);
#endif
        std::memcpy(output.data(),result,sizeof(result));
    } else if(kind==Fp64) {
#if defined(__aarch64__)
        float64x2_t v[8];const auto mul=vdupq_n_f64(1.0+1.0/8388608.0);
#else
        __m128d v[8];const auto mul=_mm_set1_pd(1.0+1.0/8388608.0);
#endif
        for(int a=0;a<8;++a) {
            alignas(16) double f[2];for(int l=0;l<2;++l)f[l]=1.0+double((id+seed+a*17+l*3)&255u)/256.0;
#if defined(__aarch64__)
            v[a]=vld1q_f64(f);
#else
            v[a]=_mm_load_pd(f);
#endif
        }
        for(std::uint32_t i=0;i<iterations;++i) {
#pragma unroll
            for(int a=0;a<8;++a) {
#if defined(__aarch64__)
                v[a]=vfmaq_f64(vdupq_n_f64(double(a+1)/4096.0),v[a],mul);
#else
                v[a]=_mm_add_pd(_mm_mul_pd(v[a],mul),_mm_set1_pd(double(a+1)/4096.0));
#endif
            }
        }
        for(int a=1;a<8;++a) {
#if defined(__aarch64__)
            v[0]=vaddq_f64(v[0],v[a]);
#else
            v[0]=_mm_add_pd(v[0],v[a]);
#endif
        }
        double result[2];
#if defined(__aarch64__)
        vst1q_f64(result,v[0]);
#else
        _mm_storeu_pd(result,v[0]);
#endif
        std::memcpy(output.data(),result,sizeof(result));
    } else if(kind==Int24||kind==Int32) {
#if defined(__aarch64__)
        uint32x4_t v[8];const auto mul=vdupq_n_u32(1664525u),mask=vdupq_n_u32(0xffffffu);
#else
        __m128i v[8];const auto mul=_mm_set1_epi32(1664525),mask=_mm_set1_epi32(0xffffff);
#endif
        for(int a=0;a<8;++a) {
            alignas(16) std::uint32_t init[4];for(int l=0;l<4;++l)init[l]=pattern(id+a*17+l*3,seed);
#if defined(__aarch64__)
            v[a]=vld1q_u32(init);
#else
            v[a]=_mm_load_si128(reinterpret_cast<const __m128i*>(init));
#endif
        }
        for(std::uint32_t i=0;i<iterations;++i) {
#pragma unroll
            for(int a=0;a<8;++a) {
#if defined(__aarch64__)
                if(kind==Int24)v[a]=vandq_u32(v[a],mask);
                v[a]=vmlaq_u32(vdupq_n_u32(1013904223u+a),v[a],mul);
#else
                if(kind==Int24)v[a]=_mm_and_si128(v[a],mask);
                const auto even=_mm_mul_epu32(v[a],mul),odd=_mm_mul_epu32(_mm_srli_si128(v[a],4),mul);
                const auto product=_mm_unpacklo_epi32(_mm_shuffle_epi32(even,0x88),_mm_shuffle_epi32(odd,0x88));
                v[a]=_mm_add_epi32(product,_mm_set1_epi32(1013904223u+a));
#endif
            }
        }
        for(int a=1;a<8;++a) {
#if defined(__aarch64__)
            v[0]=vaddq_u32(v[0],v[a]);
#else
            v[0]=_mm_add_epi32(v[0],v[a]);
#endif
        }
#if defined(__aarch64__)
        vst1q_u32(output.data(),v[0]);
#else
        _mm_storeu_si128(reinterpret_cast<__m128i*>(output.data()),v[0]);
#endif
    } else if(kind==Int64) {
        std::uint64_t v[16];for(int a=0;a<8;++a)for(int l=0;l<2;++l)v[a*2+l]=pattern(id+a*17+l*3,seed);
        for(std::uint32_t i=0;i<iterations;++i) {
#pragma unroll
            for(int a=0;a<16;++a)v[a]=v[a]*1664525ull+1013904223ull+a/2;
        }
        for(int l=0;l<2;++l){std::uint64_t sum=0;for(int a=0;a<8;++a)sum+=v[a*2+l];output[l*2]=sum;output[l*2+1]=sum>>32;}
    }
    asm volatile("" : : "r"(output.data()) : "memory");
}
__attribute__((noinline)) void cpu_frame(int kind, std::uint32_t width, std::uint32_t height, std::uint32_t* pixels, std::atomic<bool>& cancelled) {
    for(std::uint32_t y=0;y<height;++y){
        if(cancelled.load(std::memory_order_relaxed))return;
        for(std::uint32_t x=0;x<width;++x)pixels[y*width+x]=fractal_pixel(kind,y*width+x,width,height,128);
    }
    asm volatile("" : : "r"(pixels) : "memory");
}
}
