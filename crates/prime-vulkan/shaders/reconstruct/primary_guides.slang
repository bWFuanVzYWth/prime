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
import "reconstruct/visible_guides.slang";

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
    public RWTexture2D<float> visibleDepth;
    public RWTexture2D<float2> visibleMotion;
    public bool frameGeneration;

    public bool enabled() {
        return true;
    }
    public float2 sampleOffset(ZSobol sampler) {
        return 0.5 + previous.jitter.xy;
    }
    public void initialize(uint2 pixel, uint2 inputExtent) {
        distance[pixel] = 0.0;
        reflectionMotion[pixel] = float2(0.0);
        incomplete[pixel] = float(PRIME_RR_PRIMARY_PENDING) / 255.0;
        if (frameGeneration) {
            float2 sampleUv = (float2(pixel) + 0.5 + previous.jitter.xy) / float2(inputExtent);
            float2 pixels;
            primeRrMotionPixels(previous, sampleUv, primeRrCurrentDirection(previous, sampleUv),
                                true, true, inputExtent, pixels);
            visibleDepth[pixel] = 1.0;
            visibleMotion[pixel] = pixels;
        }
    }

    public void foreground(PrimaryParameters camera, uint2 pixel, float2 sampleUv, float3 position,
                           float3 previousPosition, bool previousPositionKnown) {
        incomplete[pixel] =
                float(primeRrGuideStatus(incomplete[pixel]) | PRIME_RR_FOREGROUND) / 255.0;
        if (frameGeneration) {
            float2 pixels;
            primeRrMotionPixels(previous, sampleUv, previousPosition, false, previousPositionKnown,
                                camera.sampling.xy, pixels);
            visibleDepth[pixel] = primeVisibleDeviceDepth(
                    dot(position - camera.position.xyz, camera.forward.xyz));
            // Unknown correspondence stays finite zero, never an SDK sentinel or PSR endpoint.
            visibleMotion[pixel] = pixels;
        }
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
        else
            status &= ~PRIME_RR_SURFACE_CANDIDATE;
        incomplete[pixel] = float(status) / 255.0;
    }

    public void candidate(PrimaryParameters camera, uint2 pixel, float2 sampleUv,
                          SurfacePoint surface, PrimePbrVertex material, float3 direction,
                          float4 incident, float4 transmitted, float3 previousPosition,
                          bool previousPositionKnown, PrimePrimaryPsr psr) {
        uint status = primeRrGuideStatus(incomplete[pixel]);
        bool reflection = (psr.flags & PRIME_PRIMARY_GUIDE_REFLECTION) != 0;
        // Keep the earliest usable interface: it has the fewest optical approximations.
        // A moving/unknown first interface makes the later optical chain unknown as well.
        if (reflection ? (status & PRIME_RR_EXPLICIT_REFLECTION) != 0
                       : (status & PRIME_RR_SURFACE_CANDIDATE) != 0)
            return;
        this.surface(camera, pixel, sampleUv, surface, material, direction, incident, transmitted,
                     previousPosition, previousPositionKnown, psr);
        status = primeRrGuideStatus(incomplete[pixel]);
        if (!reflection && (status & PRIME_RR_PRIMARY_PENDING) == 0)
            incomplete[pixel] =
                    float(status | PRIME_RR_SURFACE_CANDIDATE | PRIME_RR_PRIMARY_PENDING) / 255.0;
    }

    public void interfaceSpecular(uint2 pixel, PrimePbrVertex material, float3 view,
                                  float4 incident, float4 transmitted) {
        // The first interface candidate already computed these identical directional energies.
        if ((primeRrGuideStatus(incomplete[pixel]) & PRIME_RR_SURFACE_CANDIDATE) != 0)
            return;
        PrimeFullTransmissionGgxEnergy energy = {energyTexture};
        PrimeDenoiseAlbedos albedos =
                primePbrGuideAlbedos(energy, material, view, incident, transmitted);
        specular[pixel] = float4(saturate(workingToLinear709(albedos.specular)), 1.0);
    }

    public void surface(PrimaryParameters camera, uint2 pixel, float2 sampleUv,
                        SurfacePoint surface, PrimePbrVertex material, float3 direction,
                        float4 incident, float4 transmitted, float3 previousPosition,
                        bool previousPositionKnown, PrimePrimaryPsr psr) {
        float3 position, shadingNormal;
        float3 cameraDirection = primeRrCurrentDirection(previous, sampleUv);
        bool positionKnown = true;
        if (!primePrimaryPsrSurface(psr, camera.position.xyz, cameraDirection, surface.position,
                                    surface.normal, material.normal, position, shadingNormal)) {
            if ((primeRrGuideStatus(incomplete[pixel]) & PRIME_RR_SURFACE_CANDIDATE) != 0) {
                unresolved(pixel, psr.flags);
                return;
            }
            // No usable plane: retain the observed surface's range and material on the camera
            // ray. This is an explicit local proxy, never a fabricated terminal observation.
            position = camera.position.xyz +
                       cameraDirection * length(surface.position - camera.position.xyz);
            shadingNormal = material.normal;
            positionKnown = false;
            incomplete[pixel] =
                    float(primeRrGuideStatus(incomplete[pixel]) | PRIME_RR_SURFACE_PROXY) / 255.0;
        }
        float linearDepth = dot(position - camera.position.xyz, camera.forward.xyz);
        if (!(linearDepth > 0.0) || isinf(linearDepth) || isnan(linearDepth)) {
            unresolved(pixel, psr.flags);
            return;
        }
        bool reflection = (psr.flags & PRIME_PRIMARY_GUIDE_REFLECTION) != 0;
        uint quality = primeRrGuideStatus(incomplete[pixel]);
        if (!reflection && positionKnown) {
            quality &= ~PRIME_RR_SURFACE_PROXY;
            if ((quality & PRIME_RR_EXPLICIT_REFLECTION) == 0)
                quality &= ~PRIME_RR_MOTION_PROXY;
            incomplete[pixel] = float(quality) / 255.0;
        }
        float2 pixels;
        float3 oldPosition =
                position + primePrimaryPsrDirection(psr, previousPosition - surface.position);
        if (!primeRrMotionPixels(previous, sampleUv, oldPosition, false,
                                 positionKnown &&
                                         primePrimaryPsrMotionKnown(psr, previousPositionKnown),
                                 camera.sampling.xy, pixels)) {
            // Preserve the actual depth/normal/albedos even when object or optical motion is
            // missing. Camera reprojection is the most useful available stable correspondence.
            primeRrMotionPixels(previous, sampleUv, position, false, true, camera.sampling.xy,
                                pixels);
            incomplete[pixel] =
                    float(primeRrGuideStatus(incomplete[pixel]) | PRIME_RR_MOTION_PROXY) / 255.0;
        }
        if (reflection) {
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
            primeRrMotionPixels(previous, sampleUv, virtualDirection, true, true,
                                camera.sampling.xy, pixels);
            incomplete[pixel] =
                    float(primeRrGuideStatus(incomplete[pixel]) | PRIME_RR_MOTION_PROXY) / 255.0;
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
            if ((status & PRIME_RR_EXPLICIT_REFLECTION) == 0)
                reflectionMotion[pixel] = motion[pixel];
            status = (status & ~PRIME_RR_REFLECTION_PENDING) | PRIME_RR_EXPLICIT_REFLECTION |
                     PRIME_RR_MOTION_PROXY | PRIME_RR_BUDGET_PROXY;
            incomplete[pixel] = float(status) / 255.0;
            return;
        }
        if ((status & PRIME_RR_SURFACE_CANDIDATE) != 0) {
            incomplete[pixel] =
                    float((status & ~(PRIME_RR_PRIMARY_PENDING | PRIME_RR_SURFACE_CANDIDATE)) |
                          PRIME_RR_SURFACE_PROXY | PRIME_RR_BUDGET_PROXY) /
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
        incomplete[pixel] = float(status | PRIME_RR_PRIMARY_PENDING | PRIME_RR_SURFACE_PROXY |
                                  PRIME_RR_MOTION_PROXY) /
                            255.0;
    }
}
