#language slang 2026
module foundations_test;
import "math/color.slang";
import "math/z_sobol.slang";
import "math/ray_offset.slang";

// These bindings belong exclusively to the test entry, never to the imported libraries.
[[vk::binding(0, 0)]] StructuredBuffer<uint4> inputs;
[[vk::binding(1, 0)]] RWStructuredBuffer<uint4> outputs;
[[vk::push_constant]] ConstantBuffer<uint4> config; // mode, case count, reserved

[shader("compute")][numthreads(64, 1, 1)] void main(uint3 id : SV_DispatchThreadID) {
    uint i = id.x;
    if (i >= config.y)
        return;
    if (config.x == 0) {
        uint4 a = inputs[2 * i], b = inputs[2 * i + 1];
        ZSobol sampler = makeZSobol(a.xy, a.z, a.w, b.x, b.y);
        outputs[2 * i] = uint4(sampler.bits2D(b.z), sampler.bits1D(b.z), 0);
        outputs[2 * i + 1] = uint4(asuint(sampler.sample2D(b.z)), asuint(sampler.sample1D(b.z)), 0);
    } else if (config.x == 1) {
        float3 rgb = asfloat(inputs[i]).xyz;
        float3 working = sourceToWorking(rgb);
        outputs[4 * i] = asuint(float4(working, workingLuminance(working)));
        outputs[4 * i + 1] = asuint(float4(encodeSrgb(workingToLinear709(working)), 0));
        outputs[4 * i + 2] = asuint(float4(workingToLinear709(working), 0));
        outputs[4 * i + 3] = asuint(float4(encodeSrgb(rgb), 0));
    } else {
        uint base = 10 * i;
        float3 v0 = asfloat(inputs[base]).xyz;
        float3 v1 = asfloat(inputs[base + 1]).xyz;
        float3 v2 = asfloat(inputs[base + 2]).xyz;
        float2 bary = asfloat(inputs[base + 3]).xy;
        float3x4 o2w = float3x4(asfloat(inputs[base + 4]), asfloat(inputs[base + 5]),
                                asfloat(inputs[base + 6]));
        float3x4 w2o = float3x4(asfloat(inputs[base + 7]), asfloat(inputs[base + 8]),
                                asfloat(inputs[base + 9]));
        SurfacePoint hit =
                config.x == 3 ? reconstructStaticSurface(v0, v1, v2, bary,
                                                         float3(o2w[0][3], o2w[1][3], o2w[2][3]))
                              : reconstructSurface(v0, v1, v2, bary, o2w, w2o);
        outputs[4 * i] = asuint(float4(hit.position, hit.offset));
        outputs[4 * i + 1] = asuint(float4(hit.normal, 0));
        outputs[4 * i + 2] = asuint(float4(spawnRay(hit, hit.normal), 0));
        outputs[4 * i + 3] = asuint(float4(spawnRay(hit, -hit.normal), 0));
    }
}
