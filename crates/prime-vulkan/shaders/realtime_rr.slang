#language slang 2026
module realtime_rr;
import "frame.slang";
import "transport.slang";
import "pbr.slang";
import "bsdf/full/energy.slang";
import "ray_query.slang";
import "math/color.slang";
import "math/z_sobol.slang";
import "service/bsdf/contract/albedo.slang";
import "reconstruct/rr_guides.slang";
#include "atmosphere/consumer_bindings.slang"

[[vk::binding(0, 0)]] RaytracingAccelerationStructure scene;
[[vk::binding(2, 0)]] StructuredBuffer<uint4> textures;
[[vk::binding(3, 0)]] StructuredBuffer<uint> texels;
[[vk::binding(7, 0)]] StructuredBuffer<InstanceMaterial> instanceMaterials;
[[vk::binding(8, 0)]] StructuredBuffer<TrianglePage> staticTrianglePages;
[[vk::binding(9, 0)]] Sampler3D<float4> transmissionEnergy;
[[vk::binding(10, 0)]] [[vk::image_format("rgba16f")]] RWTexture2D<float4> noisyColor;
[[vk::binding(11, 0)]] [[vk::image_format("r32f")]] RWTexture2D<float> linearDepth;
[[vk::binding(12, 0)]] [[vk::image_format("rg16f")]] RWTexture2D<float2> motionVectors;
[[vk::binding(13, 0)]] [[vk::image_format("rgba16f")]] RWTexture2D<float4> normalRoughness;
[[vk::binding(14, 0)]] [[vk::image_format("rgba16f")]] RWTexture2D<float4> diffuseAlbedo;
[[vk::binding(15, 0)]] [[vk::image_format("rgba16f")]] RWTexture2D<float4> specularAlbedo;
[[vk::binding(16, 0)]] [[vk::image_format("r16f")]] RWTexture2D<float> specularHitDistance;
[[vk::binding(17, 0)]] ConstantBuffer<RrCamera> rrCamera;
[[vk::push_constant]] ConstantBuffer<Frame> frame;

public struct RrGuides : ITraceGuides {
    public float2 sampleOffset(ZSobol sampler) {
        return 0.5 + rrCamera.jitter.xy;
    }

    public void primary(Frame camera, uint2 pixel, float2 sampleUv, SurfaceHit hit,
                        PrimePbrVertex material, float3 direction, float4 incident,
                        float4 transmitted) {
        PrimeFullTransmissionGgxEnergy energy = {transmissionEnergy};
        PrimeDenoiseAlbedos albedos =
                primePbrGuideAlbedos(energy, material, -direction, incident, transmitted);
        diffuseAlbedo[pixel] = float4(saturate(workingToLinear709(albedos.diffuse)), 1.0);
        specularAlbedo[pixel] = float4(saturate(workingToLinear709(albedos.specular)), 1.0);
        normalRoughness[pixel] = float4(material.normal, material.roughness);
        linearDepth[pixel] = dot(hit.surface.position - camera.position.xyz, camera.forward.xyz);
        // Current dynamic records have no previous transform or vertex correspondence. This
        // includes rigid instances: do not misrepresent their motion with camera-only vectors.
        motionVectors[pixel] =
                primeRrMotion(rrCamera, sampleUv, hit.surface.position, false, hit.staticGeometry);
    }

    public void miss(Frame camera, uint2 pixel, float2 sampleUv, float3 direction) {
        diffuseAlbedo[pixel] = float4(float3(0.5), 1.0);
        specularAlbedo[pixel] = float4(float3(0.5), 1.0);
        normalRoughness[pixel] = float4(0, 0, 0, 1);
        linearDepth[pixel] = PRIME_RR_SKY_DEPTH;
        motionVectors[pixel] = primeRrMotion(rrCamera, sampleUv, direction, true, true);
    }

    public void specularDistance(uint2 pixel, float distance) {
        // Only an actual primary specular reflection continuation reaches this writer.
        // Finite FP16 far distance marks a traced environment miss, as in the old renderer.
        specularHitDistance[pixel] =
                isnan(distance) || isinf(distance) ? 0.0 : clamp(distance, 0.0, 65504.0);
    }
};

[shader("compute")][numthreads(8, 8, 1)] void main(uint3 id : SV_DispatchThreadID) {
    if (any(id.xy >= frame.dimensions.xy))
        return;
    PrimeFullTransmissionGgxEnergy energy = {transmissionEnergy};
    // Zero means no sampled reflection distance (diffuse, transmission, rejected or exhausted
    // continuation), not a fabricated zero-length intersection.
    specularHitDistance[id.xy] = 0.0;
    TraceScene resources = {scene, textures, texels, instanceMaterials, staticTrianglePages};
    TraceResult sample = traceSampleWithGuides(resources, atmosphereEnvironment(), energy, frame,
                                               id.xy, frame.target.y, RrGuides());
    // Streamline consumes scene-linear Rec.709 with no exposure or display transform applied.
    noisyColor[id.xy] = float4(primeRrFiniteHalfColor(workingToLinear709(sample.radiance)), 1.0);
}
