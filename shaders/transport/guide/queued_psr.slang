#language slang 2026
module "transport/guide/queued_psr.slang";

import "prime_abi.slang";
import "prime_abi_types.slang";
import "service/reconstruct/nrd/common.slang";
import "math/queued_psr.slang";
import "contract/realtime/guide.slang";
import "service/bsdf/contract/albedo.slang";

// Queue-resident PSR state stores the bounded discrete chain as an incremental path length and
// orthogonal direction transform. The last discrete position is exactly path.physicalOrigin at every
// queue boundary, so keeping a second float3 would increase both slot size and live registers.

public float primeQueuedPsrAnchorDistance(
        PrimeQueuedPsrState state,
        float3 target,
        float3 targetNormal) {
    return primeQueuedPsrAnchorDistanceValue(
            state,
            primePush.cameraPosition,
            target,
            targetNormal);
}

public PrimeDenoiserGuides primeQueuedPsrGuide(
        PrimeDenoiserGuides guides,
        SurfaceInteraction surface,
        float3 shadingNormal,
        float linearRoughness,
        PrimeDenoiseAlbedos albedos,
        float3 lastPosition,
        PrimeQueuedPsrState state) {
    float3 position = surface.position - primePush.cameraPosition;
    float3 normal = shadingNormal;
    float3 virtualPosition;
    float3 virtualNormal;
    if (primeBuildQueuedPsrGuideValue(
            state,
            lastPosition,
            surface.position,
            normal,
            virtualPosition,
            virtualNormal)) {
        position = virtualPosition;
        normal = virtualNormal;
    }
    guides.primaryDistance = length(position);
    guides.primaryPosition = position;
    guides.primaryPreviousPosition = position;
    guides.primaryHasMotion = false;
    // Reconstruction material identity must not inherit stochastic Fresnel or path throughput.
    guides.primaryAlbedo = albedos.diffuse;
    guides.primaryNormal = normal;
    guides.primaryHitKind = surface.hitKind;
    guides.primaryMaterialFlags = primeNrdGuideControl(
            surface.materialControl, surface.opticalControl);
    guides.primarySpecularAlbedo = albedos.specular;
    guides.primaryLinearRoughness = linearRoughness;
    return guides;
}
