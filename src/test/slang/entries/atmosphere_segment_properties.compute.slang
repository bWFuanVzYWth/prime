import "model/atmosphere/sky.slang";
import "model/atmosphere/geometry.slang";
import "model/atmosphere/integrate.slang";
[[vk::binding(33, 0)]] StructuredBuffer<float4> input;
[[vk::binding(34, 0)]] RWStructuredBuffer<float4> output;
[numthreads(64, 1, 1)]
void main(uint3 id : SV_DispatchThreadID) {
    uint count, stride;
    input.GetDimensions(count,stride);
    if (id.x*2u >= count) return;
    float4 ray = input[id.x*2u], sun = input[id.x*2u+1u];
    float split = sun.w*0.4;
    AtmTransport full = atmIntegrateAt(ray.xyz,sun.xyz,ray.w,sun.w,96u,true);
    AtmTransport first = atmIntegrateAt(ray.xyz,sun.xyz,ray.w,split,96u,true);
    float height = atmHeightAt(ray.w,ray.y,split);
    float3 up = normalize(float3(0,ATM_BOTTOM_RADIUS_KM+ray.w,0)+ray.xyz*split);
    float3 side = normalize(cross(up,float3(1,0,0)));
    float3 forward = cross(side,up);
    float3 localRay = float3(dot(ray.xyz,side),dot(ray.xyz,up),dot(ray.xyz,forward));
    float3 localSun = float3(dot(sun.xyz,side),dot(sun.xyz,up),dot(sun.xyz,forward));
    AtmTransport last = atmIntegrateAt(localRay,localSun,height,sun.w-split,96u,true);
    output[id.x*4u] = full.light;
    output[id.x*4u+1u] = first.light+first.transmittance*last.light;
    output[id.x*4u+2u] = full.transmittance;
    output[id.x*4u+3u] = first.transmittance*last.transmittance;
}
