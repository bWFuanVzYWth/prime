#language slang 2026
module "transport/realtime/advance.slang";

import "transport/scatter/phase_contract.slang";
import "policy/realtime/direct.slang";
import "prime_abi_types.slang";
import "state/path.slang";
import "prime_abi.slang";
import "contract/realtime/guide.slang";
import "model/atmosphere/sky.slang";
import "math/queued_psr.slang";
import "math/packed_geometry.slang";
import "policy/runtime/config.slang";
import "service/light/sample.slang";
import "service/light/emission.slang";
import "model/material/defaults.slang";
import "service/trace/context.slang";
import "transport/direct/staged.slang";
import "model/realtime/transport.slang";
import "transport/scatter/surface.slang";
import "transport/scatter/transparent.slang";
import "transport/surface/access.slang";
import "transport/surface/environment.slang";
import "transport/surface/medium.slang";
import "state/realtime/contract.slang";
import "state/realtime/light.slang";
import "state/realtime/ordinary.slang";
import "state/realtime/phase_scratch.slang";
import "state/realtime/guide_normal.slang";
import "state/realtime/psr.slang";
import "state/realtime/guide.slang";
import "state/realtime/transparent.slang";
import "state/realtime/transport.slang";

import "transport/realtime/trace.slang";
import "bsdf/compact/contract/volume.slang";
import "contract/realtime/ordinary.slang";
import "contract/realtime/transparent.slang";
import "contract/path.slang";

public bool primeAdvanceOrdinaryWavefrontRecord<Phase>(
        Phase phase,
        inout PrimeIntegratorContext context,
        uint recordIndex,
        PrimeWavefrontTransportRecord record,
        SurfaceInteraction surface,
        uint control,
        bool skipRoulette)
    where Phase : IPrimeRealtimeTransportPhase
{
    uint2 pixel = primeWavefrontPixel(recordIndex);
    PathState path = primeWavefrontPath(pixel, record);
    PrimeOpenPbrVolumeStack volumeStack = primeWavefrontVolumeStack(record);
    IntegratorRecord integrator;
    integrator.sunDirectionIntensity = float4(
            primePush.sunDirection,
            ATM_SPACE_SUN_INTENSITY * primeSunRadianceMultiplier());
    integrator.environmentRadiance = float4(0.0);

    bool guideWasPending =
            (control & PRIME_WAVEFRONT_REACHED_SOLID_ANGLE) == 0u;
    PrimeOrdinarySample result =
            primeLoadWavefrontActiveIntermediate(pixel, control);
    PrimeDenoiserState denoiserState =
            primeWavefrontDenoiserState(control, result);
    bool segmentAlive = surface.hitKind != PRIME_HIT_SURFACE
            || primeApplySegmentMedium(path.throughput, surface, volumeStack);
    bool continuation = segmentAlive
            && primeIntegrateWavefrontSurface(
                    phase,
                    PrimeStagedSecondarySunNee(),
                    PrimeFixedSecondaryAreaNee(),
                    PrimeNoIndirectReuse(),
                    context,
                    path,
                    integrator,
                    volumeStack,
                    denoiserState,
                    result,
                    surface,
                    skipRoulette,
                    recordIndex);
    PrimeWavefrontTransportRecord updatedRecord =
            primeMakeUpdatedWavefrontRecord(
                    path,
                    volumeStack,
                    denoiserState,
                    control,
                    0u,
                    continuation);
    primeStoreWavefrontTransportRecord(recordIndex, updatedRecord);
    primeStoreWavefrontPreviousLightNormal(
            recordIndex, path.previousLightNormal);
    if (guideWasPending && denoiserState.reachedSolidAngle) {
        primeStoreWavefrontGuideSurface(
                recordIndex,
                result.guides.primaryNormal,
                result.guides.primaryLinearRoughness);
    }
    primeStoreWavefrontActiveIntermediate(
            pixel, guideWasPending, result);
    return continuation;
}

