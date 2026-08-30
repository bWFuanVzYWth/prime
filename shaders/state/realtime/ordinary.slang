#language slang 2026
module "state/realtime/ordinary.slang";

import "prime_abi_types.slang";
import "state/path.slang";
import "contract/realtime/guide.slang";
import "math/queued_psr.slang";
import "policy/runtime/config.slang";
import "math/sampling.slang";
import "math/transport.slang";
import "state/realtime/medium.slang";
import "state/realtime/path_storage.slang";
import "state/realtime/contract.slang";
import "state/realtime/transport.slang";
import "model/realtime/transport.slang";
import "bsdf/compact/contract/volume.slang";
import "contract/realtime/ordinary.slang";
import "contract/realtime/transparent.slang";
import "contract/path.slang";

public PathState primeWavefrontPath(
        uint2 pixel, PrimeWavefrontTransportRecord record) {
    PathState path;
    uint pathControl = record.sourceAndPathControl.w;
    path.physicalOrigin = record.physicalOriginAndPreviousBsdfPdf.xyz;
    path.previousBsdfPdf = record.physicalOriginAndPreviousBsdfPdf.w;
    path.rayDirection = record.rayDirectionAndDenoiserControl.xyz;
    primeUnpackWavefrontPreviousLightNormalAndEtaScale(
            record.previousLightNormalAndEtaScale, path);
    path.throughput = record.throughputAndReserved.xyz;
    uint bounce = (pathControl >> PRIME_WAVEFRONT_BOUNCE_SHIFT)
            & PRIME_WAVEFRONT_BYTE_MASK;
    uint flags = (pathControl >> PRIME_WAVEFRONT_PATH_FLAGS_SHIFT)
            & PRIME_WAVEFRONT_PATH_FLAGS_MASK;
    path.control = primeMakePathStateControl(bounce, flags);
    path.sourcePrimitive = record.sourceAndPathControl.xy;
    uint denoiserControl =
            asuint(record.rayDirectionAndDenoiserControl.w);
    uint samplePathIndex =
            (denoiserControl & PRIME_WAVEFRONT_TRANSMISSION_BRANCH) != 0u
            ? 1u
            : 0u;
    path.sampleSeed = primePathSampleSeed(
            pixel, primeSampleEpoch(), samplePathIndex);
    return path;
}

public PrimeOpenPbrVolumeStack primeWavefrontVolumeStack(
        PrimeWavefrontTransportRecord record) {
    PrimeOpenPbrVolumeStack volumeStack;
    volumeStack.values[0] = primeUnpackWavefrontMedium(
            record.medium0,
            primeUnpackWavefrontMediumIor(record.sourceAndPathControl.z, 0u));
    volumeStack.values[1] = primeUnpackWavefrontMedium(
            record.medium1,
            primeUnpackWavefrontMediumIor(record.sourceAndPathControl.z, 1u));
    uint denoiserControl =
            asuint(record.rayDirectionAndDenoiserControl.w);
    volumeStack.count = min(
            (denoiserControl >> PRIME_WAVEFRONT_MEDIUM_COUNT_SHIFT)
                    & PRIME_WAVEFRONT_MEDIUM_COUNT_MASK,
            2u);
    return volumeStack;
}

public PrimeDenoiserState primeWavefrontDenoiserState(
        uint denoiserControl,
        PrimeOrdinarySample result) {
    PrimeDenoiserState state;
    state.hasPrimarySurface = true;
    state.reachedSolidAngle =
            (denoiserControl & PRIME_WAVEFRONT_REACHED_SOLID_ANGLE) != 0u;
    state.diffuseAlbedoProduct = result.guides.primaryAlbedo;
    state.specularAlbedoProduct = result.guides.primarySpecularAlbedo;
    state.diffusePath =
            (denoiserControl & PRIME_WAVEFRONT_DIFFUSE_PATH) != 0u;
    state.primaryBounce =
            (denoiserControl >> PRIME_WAVEFRONT_PRIMARY_BOUNCE_SHIFT)
            & PRIME_WAVEFRONT_BYTE_MASK;
    return state;
}

public PrimeWavefrontTransportRecord primeMakeUpdatedWavefrontRecord(
        PathState path,
        PrimeOpenPbrVolumeStack volumeStack,
        PrimeDenoiserState denoiserState,
        uint previousDenoiserControl,
        uint psrControl,
        bool enabled) {
    uint persistedControl =
            previousDenoiserControl & PRIME_WAVEFRONT_BRANCH_CONTROL_MASK;
    uint primaryMaterialFlags = (previousDenoiserControl
            >> PRIME_WAVEFRONT_PRIMARY_FLAGS_SHIFT)
            & PRIME_WAVEFRONT_PRIMARY_FLAGS_MASK;
    PrimeWavefrontTransportRecord record = primeMakeWavefrontTransportRecord(
path,
            volumeStack,
            denoiserState,
            primaryMaterialFlags,
            enabled);
    uint denoiserControl =
            asuint(record.rayDirectionAndDenoiserControl.w)
                    | persistedControl;
    record.rayDirectionAndDenoiserControl.w = asfloat(denoiserControl);
    record = primeWavefrontWithQueuedPsrControl(record, psrControl);
    return record;
}

public PrimeWavefrontPathRecord primeMakeTransparentWavefrontRecord(
        PathState path,
        PrimeOpenPbrVolumeStack volumeStack,
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
            volumeStack,
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
        PrimeOpenPbrVolumeStack volumeStack,
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
            volumeStack,
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
