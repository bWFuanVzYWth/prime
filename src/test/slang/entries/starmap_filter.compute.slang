// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.
import "math/starmap_filter.slang";
import "service/atmosphere/starmap_filter.slang";
layout(set = 0, binding = 0, std430) readonly buffer Inputs { float4 values[]; } inputData;
layout(set = 0, binding = 1, std430) writeonly buffer Outputs { float4 values[]; } outputData;
[[vk::binding(2, 0)]] Sampler2D starmap;
[numthreads(1, 1, 1)]
void main(uint3 id : SV_DispatchThreadID) {
    float4 a = inputData.values[id.x * 2u];
    float4 b = inputData.values[id.x * 2u + 1u];
    uint width, height;
    starmap.GetDimensions(width, height);
    PrimeStarmapFootprint footprint = primeStarmapFootprint(a.zw, b.xy, float2(width, height));
    float3 filtered = primeSampleStarmap(starmap, a.xy, footprint);
    float raw = starmap.SampleLevel(a.xy, 0.0).r;
    outputData.values[id.x * 2u] = float4(filtered.r, raw, footprint.lod, float(footprint.taps));
    outputData.values[id.x * 2u + 1u] = float4(
            primeCompositeStars(float3(1.0, 2.0, 3.0), float3(4.0, 8.0, 12.0), b.z),
            primeStarmapWrappedDelta(float2(0.001, 0.5), float2(0.999, 0.5)).x);
}
