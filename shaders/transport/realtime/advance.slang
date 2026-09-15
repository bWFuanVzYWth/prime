// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

#language slang 2026
module "transport/realtime/advance.slang";

import "transport/scatter/phase_contract.slang";
import "prime_abi_types.slang";
import "state/path.slang";
import "math/queued_psr.slang";
import "service/trace/context.slang";
import "model/realtime/transport.slang";
import "transport/scatter/transparent.slang";
import "transport/surface/access.slang";
import "transport/surface/environment.slang";
import "state/realtime/contract.slang";
import "state/realtime/light.slang";
import "state/realtime/ordinary.slang";
import "state/realtime/phase_scratch.slang";
import "state/realtime/guide_normal.slang";
import "state/realtime/psr.slang";
import "state/realtime/guide.slang";
import "state/realtime/transparent.slang";
import "state/realtime/transport.slang";

import "bsdf/compact/contract/volume.slang";
import "contract/realtime/ordinary.slang";
import "contract/realtime/transparent.slang";
import "contract/path.slang";
import "transport/surface/accumulate.slang";
import "service/reconstruct/nrd/common.slang";

public bool primeAdvanceOrdinaryWavefrontMiss<Phase>(
        Phase phase,
        inout PrimeIntegratorContext context,
        uint recordIndex,
        PrimeWavefrontTransportRecord record,
        SurfaceInteraction surface,
        uint control)
    where Phase : IPrimeRealtimeTransportPhase
{
    uint2 pixel = primeWavefrontPixel(recordIndex);
    PathState path = primeWavefrontPath(pixel, record);
    PrimeOpenPbrVolume medium = primeWavefrontMedium(record);

    bool guideWasPending =
            (control & PRIME_WAVEFRONT_REACHED_SOLID_ANGLE) == 0u;
    PrimeOrdinarySample result =
            primeLoadWavefrontActiveIntermediate(pixel, control);
    PrimeDenoiserState denoiserState =
            primeWavefrontDenoiserState(control, result);
    bool continuation = false;
    if (surface.hitKind == PRIME_HIT_NONE) {
        if (primePathBounce(path.control) == phase.ordinaryGuideHitBounce(denoiserState.primaryBounce)) {
            if (denoiserState.diffusePath) result.guides.diffuseHitDistance = PRIME_NRD_FP16_MAX;
            else result.guides.specularHitDistance = PRIME_NRD_FP16_MAX;
        }
        primeAccumulateAfterPrimary(context, result, denoiserState.diffusePath,
                primeEvaluateEnvironmentContribution(context, path));
    }
    PrimeWavefrontTransportRecord updatedRecord =
            primeMakeUpdatedWavefrontRecord(
                    path,
                    medium,
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

public bool primeAdvanceTransparentWavefrontMiss<Phase>(
        Phase phase,
        inout PrimeIntegratorContext context,
        uint recordIndex,
        PrimeWavefrontTransportRecord record,
        SurfaceInteraction surface,
        uint control)
    where Phase : IPrimeRealtimeTransportPhase
{
    uint2 pixel = primeWavefrontPixel(recordIndex);
    PathState path = primeWavefrontPath(pixel, record);
    PrimeOpenPbrVolume medium = primeWavefrontMedium(record);

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
    bool continuation = primeIntegrateTransparentWavefrontMiss(
            phase,
            context,
            path,
            medium,
            branchResult,
            psrState,
            hasGuide,
            diffusePath,
            guideBounce,
            directionalGuide,
            guideFallback,
            surface,
            transmissionBranch,
            guideEnabled);
    PrimeWavefrontTransportRecord updatedRecord =
            primeMakeUpdatedTransparentWavefrontRecord(
                    path,
                    medium,
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

