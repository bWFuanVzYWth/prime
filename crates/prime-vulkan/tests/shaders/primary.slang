#language slang 2026
module primary_test;
import "math/z_sobol.slang";
import "math/ray_offset.slang";
import "pbr/vertex.slang";
import "trace/closest.slang";
import "realtime/primary.slang";
import "realtime/primary_parameters.slang";
import "realtime/primary_guide_contract.slang";
import "realtime/primary_environment.slang";
import "realtime/landing.slang";
import "reconstruct/primary_psr.slang";

[[vk::binding(0, 0)]] StructuredBuffer<uint4> inputs;
[[vk::binding(1, 0)]] RWStructuredBuffer<float4> outputs;
[[vk::binding(2, 0)]] RaytracingAccelerationStructure acceleration;
[[vk::binding(3, 0)]] StructuredBuffer<uint4> textures;
[[vk::binding(4, 0)]] StructuredBuffer<uint> texels;
[[vk::binding(5, 0)]] StructuredBuffer<InstanceMaterial> instanceMaterials;
[[vk::binding(6, 0)]] StructuredBuffer<TrianglePage> staticTrianglePages;
struct TestParameters {
    uint mode;
    uint count;
    float4 *scratchAddress;
};
[[vk::push_constant]] ConstantBuffer<TestParameters> config;

// Only the environment radiance and final guide sink are replaced. The production query,
// material classification, delta sampling, roulette, shared traversal and seed paths all run.
public struct TestEnvironment : IPrimaryEnvironment {
    public float3 escape(float3 direction) {
        return float3(1.0);
    }
};
public struct TestGuides : IPrimaryGuides {
    public bool enabled() {
        return true;
    }
    public float2 sampleOffset(ZSobol sampler) {
        return float2(0.5);
    }
    public void initialize(uint2 pixel) {
        for (uint plane = 0; plane < 8; ++plane)
            outputs[pixel.x * 8 + plane] = float4(0.0);
    }
    public void interfaceSpecular(uint2 pixel, PrimePbrVertex material, float3 view,
                                  float4 incident, float4 transmitted) {
        outputs[pixel.x * 8 + 5].z += 1.0;
    }
    public void reflectionPending(uint2 pixel) {}
    public void surface(PrimaryParameters camera, uint2 pixel, float2 sampleUv,
                        SurfacePoint surface, PrimePbrVertex material, float3 direction,
                        float4 incident, float4 transmitted, bool staticGeometry,
                        PrimePrimaryPsr psr) {
        uint base = pixel.x * 8;
        if ((psr.flags & PRIME_PRIMARY_GUIDE_REFLECTION) != 0) {
            outputs[base + 6].w += 1.0;
            return;
        }
        float3 virtualPosition, virtualNormal;
        bool valid = primePrimaryPsrSurface(psr, camera.position.xyz, camera.forward.xyz,
                                            surface.position, surface.normal, material.normal,
                                            virtualPosition, virtualNormal);
        outputs[base] = float4(surface.position, 1.0);
        outputs[base + 1] = float4(virtualPosition,
                                   primePrimaryPsrMotionKnown(psr, staticGeometry) ? 1.0 : 0.0);
        outputs[base + 2] = float4(virtualNormal, asfloat(psr.flags));
        outputs[base + 5].w += 1.0;
        outputs[base + 6].yz = float2(valid ? 1.0 : 0.0, material.roughness);
    }
    public void environment(PrimaryParameters camera, uint2 pixel, float2 sampleUv,
                            float3 direction, PrimePrimaryPsr psr) {
        uint base = pixel.x * 8;
        if ((psr.flags & PRIME_PRIMARY_GUIDE_REFLECTION) != 0) {
            outputs[base + 6].w += 1.0;
            return;
        }
        outputs[base] = float4(direction, 2.0);
        outputs[base + 2].w = asfloat(psr.flags);
        outputs[base + 5].w += 1.0;
    }
    public void unresolved(uint2 pixel, uint flags) {
        if ((flags & PRIME_PRIMARY_GUIDE_REFLECTION) != 0) {
            outputs[pixel.x * 8 + 6].w -= 1.0;
            return;
        }
        outputs[pixel.x * 8] = float4(0, 0, 0, 3);
        outputs[pixel.x * 8 + 5].w += 1.0;
    }
};

[shader("compute")][numthreads(64, 1, 1)] void main(uint3 id : SV_DispatchThreadID) {
    uint index = id.x;
    if (index >= config.count)
        return;
    uint4 input = inputs[index];
    PrimaryParameters parameters;
    parameters.position =
            float4(2, 2, 0, 0); // tan(FOV/2)=0 fixes the geometric ray across samples.
    parameters.forward = float4(0, 0, 1, 1);
    parameters.right = float4(1, 0, 0, 1);
    parameters.up = float4(0, 1, 0, 1);
    parameters.sampling = uint4(config.count, 1, input.y, input.x);
    parameters.config = uint4(8, input.z, 0, 0);
    // 8 report planes (AoS), 11 RR common + 2 optical planes (SoA), one prefix plane.
    parameters.commonAddress = config.scratchAddress + config.count * 8u;
    parameters.opticalAddress = parameters.commonAddress + config.count * 11u;
    parameters.prefixAddress = parameters.opticalAddress + config.count * 2u;
    parameters.diagnosticAddress = config.scratchAddress; // config.z disables this consumer.
    TraceScene resources = {acceleration, textures, texels, instanceMaterials, staticTrianglePages};
    TestEnvironment environment;
    TestGuides guides;
    primeTracePrimary(resources, environment, parameters, uint2(index, 0), guides);
    uint flags = primeLandingFlags(parameters.commonAddress, config.count, index);
    uint base = index * 8;
    outputs[base + 3].w = asfloat(flags);
    if ((flags & PRIME_LANDING_ACTIVE) != 0) {
        PrimeLanding landing = primeLoadLanding(parameters.commonAddress, parameters.opticalAddress,
                                                config.count, index);
        outputs[base + 3].xyz = landing.throughput;
        outputs[base + 7] = float4(landing.surface.position, landing.material.roughness);
    }
    outputs[base + 4] = parameters.prefixAddress[index];
    ZSobol sampler = makeZSobolS8(uint2(index, 0), input.y & 0xffu, parameters.config.x,
                                  input.x ^ (input.y >> 8));
    outputs[base + 5].xy =
            float2(sampler.sample1D(PRIME_PRIMARY_BRANCH_DOMAIN), sampler.sample1D(8u));
}
