// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

#language slang 2026
module "state/realtime/transparent_record.slang";

import "prime_abi_types.slang";
import "state/path.slang";
import "state/realtime/contract.slang";
import "state/realtime/ordinary.slang";
import "state/realtime/transport.slang";
import "state/realtime/initial_record.slang";
import "math/queued_psr.slang";
import "contract/realtime/transparent.slang";
import "model/realtime/transport.slang";
import "contract/medium.slang";

public PrimeWavefrontPathRecord primeMakeTransparentWavefrontRecord(
        PathState path,
        PrimeMedium medium,
        PrimeTransparentBranchResult branchResult,
        PrimeQueuedPsrState psrState,
        bool transmissionBranch,
        bool guideEnabled,
        bool hasGuide,
        bool diffusePath,
        uint guideBounce,
        bool primaryReflectionOnly,
        bool guideFallback,
        bool enabled) {
    PrimeDenoiserState denoiserState;
    denoiserState.hasPrimarySurface = true;
    denoiserState.reachedSolidAngle = hasGuide;
    denoiserState.diffuseAlbedoProduct = branchResult.guides.primaryAlbedo;
    denoiserState.specularAlbedoProduct =
            branchResult.guides.primarySpecularAlbedo;
    denoiserState.diffusePath = diffusePath;
    denoiserState.primaryBounce = guideBounce;
    PrimeWavefrontPathRecord record = primeMakeWavefrontRecord(
            path,
            medium,
            denoiserState,
            branchResult.guides,
            enabled);
    uint control = asuint(
            record.transport.rayDirectionAndDenoiserControl.w)
            | PRIME_WAVEFRONT_TRANSPARENT_BRANCH;
    if (transmissionBranch) {
        control |= PRIME_WAVEFRONT_TRANSMISSION_BRANCH;
    }
    if (guideEnabled) {
        control |= PRIME_WAVEFRONT_GUIDE_ENABLED;
    }
    if (branchResult.directionalGuide) {
        control |= PRIME_WAVEFRONT_DIRECTIONAL_GUIDE;
    }
    if (primaryReflectionOnly) {
        control |= PRIME_WAVEFRONT_PRIMARY_REFLECTION_ONLY;
    }
    record.transport.rayDirectionAndDenoiserControl.w = asfloat(control);
    record = primeWavefrontWithQueuedPsrState(record, psrState);
    record.transport = primeWavefrontWithGuideFallback(
            record.transport, guideFallback);
    return record;
}

public PrimeWavefrontTransportRecord primeMakeUpdatedTransparentWavefrontRecord(
        PathState path,
        PrimeMedium medium,
        PrimeTransparentBranchResult branchResult,
        PrimeQueuedPsrState psrState,
        bool hasGuide,
        bool diffusePath,
        uint guideBounce,
        bool directionalGuide,
        uint previousDenoiserControl,
        bool guideFallback,
        bool enabled) {
    PrimeDenoiserState denoiserState;
    denoiserState.hasPrimarySurface = true;
    denoiserState.reachedSolidAngle = hasGuide;
    denoiserState.diffuseAlbedoProduct = branchResult.guides.primaryAlbedo;
    denoiserState.specularAlbedoProduct =
            branchResult.guides.primarySpecularAlbedo;
    denoiserState.diffusePath = diffusePath;
    denoiserState.primaryBounce = guideBounce;
    PrimeWavefrontTransportRecord record = primeMakeUpdatedWavefrontRecord(
            path,
            medium,
            denoiserState,
            previousDenoiserControl,
            primeQueuedPsrStorageFlags(psrState),
            enabled);
    uint control = asuint(record.rayDirectionAndDenoiserControl.w);
    control = directionalGuide
            ? control | PRIME_WAVEFRONT_DIRECTIONAL_GUIDE
            : control & ~PRIME_WAVEFRONT_DIRECTIONAL_GUIDE;
    control &= ~(PRIME_WAVEFRONT_PRIMARY_FLAGS_MASK
            << PRIME_WAVEFRONT_PRIMARY_FLAGS_SHIFT);
    control |= (branchResult.guides.primaryMaterialFlags
            & PRIME_WAVEFRONT_PRIMARY_FLAGS_MASK)
            << PRIME_WAVEFRONT_PRIMARY_FLAGS_SHIFT;
    record.rayDirectionAndDenoiserControl.w = asfloat(control);
    record = primeWavefrontWithGuideFallback(record, guideFallback);
    return record;
}