public bool primeAdvanceTransparentWavefrontRecord<Phase>(
        Phase phase,
        inout PrimeIntegratorContext context,
        uint recordIndex,
        PrimeWavefrontTransportRecord record,
        SurfaceInteraction surface,
        uint control,
        bool skipRoulette)
    where Phase : IPrimeRealtimeTransportPhase
{
    uint2 pixel = primeWavefrontPixel(recordIndex);
    PathState path = primeWavefrontPath(pixel, record);
    PrimeOpenPbrVolumeStack volumeStack = primeWavefrontVolumeStack(record);
    IntegratorRecord integrator;
    integrator.sunDirectionIntensity = float4(
            primePush.sunDirection,
            ATM_SPACE_SUN_INTENSITY * primeSunRadianceMultiplier());
    integrator.environmentRadiance = float4(0.0);

    bool hadGuide =
            (control & PRIME_WAVEFRONT_REACHED_SOLID_ANGLE) != 0u;
    bool guideEnabled =
            (control & PRIME_WAVEFRONT_GUIDE_ENABLED) != 0u;
    bool needsPsr = guideEnabled && !hadGuide;
    bool transmissionBranch = primeWavefrontTransmissionBranch(control);
    PrimeTransparentBranchResult branchResult =
            primeLoadTransparentBranchActiveIntermediate(
                    pixel, transmissionBranch, control);
    PrimeQueuedPsrState psrState = needsPsr
            ? primeLoadWavefrontPsrState(recordIndex, record)
            : primeEmptyQueuedPsrState();
    bool hasGuide = hadGuide;
    bool diffusePath =
            (control & PRIME_WAVEFRONT_DIFFUSE_PATH) != 0u;
    uint guideBounce =
            (control >> PRIME_WAVEFRONT_PRIMARY_BOUNCE_SHIFT)
                    & PRIME_WAVEFRONT_BYTE_MASK;
    bool directionalGuide = primeWavefrontDirectionalGuide(control);
    bool hadDirectionalGuide = directionalGuide;
    bool guideFallback = primeWavefrontGuideFallback(
            record.sourceAndPathControl.z);
    bool continuation = primeIntegrateTransparentWavefrontSurface(
            phase,
            PrimeStagedSecondarySunNee(),
            PrimeFixedSecondaryAreaNee(),
            context,
            path,
            integrator,
            volumeStack,
            branchResult,
            psrState,
            hasGuide,
            diffusePath,
            guideBounce,
            directionalGuide,
            guideFallback,
            surface,
            transmissionBranch,
            guideEnabled,
            skipRoulette,
            recordIndex);
    PrimeWavefrontTransportRecord updatedRecord =
            primeMakeUpdatedTransparentWavefrontRecord(
                    path,
                    volumeStack,
                    branchResult,
                    psrState,
                    hasGuide,
                    diffusePath,
                    guideBounce,
                    directionalGuide,
                    control,
                    guideFallback,
                    continuation);
    primeStoreWavefrontTransportRecord(recordIndex, updatedRecord);
    primeStoreWavefrontPreviousLightNormal(
            recordIndex, path.previousLightNormal);
    if (continuation && guideEnabled && !hasGuide) {
        primeStoreWavefrontPsrState(recordIndex, psrState);
    }
    if (!hadGuide && hasGuide) {
        primeStoreWavefrontGuideSurface(
                recordIndex,
                branchResult.guides.primaryNormal,
                branchResult.guides.primaryLinearRoughness);
    }
    primeStoreTransparentBranchActiveIntermediate(
            pixel,
            transmissionBranch,
            !hadGuide && hasGuide,
            !hadDirectionalGuide && directionalGuide,
            (control & PRIME_WAVEFRONT_PRIMARY_REFLECTION_ONLY) != 0u,
            branchResult);
    return continuation;
}

