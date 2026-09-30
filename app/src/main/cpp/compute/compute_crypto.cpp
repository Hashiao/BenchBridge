#include "compute_common.h"
#include <algorithm>
#include <array>
#include <bit>
#include <cstring>
#if defined(__aarch64__)
#include <arm_neon.h>
#include <asm/hwcap.h>
#include <sys/auxv.h>
#elif defined(__x86_64__)
#include <cpuid.h>
#include <wmmintrin.h>
#endif

namespace bbcompute {
namespace {
constexpr std::uint8_t multiply(std::uint8_t a, std::uint8_t b) {
    std::uint8_t out = 0;
    for (int i = 0; i < 8; ++i) { if (b & 1) out ^= a; a = (a << 1) ^ ((a & 128) ? 0x1b : 0); b >>= 1; }
    return out;
}
constexpr auto make_sbox() {
    std::array<std::uint8_t, 256> table{};
    for (unsigned i = 0; i < 256; ++i) {
        std::uint8_t x = i ? 1 : 0, p = i;
        for (unsigned n = 254; n; n >>= 1) { if (n & 1) x = multiply(x,p); p = multiply(p,p); }
        table[i] = x ^ std::rotl(x,1) ^ std::rotl(x,2) ^ std::rotl(x,3) ^ std::rotl(x,4) ^ 0x63;
    }
    return table;
}
constexpr auto sbox = make_sbox();
constexpr auto expand_key() {
    std::array<std::uint8_t,240> key{};
    for (unsigned i = 0; i < 32; ++i) key[i] = i;
    std::uint8_t rc = 1;
    for (unsigned pos = 32; pos < 240; pos += 4) {
        std::uint8_t t[4]; for (unsigned i = 0; i < 4; ++i) t[i] = key[pos-4+i];
        if (pos % 32 == 0) {
            auto first = t[0]; t[0] = sbox[t[1]] ^ rc; t[1] = sbox[t[2]]; t[2] = sbox[t[3]]; t[3] = sbox[first]; rc = multiply(rc,2);
        } else if (pos % 32 == 16) for (auto& v : t) v = sbox[v];
        for (unsigned i = 0; i < 4; ++i) key[pos+i] = key[pos-32+i] ^ t[i];
    }
    return key;
}
alignas(16) constexpr auto keys = expand_key();
std::uint32_t be32(const std::uint8_t* p) { return std::uint32_t(p[0]) << 24 | std::uint32_t(p[1]) << 16 | std::uint32_t(p[2]) << 8 | p[3]; }

#if defined(__aarch64__)
__attribute__((target("aes"), noinline)) void aes_hardware(const std::uint8_t* input, std::uint8_t* output, int count) {
    uint8x16_t data[8];
    for (int i = 0; i < count; ++i) data[i] = vld1q_u8(input + i*16);
    for (int round = 0; round < 13; ++round) {
        const auto key = vld1q_u8(keys.data()+round*16);
        for (int i = 0; i < count; ++i) data[i] = vaesmcq_u8(vaeseq_u8(data[i],key));
    }
    for (int i = 0; i < count; ++i) vst1q_u8(output+i*16,veorq_u8(vaeseq_u8(data[i],vld1q_u8(keys.data()+208)),vld1q_u8(keys.data()+224)));
}
__attribute__((target("sha2"), noinline)) void sha_compress_hardware(std::uint32_t* h, const std::uint32_t* w) {
    auto abcd = vld1q_u32(h); auto e = h[4];
    for (int group = 0; group < 20; ++group) {
        const std::uint32_t k = group < 5 ? 0x5a827999u : group < 10 ? 0x6ed9eba1u : group < 15 ? 0x8f1bbcdcu : 0xca62c1d6u;
        auto input = vaddq_u32(vld1q_u32(w + group*4), vdupq_n_u32(k));
        const auto nextE = vsha1h_u32(vgetq_lane_u32(abcd,0));
        if (group < 5) abcd = vsha1cq_u32(abcd,e,input);
        else if (group < 10 || group >= 15) abcd = vsha1pq_u32(abcd,e,input);
        else abcd = vsha1mq_u32(abcd,e,input);
        e = nextE;
    }
    vst1q_u32(h,vaddq_u32(abcd,vld1q_u32(h))); h[4] += e;
}
#elif defined(__x86_64__)
__attribute__((target("aes,sse2"), noinline)) void aes_hardware(const std::uint8_t* input, std::uint8_t* output, int count) {
    __m128i data[8];
    for (int i=0;i<count;++i) data[i]=_mm_xor_si128(_mm_loadu_si128(reinterpret_cast<const __m128i*>(input+i*16)),_mm_load_si128(reinterpret_cast<const __m128i*>(keys.data())));
    for (int r=1;r<14;++r) for (int i=0;i<count;++i) data[i]=_mm_aesenc_si128(data[i],_mm_load_si128(reinterpret_cast<const __m128i*>(keys.data()+r*16)));
    for (int i=0;i<count;++i) _mm_storeu_si128(reinterpret_cast<__m128i*>(output+i*16),_mm_aesenclast_si128(data[i],_mm_load_si128(reinterpret_cast<const __m128i*>(keys.data()+224))));
}
#endif
}
bool aes_accelerated() {
#if defined(__aarch64__)
    return getauxval(AT_HWCAP) & HWCAP_AES;
#else
    unsigned a,b,c,d; return __get_cpuid(1,&a,&b,&c,&d) && (c & bit_AES);
#endif
}
bool sha_accelerated() {
#if defined(__aarch64__)
    return getauxval(AT_HWCAP) & HWCAP_SHA1;
#else
    return false;
#endif
}
void aes_block(const std::uint8_t* input, std::uint8_t* output) {
    std::uint8_t state[16];
    for (int i=0;i<16;++i) state[i]=input[i]^keys[i];
    for (int round=1;round<=14;++round) {
        std::uint8_t shifted[16];
        for (int col=0;col<4;++col) for (int row=0;row<4;++row) shifted[4*col+row]=sbox[state[4*((col+row)%4)+row]];
        for (int col=0;col<4;++col) {
            auto* a=shifted+col*4;
            if (round<14) {
                state[col*4]=multiply(a[0],2)^multiply(a[1],3)^a[2]^a[3];
                state[col*4+1]=a[0]^multiply(a[1],2)^multiply(a[2],3)^a[3];
                state[col*4+2]=a[0]^a[1]^multiply(a[2],2)^multiply(a[3],3);
                state[col*4+3]=multiply(a[0],3)^a[1]^a[2]^multiply(a[3],2);
            } else std::copy_n(a,4,state+col*4);
        }
        for (int i=0;i<16;++i) state[i]^=keys[round*16+i];
    }
    std::copy_n(state,16,output);
}
void aes_fast_batch(std::uint32_t id, std::uint32_t count, std::uint32_t seed, Words& output) {
    output.fill(0);
    const bool accelerated=aes_accelerated();
    for (std::uint32_t start=0;start<count;start+=8) {
        alignas(16) std::uint8_t input[128], encrypted[128];
        const int n=std::min(8u,count-start);
        for (int block=0;block<n;++block) for (int w=0;w<4;++w) {
            auto x=pattern(id*131u+(start+block)*17u+w,seed);
            for (int b=0;b<4;++b) input[block*16+w*4+b]=x>>(24-b*8);
        }
        if (accelerated) aes_hardware(input,encrypted,n);
        else for (int b=0;b<n;++b) aes_block(input+b*16,encrypted+b*16);
        for (int b=0;b<n;++b) for (int w=0;w<4;++w) output[w]^=be32(encrypted+b*16+w*4);
    }
}
void sha_message(const std::uint8_t* input, std::size_t length, std::uint32_t* h, bool accelerated) {
    std::uint8_t padded[128]{};
    std::copy_n(input,length,padded); padded[length]=0x80;
    const int total=length<56?64:128;
    const auto bits=static_cast<std::uint64_t>(length)*8;
    for (int b=0;b<8;++b) padded[total-1-b]=bits>>(b*8);
    h[0]=0x67452301;h[1]=0xefcdab89;h[2]=0x98badcfe;h[3]=0x10325476;h[4]=0xc3d2e1f0;
    for (int block=0;block<total;block+=64) {
        std::uint32_t w[80];
        for (int i=0;i<16;++i) w[i]=be32(padded+block+i*4);
        for (int i=16;i<80;++i) w[i]=std::rotl(w[i-3]^w[i-8]^w[i-14]^w[i-16],1);
#if defined(__aarch64__)
        if (accelerated) { sha_compress_hardware(h,w);continue; }
#else
        (void)accelerated;
#endif
        auto a=h[0],b=h[1],c=h[2],d=h[3],e=h[4];
        for (int i=0;i<80;++i) {
            auto f=i<20?((b&c)|(~b&d)):i<40?(b^c^d):i<60?((b&c)|(b&d)|(c&d)):(b^c^d);
            auto k=i<20?0x5a827999u:i<40?0x6ed9eba1u:i<60?0x8f1bbcdcu:0xca62c1d6u;
            auto t=std::rotl(a,5)+f+e+k+w[i];e=d;d=c;c=std::rotl(b,30);b=a;a=t;
        }
        h[0]+=a;h[1]+=b;h[2]+=c;h[3]+=d;h[4]+=e;
    }
}
bool crypto_self_test() {
    // FIPS 197 AES-256 与 FIPS 180 SHA-1 的已知答案。
    // Known answers for FIPS 197 AES-256 and FIPS 180 SHA-1.
    constexpr std::uint8_t plaintext[16]={0,0x11,0x22,0x33,0x44,0x55,0x66,0x77,0x88,0x99,0xaa,0xbb,0xcc,0xdd,0xee,0xff};
    constexpr std::uint8_t cipher[16]={0x8e,0xa2,0xb7,0xca,0x51,0x67,0x45,0xbf,0xea,0xfc,0x49,0x90,0x4b,0x49,0x60,0x89};
    std::uint8_t out[16]; aes_block(plaintext,out);
    if (!std::equal(out,out+16,cipher)) return false;
    if (aes_accelerated()) { aes_hardware(plaintext,out,1);if (!std::equal(out,out+16,cipher)) return false; }
    constexpr std::uint32_t expected[5]={0xa9993e36,0x4706816a,0xba3e2571,0x7850c26c,0x9cd0d89d};
    std::uint32_t digest[5];sha_message(reinterpret_cast<const std::uint8_t*>("abc"),3,digest,false);
    if (!std::equal(digest,digest+5,expected)) return false;
    sha_message(reinterpret_cast<const std::uint8_t*>("abc"),3,digest,sha_accelerated());
    return std::equal(digest,digest+5,expected);
}
}
