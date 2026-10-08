#include <metal_stdlib>
using namespace metal;
struct Params { uint kind, count, iterations, width, height; };
uint pattern(uint index) { uint x=index*747796405u+419u*2891336453u+277803737u;x=((x>>((x>>28u)+4u))^x)*277803737u;return (x>>22u)^x; }
kernel void memoryRead(device const uint* src [[buffer(0)]],device uint* dst [[buffer(1)]],constant Params& p [[buffer(2)]],uint id [[thread_position_in_grid]]) {
    if(id>=p.count)return;uint sum=0;for(uint i=0;i<64;++i)sum+=src[id*64+i];dst[id]=sum;
}
kernel void memoryWrite(device const uint* src [[buffer(0)]],device uint* dst [[buffer(1)]],constant Params& p [[buffer(2)]],uint id [[thread_position_in_grid]]) {
    if(id<p.count)dst[id]=pattern(id);(void)src;
}
kernel void memoryCopy(device const uint* src [[buffer(0)]],device uint* dst [[buffer(1)]],constant Params& p [[buffer(2)]],uint id [[thread_position_in_grid]]) {
    if(id<p.count)dst[id]=src[id];
}
kernel void arithmetic(device const uint* src [[buffer(0)]],device uint* dst [[buffer(1)]],constant Params& p [[buffer(2)]],uint id [[thread_position_in_grid]]) {
    if(id>=p.count)return;(void)src;for(uint l=0;l<8;++l)dst[id*8+l]=0;
    if(p.kind==3){
        float4 v[8];for(uint a=0;a<8;++a)v[a]=float4(1)+float4(uint4(id+419+a*17)+uint4(0,3,6,9)&uint4(255))/256.0f;
        for(uint i=0;i<p.iterations;++i)for(uint a=0;a<8;++a)v[a]=fma(v[a],float4(1.0f+1.0f/8388608.0f),float4(float(a+1)/4096.0f));
        for(uint a=1;a<8;++a)v[0]+=v[a];for(uint l=0;l<4;++l)dst[id*8+l]=as_type<uint>(v[0][l]);
    }else if(p.kind==7){
        ulong2 v[8];for(uint a=0;a<8;++a)v[a]=ulong2(pattern(id+a*17),pattern(id+a*17+3));
        for(uint i=0;i<p.iterations;++i)for(uint a=0;a<8;++a)v[a]=v[a]*ulong2(1664525)+ulong2(1013904223+a);
        ulong2 sum=ulong2(0);for(uint a=0;a<8;++a)sum+=v[a];for(uint l=0;l<2;++l){dst[id*8+l*2]=uint(sum[l]);dst[id*8+l*2+1]=uint(sum[l]>>32);}
    }else{
        uint4 v[8];for(uint a=0;a<8;++a)v[a]=uint4(pattern(id+a*17),pattern(id+a*17+3),pattern(id+a*17+6),pattern(id+a*17+9));
        for(uint i=0;i<p.iterations;++i)for(uint a=0;a<8;++a){if(p.kind==5)v[a]&=uint4(0xffffff);v[a]=v[a]*uint4(1664525)+uint4(1013904223+a);}
        uint4 sum=uint4(0);for(uint a=0;a<8;++a)sum+=v[a];for(uint l=0;l<4;++l)dst[id*8+l]=sum[l];
    }
}
// 与 CPU 参考保持逐步浮点运算顺序。 / Match the CPU reference's individual floating-point operations.
#pragma clang fp contract(off)
kernel void julia(device const uint* src [[buffer(0)]],device uint* dst [[buffer(1)]],constant Params& p [[buffer(2)]],uint id [[thread_position_in_grid]]) {
    if(id>=p.count)return;(void)src;
    const float x=float(id%p.width)/float(p.width-1),y=float(id/p.width)/float(p.height-1);
    float zx=x*3.0f-1.5f,zy=y*2.0f-1.0f;uint n=0;
    while(n<128){float xx=zx*zx,yy=zy*zy;if(xx+yy>4)break;zy=2*zx*zy+0.27015f;zx=xx-yy-0.7f;++n;}dst[id]=n;
}
