#language slang 2026
module "reconstruct/primary_guides.slang";
import "math/color.slang";
import "math/ray_offset.slang";
import "math/z_sobol.slang";
import "pbr/vertex.slang";
import "pbr/guide_albedo.slang";
import "bsdf/full/energy.slang";
import "service/bsdf/contract/albedo.slang";
import "realtime/primary_parameters.slang";
import "realtime/primary_guide_contract.slang";
import "reconstruct/primary_psr.slang";
import "reconstruct/rr_guides.slang";

public struct PrimaryRrGuides : IPrimaryGuides {
    public RWTexture2D<float> depth;
    public RWTexture2D<float2> motion;
    public RWTexture2D<float4> normal;
    public RWTexture2D<float4> diffuse;
    public RWTexture2D<float4> specular;
    public RWTexture2D<float> distance;
    public RWTexture2D<float> incomplete;
    public Sampler3D<float4> energyTexture;
    public RrCamera previous;

    public bool enabled() {
        return true;
    }
    public float2 sampleOffset(ZSobol sampler) {
        return 0.5 + previous.jitter.xy;
    }
    public void initialize(uint2 pixel) {
        distance[pixel] = 0.0;
    }

    public void interfaceSpecular(uint2 pixel, PrimePbrVertex material, float3 view,
                                  float4 incident, float4 transmitted) {
        PrimeFullTransmissionGgxEnergy energy = {energyTexture};
        PrimeDenoiseAlbedos albedos =
                primePbrGuideAlbedos(energy, material, view, incident, transmitted);
        specular[pixel] = float4(saturate(workingToLinear709(albedos.specular)), 1.0);
    }

    public void surface(PrimaryParameters camera, uint2 pixel, float2 sampleUv,
                        SurfacePoint surface, PrimePbrVertex material, float3 direction,
                        float4 incident, float4 transmitted, bool staticGeometry,
                        PrimePrimaryPsr psr, float3 previousPosition) {
        float3 position, shadingNormal;
        if (!primePrimaryPsrSurface(psr, camera.position.xyz, previousPosition, surface.position,
                                    material.normal, position, shadingNormal)) {
            unresolved(pixel);
            return;
        }
        float linearDepth = dot(position - camera.position.xyz, camera.forward.xyz);
        if (!(linearDepth > 0.0) || isinf(linearDepth) || isnan(linearDepth)) {
            unresolved(pixel);
            return;
        }
        PrimeFullTransmissionGgxEnergy energy = {energyTexture};
        PrimeDenoiseAlbedos albedos =
                primePbrGuideAlbedos(energy, material, -direction, incident, transmitted);
        diffuse[pixel] = float4(saturate(workingToLinear709(albedos.diffuse)), 1.0);
        if ((psr.flags & PRIME_PRIMARY_PSR_INTERFACE_SPECULAR) == 0)
            specular[pixel] = float4(saturate(workingToLinear709(albedos.specular)), 1.0);
        normal[pixel] = float4(normalize(shadingNormal), material.roughness);
        depth[pixel] = linearDepth;
        motion[pixel] = primeRrMotion(previous, sampleUv, position, false,
                                      primePrimaryPsrMotionKnown(psr, staticGeometry));
        incomplete[pixel] = 0.0;
    }

    public void environment(PrimaryParameters camera, uint2 pixel, float2 sampleUv,
                            float3 direction, PrimePrimaryPsr psr) {
        float3 virtualDirection = primePrimaryPsrDirection(psr, direction);
        if (any(isnan(virtualDirection)) || any(isinf(virtualDirection)) ||
            !(dot(virtualDirection, virtualDirection) > 1e-12)) {
            unresolved(pixel);
            return;
        }
        diffuse[pixel] = float4(float3(0.5), 1.0);
        if ((psr.flags & PRIME_PRIMARY_PSR_INTERFACE_SPECULAR) == 0)
            specular[pixel] = float4(float3(0.5), 1.0);
        normal[pixel] = float4(0, 0, 0, 1);
        depth[pixel] = PRIME_RR_SKY_DEPTH;
        motion[pixel] = primeRrMotion(previous, sampleUv, virtualDirection, true,
                                      primePrimaryPsrMotionKnown(psr, true));
        incomplete[pixel] = 0.0;
    }

    public void unresolved(uint2 pixel) {
        // Mandatory SDK inputs receive finite placeholders. Only our completion mask assigns
        // validity: these values are not a surface, a sky observation, or a history reset request.
        diffuse[pixel] = float4(float3(0.5), 1.0);
        specular[pixel] = float4(float3(0.5), 1.0);
        normal[pixel] = float4(0, 0, 1, 1);
        depth[pixel] = 1.0;
        motion[pixel] = float2(PRIME_RR_INVALID_MOTION);
        incomplete[pixel] = 1.0;
    }

    public void specularDistance(uint2 pixel, float value) {
        distance[pixel] = isnan(value) || isinf(value) ? 0.0 : clamp(value, 0.0, 65504.0);
    }
}
