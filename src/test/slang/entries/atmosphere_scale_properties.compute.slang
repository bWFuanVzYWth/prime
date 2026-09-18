import "model/atmosphere/sky.slang";
import "model/atmosphere/geometry.slang";
[[vk::binding(0,0)]] StructuredBuffer<float4> input;
[[vk::binding(1,0)]] RWStructuredBuffer<float4> output;
struct Push {uint count;};
[[vk::push_constant]] ConstantBuffer<Push> push;
[numthreads(64,1,1)]
void main(uint3 id:SV_DispatchThreadID) {
    if(id.x>=push.count) return;
    float4 v=input[id.x];
    float distance=v.w*ATM_WORLD_UNIT_SCALE_KM;
    output[id.x]=float4(atmBoundary(v.x,v.z,false),atmHeightAt(v.x,v.z,distance),distance,
        atmGround(v.x,v.z)?1.0:0.0);
}
