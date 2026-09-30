#include "compute_common.h"
#include <algorithm>
#include <array>
#include <bit>
#include <cstring>
#include <stdexcept>
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
template<int Count> __attribute__((target("aes"), noinline)) void aes_hardware(const std::uint8_t* input, std::uint8_t* output) {
    uint8x16_t data[Count];
#pragma unroll
    for (int i = 0; i < Count; ++i) data[i] = vld1q_u8(input + i*16);
    for (int round = 0; round < 13; ++round) {
        const auto key = vld1q_u8(keys.data()+round*16);
#pragma unroll
        for (int i = 0; i < Count; ++i) data[i] = vaesmcq_u8(vaeseq_u8(data[i],key));
    }
#pragma unroll
    for (int i = 0; i < Count; ++i) vst1q_u8(output+i*16,veorq_u8(vaeseq_u8(data[i],vld1q_u8(keys.data()+208)),vld1q_u8(keys.data()+224)));
}
__attribute__((target("sha2"), noinline)) void sha_compress_hardware(std::uint32_t* h, const std::uint8_t* block) {
    uint32x4_t w[4];
    for (int i=0;i<4;++i) w[i]=vreinterpretq_u32_u8(vrev32q_u8(vld1q_u8(block+i*16)));
    auto abcd = vld1q_u32(h); auto e = h[4];
#pragma unroll
    for (int group = 0; group < 20; ++group) {
        const std::uint32_t k = group < 5 ? 0x5a827999u : group < 10 ? 0x6ed9eba1u : group < 15 ? 0x8f1bbcdcu : 0xca62c1d6u;
        const int i=group&3;
        auto input = vaddq_u32(w[i], vdupq_n_u32(k));
        const auto nextE = vsha1h_u32(vgetq_lane_u32(abcd,0));
        if (group < 5) abcd = vsha1cq_u32(abcd,e,input);
        else if (group < 10 || group >= 15) abcd = vsha1pq_u32(abcd,e,input);
        else abcd = vsha1mq_u32(abcd,e,input);
        e = nextE;
        // 四组向量循环扩展消息，覆盖 W16 至 W79。
        // Rotate four vectors through the hardware schedule for W16 through W79.
        if(group<16)w[i]=vsha1su1q_u32(vsha1su0q_u32(w[i],w[(i+1)&3],w[(i+2)&3]),w[(i+3)&3]);
    }
    vst1q_u32(h,vaddq_u32(abcd,vld1q_u32(h))); h[4] += e;
}
#elif defined(__x86_64__)
template<int Count> __attribute__((target("aes,sse2"), noinline)) void aes_hardware(const std::uint8_t* input, std::uint8_t* output) {
    __m128i data[Count];
#pragma unroll
    for (int i=0;i<Count;++i) data[i]=_mm_xor_si128(_mm_loadu_si128(reinterpret_cast<const __m128i*>(input+i*16)),_mm_load_si128(reinterpret_cast<const __m128i*>(keys.data())));
    for (int r=1;r<14;++r) {
#pragma unroll
        for (int i=0;i<Count;++i) data[i]=_mm_aesenc_si128(data[i],_mm_load_si128(reinterpret_cast<const __m128i*>(keys.data()+r*16)));
    }
#pragma unroll
    for (int i=0;i<Count;++i) _mm_storeu_si128(reinterpret_cast<__m128i*>(output+i*16),_mm_aesenclast_si128(data[i],_mm_load_si128(reinterpret_cast<const __m128i*>(keys.data()+224))));
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
void aes_buffer(const std::uint8_t* input, std::uint8_t* output, std::size_t length, bool accelerated) {
    if(length%16)throw std::invalid_argument("AES_BLOCK_LENGTH");
    std::size_t offset=0;
    if(accelerated){
        for(;offset+128<=length;offset+=128)aes_hardware<8>(input+offset,output+offset);
        for(;offset<length;offset+=16)aes_hardware<1>(input+offset,output+offset);
    }else for(;offset<length;offset+=16)aes_block(input+offset,output+offset);
    asm volatile("" : : "r"(output) : "memory");
}
namespace {
void sha_compress_portable(std::uint32_t* h,const std::uint8_t* input){
    std::uint32_t w[80];
    for(int i=0;i<16;++i)w[i]=be32(input+i*4);
    for(int i=16;i<80;++i)w[i]=std::rotl(w[i-3]^w[i-8]^w[i-14]^w[i-16],1);
    auto a=h[0],b=h[1],c=h[2],d=h[3],e=h[4];
    for(int i=0;i<80;++i){
        const auto f=i<20?((b&c)|(~b&d)):i<40?(b^c^d):i<60?((b&c)|(b&d)|(c&d)):(b^c^d);
        const auto k=i<20?0x5a827999u:i<40?0x6ed9eba1u:i<60?0x8f1bbcdcu:0xca62c1d6u;
        const auto t=std::rotl(a,5)+f+e+k+w[i];e=d;d=c;c=std::rotl(b,30);b=a;a=t;
    }
    h[0]+=a;h[1]+=b;h[2]+=c;h[3]+=d;h[4]+=e;
}
}
void sha_message(const std::uint8_t* input, std::size_t length, std::uint32_t* h, bool accelerated) {
    h[0]=0x67452301;h[1]=0xefcdab89;h[2]=0x98badcfe;h[3]=0x10325476;h[4]=0xc3d2e1f0;
    auto compress=[&](const std::uint8_t* block){
#if defined(__aarch64__)
        if(accelerated){sha_compress_hardware(h,block);return;}
#else
        (void)accelerated;
#endif
        sha_compress_portable(h,block);
    };
    std::size_t offset=0;
    for(;offset+64<=length;offset+=64)compress(input+offset);
    std::uint8_t padded[128]{};const auto remaining=length-offset;
    if(remaining)std::copy_n(input+offset,remaining,padded);
    padded[remaining]=0x80;const int total=remaining<56?64:128;
    const auto bits=static_cast<std::uint64_t>(length)*8;
    for(int b=0;b<8;++b)padded[total-1-b]=bits>>(b*8);
    for(int block=0;block<total;block+=64)compress(padded+block);
}
bool crypto_self_test() {
    // FIPS 197 AES-256 与 FIPS 180 SHA-1 的已知答案。
    // Known answers for FIPS 197 AES-256 and FIPS 180 SHA-1.
    constexpr std::uint8_t plaintext[16]={0,0x11,0x22,0x33,0x44,0x55,0x66,0x77,0x88,0x99,0xaa,0xbb,0xcc,0xdd,0xee,0xff};
    constexpr std::uint8_t cipher[16]={0x8e,0xa2,0xb7,0xca,0x51,0x67,0x45,0xbf,0xea,0xfc,0x49,0x90,0x4b,0x49,0x60,0x89};
    std::uint8_t out[16]; aes_block(plaintext,out);
    if (!std::equal(out,out+16,cipher)) return false;
    if (aes_accelerated()) { aes_hardware<1>(plaintext,out);if (!std::equal(out,out+16,cipher)) return false; }
    constexpr std::uint32_t expected[5]={0xa9993e36,0x4706816a,0xba3e2571,0x7850c26c,0x9cd0d89d};
    std::uint32_t digest[5];sha_message(reinterpret_cast<const std::uint8_t*>("abc"),3,digest,false);
    if (!std::equal(digest,digest+5,expected)) return false;
    sha_message(reinterpret_cast<const std::uint8_t*>("abc"),3,digest,sha_accelerated());
    if(!std::equal(digest,digest+5,expected))return false;
    std::array<std::uint8_t,256> aesInput{},aesOutput{},aesReference{};
    for(std::size_t i=0;i<aesInput.size();++i)aesInput[i]=static_cast<std::uint8_t>(i*17+11);
    for(std::size_t i=0;i<aesInput.size();i+=16)aes_block(aesInput.data()+i,aesReference.data()+i);
    for(std::size_t size:{16u,128u,144u,256u})for(bool accelerated:{false,aes_accelerated()}){
        aesOutput.fill(0xcd);
        aes_buffer(aesInput.data(),aesOutput.data(),size,accelerated);
        if(!std::equal(aesOutput.begin(),aesOutput.begin()+size,aesReference.begin()))return false;
    }
    // 独立已知摘要覆盖填充边界和正式消息长度。
    // Independent known digests cover padding boundaries and the measured message size.
    struct Case{std::size_t length;std::array<std::uint32_t,5> digest;};
    const Case cases[]={
        {0,{0xda39a3ee,0x5e6b4b0d,0x3255bfef,0x95601890,0xafd80709}},
        {55,{0xe33b5fe8,0x21e8bee6,0xa1a34f44,0xfe0857fe,0x8aaaeaa0}},
        {56,{0x4064ac9f,0x344e3607,0xdeee0b06,0x28f596db,0xb4d30bce}},
        {63,{0xa94b03ee,0x2fc2c1da,0x8904d8e5,0xfd0eac9b,0x8d432c3a}},
        {64,{0xf41dc3f7,0xad3638ed,0x96d68bb1,0x58155139,0x845c8171}},
        {65,{0xeec86c77,0x395b30bb,0xb7339692,0x538bac57,0x7ccea8a0}},
        {crypto_message_bytes,{0x480a3dc7,0x7c7241dd,0xead3fb5b,0x089454ec,0xd0907e16}},
    };
    std::vector<std::uint8_t> message(crypto_message_bytes);
    for(std::size_t i=0;i<message.size();++i)message[i]=static_cast<std::uint8_t>(i*17+11);
    for(const auto& test:cases)for(bool accelerated:{false,sha_accelerated()}){
        sha_message(message.data(),test.length,digest,accelerated);
        if(!std::equal(digest,digest+5,test.digest.begin()))return false;
    }
    return true;
}
}
