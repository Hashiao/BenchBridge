#include "base.glsl"
#ifdef USE_DOUBLE
#define VEC dvec2
#define REAL double
#define LANES 2
#else
#define VEC vec4
#define REAL float
#define LANES 4
#endif
// 每个乘加计两次运算，保留八组独立累加器。
// Count two operations per multiply-add across eight independent accumulators.
void main() {
    uint id=gl_GlobalInvocationID.x;if(id>=p.count)return;
    VEC v[8];
    [[unroll]] for(int a=0;a<8;++a) [[unroll]] for(int l=0;l<LANES;++l)
        v[a][l]=REAL(1)+REAL((id+p.seed+uint(a*17+l*3))&255u)/REAL(256);
    VEC mul=VEC(REAL(1)+REAL(1)/REAL(8388608));
    for(uint i=0;i<p.iterations;++i) [[unroll]] for(int a=0;a<8;++a) v[a]=fma(v[a],mul,VEC(REAL(a+1)/REAL(4096)));
    VEC sum=v[0];[[unroll]] for(int a=1;a<8;++a)sum+=v[a];
    [[unroll]] for(int w=0;w<8;++w)outputData.values[id*8+uint(w)]=0;
#ifdef USE_DOUBLE
    uvec2 lo=unpackDouble2x32(sum.x),hi=unpackDouble2x32(sum.y);
    outputData.values[id*8]=lo.x;outputData.values[id*8+1]=lo.y;outputData.values[id*8+2]=hi.x;outputData.values[id*8+3]=hi.y;
#else
    [[unroll]] for(int l=0;l<4;++l)outputData.values[id*8+uint(l)]=floatBitsToUint(sum[l]);
#endif
}
