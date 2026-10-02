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
import "reconstruct/rr_status.slang";

public struct PrimaryRrGuides : IPrimaryGuides {
    public RWTexture2D<float> depth;
    public RWTexture2D<float2> motion;
    public RWTexture2D<float4> normal;
    public RWTexture2D<float4> diffuse;
    public RWTexture2D<float4> specular;
    public RWTexture2D<float> distance;
    public RWTexture2D<float> incomplete;
    public RWTexture2D<float2> reflectionMotion;
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
        reflectionMotion[pixel] = float2(0.0);
        incomplete[pixel] = float(PRIME_RR_PRIMARY_PENDING) / 255.0;
    }

    public void reflectionPending(uint2 pixel) {
        incomplete[pixel] =
                float(primeRrGuideStatus(incomplete[pixel]) | PRIME_RR_REFLECTION_PENDING) / 255.0;
    }

    void complete(uint2 pixel, bool reflection) {
        uint status = primeRrGuideStatus(incomplete[pixel]);
        status &= ~(reflection ? PRIME_RR_REFLECTION_PENDING : PRIME_RR_PRIMARY_PENDING);
        if (reflection)
            status |= PRIME_RR_EXPLICIT_REFLECTION;
        incomplete[pixel] = float(status) / 255.0;
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
                        PrimePrimaryPsr psr) {
        float3 position, shadingNormal;
        float3 cameraDirection = primeRrCurrentDirection(previous, sampleUv);
        if (!primePrimaryPsrSurface(psr, camera.position.xyz, cameraDirection, surface.position,
                                    surface.normal, material.normal, position, shadingNormal)) {
            unresolved(pixel, psr.flags);
            return;
        }
        float linearDepth = dot(position - camera.position.xyz, camera.forward.xyz);
        if (!(linearDepth > 0.0) || isinf(linearDepth) || isnan(linearDepth)) {
            unresolved(pixel, psr.flags);
            return;
        }
        float2 pixels;
        if (!primeRrMotionPixels(previous, sampleUv, position, false,
                                 primePrimaryPsrMotionKnown(psr, staticGeometry),
                                 camera.sampling.xy, pixels)) {
            unresolved(pixel, psr.flags);
            return;
        }
        if ((psr.flags & PRIME_PRIMARY_GUIDE_REFLECTION) != 0) {
            reflectionMotion[pixel] = pixels;
            complete(pixel, true);
            return; // Reflection guide has no material/albedo consumer and never visits the LUT.
        }
        PrimeFullTransmissionGgxEnergy energy = {energyTexture};
        PrimeDenoiseAlbedos albedos =
                primePbrGuideAlbedos(energy, material, -direction, incident, transmitted);
        diffuse[pixel] = float4(saturate(workingToLinear709(albedos.diffuse)), 1.0);
        if ((psr.flags & PRIME_PRIMARY_PSR_INTERFACE_SPECULAR) == 0)
            specular[pixel] = float4(saturate(workingToLinear709(albedos.specular)), 1.0);
        normal[pixel] = float4(normalize(shadingNormal), material.roughness);
        depth[pixel] = linearDepth;
        motion[pixel] = pixels;
        complete(pixel, false);
    }

    public void environment(PrimaryParameters camera, uint2 pixel, float2 sampleUv,
                            float3 direction, PrimePrimaryPsr psr) {
        // A sky has no terminal plane. Use the actual camera-ray directional proxy; this
        // preserves zero static motion through refraction and camera rotation without parallax.
        float3 virtualDirection = primeRrCurrentDirection(previous, sampleUv);
        if (any(isnan(virtualDirection)) || any(isinf(virtualDirection)) ||
            !(dot(virtualDirection, virtualDirection) > 1e-12)) {
            unresolved(pixel, psr.flags);
            return;
        }
        float2 pixels;
        if (!primeRrMotionPixels(previous, sampleUv, virtualDirection, true,
                                 primePrimaryPsrMotionKnown(psr, true), camera.sampling.xy,
                                 pixels)) {
            unresolved(pixel, psr.flags);
            return;
        }
        if ((psr.flags & PRIME_PRIMARY_GUIDE_REFLECTION) != 0) {
            reflectionMotion[pixel] = pixels;
            complete(pixel, true);
            return;
        }
        diffuse[pixel] = float4(float3(0.5), 1.0);
        if ((psr.flags & PRIME_PRIMARY_PSR_INTERFACE_SPECULAR) == 0)
            specular[pixel] = float4(float3(0.5), 1.0);
        normal[pixel] = float4(0, 0, 0, 1);
        depth[pixel] = PRIME_RR_SKY_DEPTH;
        motion[pixel] = pixels;
        complete(pixel, false);
    }

    public void unresolved(uint2 pixel, uint flags) {
        uint status = primeRrGuideStatus(incomplete[pixel]);
        if ((flags & PRIME_PRIMARY_GUIDE_REFLECTION) != 0) {
            reflectionMotion[pixel] = float2(0.0);
            incomplete[pixel] =
                    float(status | PRIME_RR_REFLECTION_PENDING | PRIME_RR_EXPLICIT_REFLECTION) /
                    255.0;
            return;
        }
        // Mandatory SDK inputs receive finite placeholders. Only our completion mask assigns
        // validity: these values are not a surface, a sky observation, or a history reset request.
        diffuse[pixel] = float4(float3(0.5), 1.0);
        specular[pixel] = float4(float3(0.5), 1.0);
        normal[pixel] = float4(0, 0, 1, 1);
        depth[pixel] = 1.0;
        motion[pixel] = float2(0.0);
        incomplete[pixel] = float(status | PRIME_RR_PRIMARY_PENDING) / 255.0;
    }
}
