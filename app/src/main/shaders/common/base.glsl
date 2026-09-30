layout(local_size_x_id = 0) in;
layout(set=0,binding=0,std430) buffer Output { uint values[]; } outputData;
layout(set=0,binding=1,std430) readonly buffer Input { uint values[]; } inputData;
layout(push_constant) uniform Params { uint count; uint iterations; uint seed; uint kind; uint width; uint height; uint span; uint base; } p;
uint pattern(uint index,uint seed) {
    uint x=index*747796405u+seed*2891336453u+277803737u;
    x=((x>>((x>>28u)+4u))^x)*277803737u;
    return (x>>22u)^x;
}
