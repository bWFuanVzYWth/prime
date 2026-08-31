#language slang 2026
module "service/light/emission.slang";

import "prime_abi.slang";
import "prime_abi_types.slang";
import "math/packed_geometry.slang";
import "service/material/surface.slang";

public static const uint PRIME_NO_LIGHT_INDEX = 0xffffffffu;
public static const uint PRIME_EMITTER_TWO_SIDED = 1u;

public float2 primeEmitterUv(
        uint3 packedUvs, float2 parentBarycentric) {
    float2 uv0 = primeUnpackUv(packedUvs.x);
    float2 uv1 = primeUnpackUv(packedUvs.y);
    float2 uv2 = primeUnpackUv(packedUvs.z);
    return uv0 * (1.0 - parentBarycentric.x - parentBarycentric.y)
            + uv1 * parentBarycentric.x
            + uv2 * parentBarycentric.y;
}

public float primeEmitterCosine(
        float3 emitterNormal,
        uint emitterFlags,
        float3 directionFromSurfaceToLight) {
    float cosine = dot(emitterNormal, -directionFromSurfaceToLight);
    return (emitterFlags & PRIME_EMITTER_TWO_SIDED) != 0u
            ? abs(cosine)
            : max(cosine, 0.0);
}

// Delta transport has no competing area-light technique. Resolve only the hit emitter and avoid
// importing the light-tree sampler and reverse-PDF reconstruction into that megakernel.
public float3 primeEvaluateHitEmitterRadiance(
        SurfaceInteraction surface,
        float3 rayDirection) {
    uint emitterIndex = primeSurfaceEmitterIndex(surface);
    if (emitterIndex == PRIME_NO_LIGHT_INDEX) {
        return float3(0.0);
    }
    SectionRecord* sections = (SectionRecord*)primePush.sectionTableAddress;
    uint64_t sectionLightAddress = sections[surface.sectionIndex].lightAddress;
    if (sectionLightAddress == uint64_t(0)) {
        return float3(0.0);
    }
    SectionLightHeader* sectionBuffer =
            (SectionLightHeader*)sectionLightAddress;
    if (emitterIndex >= sectionBuffer[0].emitterCount) {
        return float3(0.0);
    }
    LightEmitter* emitters = (LightEmitter*)sectionBuffer[0].emitterAddress;
    float4 emitterCornerArea = emitters[emitterIndex].cornerArea;
    float lightCosine = primeEmitterCosine(
            emitters[emitterIndex].normal,
            emitters[emitterIndex].metadata.z,
            rayDirection);
    if (!(lightCosine > 0.0) || !(emitterCornerArea.w > 0.0)) {
        return float3(0.0);
    }

    float3 sectionTranslation = sections[surface.sectionIndex].translation;
    float3 localPosition = surface.position - sectionTranslation;
    float3 relative = localPosition - emitterCornerArea.xyz;
    float3 firstEdge = emitters[emitterIndex].edgeOneScale.xyz;
    float3 secondEdge = emitters[emitterIndex].edgeTwoPower.xyz;
    float3 edgeCross = cross(firstEdge, secondEdge);
    float denominator = dot(edgeCross, edgeCross);
    float2 parentBarycentric = float2(
            dot(cross(relative, secondEdge), edgeCross) / denominator,
            dot(cross(firstEdge, relative), edgeCross) / denominator);
    return primeEvaluateEmitterRadiance(
            emitters[emitterIndex].metadata.w,
            emitters[emitterIndex].uvsTint.w,
            emitters[emitterIndex].metadata.z,
            emitters[emitterIndex].edgeOneScale.w,
            primeEmitterUv(
                    emitters[emitterIndex].uvsTint.xyz,
                    parentBarycentric),
            0.0);
}
