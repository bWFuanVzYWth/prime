#language slang 2026
module "transport/realtime/delta_walk_state.slang";

import "prime_abi_types.slang";
import "prime_realtime_abi.slang";
import "state/path.slang";
import "service/bsdf/contract/sample.slang";
import "service/bsdf/support/sanitization.slang";
import "service/reconstruct/nrd/common.slang";
import "math/packed_geometry.slang";
import "math/queued_psr.slang";
import "policy/runtime/config.slang";
import "math/sampling.slang";
import "model/material/defaults.slang";
import "service/bsdf/contract/albedo.slang";
import "service/trace/context.slang";
import "transport/realtime/trace.slang";

import "transport/scatter/contract.slang";
import "transport/scatter/delta.slang";
import "transport/scatter/path_update.slang";
import "model/realtime/transport.slang";
import "transport/surface/access.slang";
import "transport/surface/accumulate.slang";
import "transport/surface/medium.slang";
import "state/realtime/contract.slang";
import "state/realtime/detached_guide.slang";
import "state/realtime/guide.slang";
import "state/realtime/light.slang";
import "state/realtime/ordinary.slang";
import "state/realtime/psr.slang";
import "state/realtime/queue.slang";
import "state/realtime/transparent.slang";
import "state/realtime/transport.slang";
import "bsdf/compact/contract/volume.slang";
import "contract/realtime/ordinary.slang";
import "contract/realtime/transparent.slang";
import "contract/path.slang";

public bool primeAdvanceOrdinaryDeltaWalk(
        inout PrimeIntegratorContext context,
        uint recordIndex,
        PrimeWavefrontTransportRecord record,
        SurfaceInteraction surface,
        uint control) {
    uint2 pixel = primeWavefrontPixel(recordIndex);
    PathState path = primeWavefrontPath(pixel, record);
    PrimeOpenPbrVolumeStack volumeStack = primeWavefrontVolumeStack(record);
    PrimeDenoiserState denoiserState =
            primeWavefrontDenoiserState(
                    control,
                    primeLoadWavefrontActiveIntermediate(pixel, control));
    PrimeOrdinarySample result =
            primeLoadWavefrontActiveIntermediate(pixel, control);

    if (denoiserState.hasPrimarySurface
            && primePathBounce(path.control)
                    == denoiserState.primaryBounce + 1u) {
        float hitDistance = primeNrdSanitizeHitDistance(surface.t);
        if (denoiserState.diffusePath) {
            result.guides.diffuseHitDistance = hitDistance;
        } else {
            result.guides.specularHitDistance = hitDistance;
        }
    }
    if (!primeApplySegmentMedium(context, path, surface, volumeStack)) {
        return false;
    }

    float3 viewDirection = -path.rayDirection;
    primeAccumulateAfterPrimary(
            context,
            result,
            denoiserState.diffusePath,
            primeEvaluateDeltaHitEmission(context, path, surface));
    PrimePreparedSampleBase preparedSample =
            primePreparePathSample(
                    path.sampleSeed,
                    primeSampleIndex(),
                    primePathBounce(path.control) + 1u);
    float3 scatterSample = primeSobolSample3D(
            preparedSample,
            PRIME_SAMPLE_EFFECT_SCATTER_BSDF,
            PRIME_SAMPLE_DIMENSION_PRIMARY);
    PrimeGuidedPathScatter guided = primeSampleGuidedDiscretePathSurface(
            context,
            surface,
            viewDirection,
            scatterSample,
            volumeStack,
            primePathBounce(path.control));
    PrimePathScatter scatter = guided.scatter;
    BsdfSample bsdf = scatter.bsdf;
    volumeStack = scatter.volumeStack;
    if (!primeHasScatter(bsdf)) {
        return false;
    }
    if (!denoiserState.reachedSolidAngle) {
        if (!denoiserState.hasPrimarySurface) {
            denoiserState.specularAlbedoProduct += guided.albedos.specular;
            denoiserState.diffuseAlbedoProduct *= guided.albedos.diffuse;
            denoiserState.diffusePath = false;
            denoiserState.hasPrimarySurface = true;
            denoiserState.primaryBounce = primePathBounce(path.control);
        } else {
            denoiserState.specularAlbedoProduct *= guided.albedos.specular;
        }
        result.guides.primarySpecularAlbedo =
                primeSanitizeDenoiseAlbedo(
                        denoiserState.specularAlbedoProduct);
    }

    if (!primeAdvancePathWithoutRoulette(
            context,
            path,
            surface,
            bsdf,
            float3(0.0))) {
        return false;
    }
    path.control = primeIncrementPathBounce(path.control);
    PrimeWavefrontTransportRecord updated = primeMakeUpdatedWavefrontRecord(
            path,
            volumeStack,
            denoiserState,
            control,
            0u,
            true);
    primeStoreWavefrontTransportRecord(recordIndex, updated);
    primeStoreWavefrontActiveIntermediate(pixel, false, result);
    return true;
}

public bool primeAdvanceTransparentDeltaWalk(
        inout PrimeIntegratorContext context,
        uint recordIndex,
        PrimeWavefrontTransportRecord record,
        SurfaceInteraction surface,
        uint control) {
    uint2 pixel = primeWavefrontPixel(recordIndex);
    PathState path = primeWavefrontPath(pixel, record);
    PrimeOpenPbrVolumeStack volumeStack = primeWavefrontVolumeStack(record);
    bool transmissionBranch = primeWavefrontTransmissionBranch(control);
    bool guideEnabled =
            (control & PRIME_WAVEFRONT_GUIDE_ENABLED) != 0u;
    bool hadGuide =
            (control & PRIME_WAVEFRONT_REACHED_SOLID_ANGLE) != 0u;
    bool guideDetached = primeWavefrontGuideDetached(
            record.sourceAndPathControl.z);
    bool observesGuide = guideEnabled
            && !hadGuide
            && !guideDetached;
    bool diffusePath =
            (control & PRIME_WAVEFRONT_DIFFUSE_PATH) != 0u;
    uint guideBounce =
            (control >> PRIME_WAVEFRONT_PRIMARY_BOUNCE_SHIFT)
                    & PRIME_WAVEFRONT_BYTE_MASK;
    bool directionalGuide = primeWavefrontDirectionalGuide(control);
    PrimeTransparentBranchResult result =
            primeLoadTransparentBranchActiveIntermediate(
                    pixel, transmissionBranch, control);
    PrimeQueuedPsrState psrState = observesGuide
            ? primeLoadWavefrontPsrState(recordIndex, record)
            : primeEmptyQueuedPsrState();

    if (primePathBounce(path.control) == 1u) {
        result.firstHitDistance = primeNrdSanitizeHitDistance(surface.t);
    }
    if (hadGuide && primePathBounce(path.control) == guideBounce + 1u) {
        if (diffusePath) {
            result.guides.diffuseHitDistance =
                    primeNrdSanitizeHitDistance(surface.t);
        } else {
            result.guides.specularHitDistance =
                    primeNrdSanitizeHitDistance(surface.t);
        }
    }
    if (!primeApplySegmentMedium(context, path, surface, volumeStack)) {
        return false;
    }

    primeAccumulateTransparentBranch(
            context,
            result,
            guideEnabled ? diffusePath : transmissionBranch,
            primeEvaluateDeltaHitEmission(context, path, surface));
    float3 viewDirection = -path.rayDirection;
    PrimePreparedSampleBase preparedSample =
            primePreparePathSample(
                    path.sampleSeed,
                    primeSampleIndex(),
                    primePathBounce(path.control) + 1u);
    PrimePathScatter scatter = primeSampleDiscretePathSurface(
            context,
            surface,
            viewDirection,
            primeSobolSample3D(
                    preparedSample,
                    PRIME_SAMPLE_EFFECT_SCATTER_BSDF,
                    PRIME_SAMPLE_DIMENSION_PRIMARY),
            volumeStack);
    BsdfSample bsdf = scatter.bsdf;
    PrimePathScatter guideScatter;
    guideScatter.bsdf = primeInvalidBsdfSample();
    guideScatter.volumeStack = volumeStack;
    if (observesGuide) {
        guideScatter = primeSamplePreferredTransmissionDiscretePathSurface(
                context, surface, viewDirection, volumeStack);
        if (primeHasScatter(guideScatter.bsdf)) {
            psrState = primeAppendQueuedPsrDiscrete(
                    psrState,
                    path.physicalOrigin,
                    surface,
                    guideScatter.bsdf);
            bool sameEvent = primeHasScatter(bsdf)
                    && primeDiscreteEventKind(bsdf)
                            == primeDiscreteEventKind(guideScatter.bsdf);
            if (!sameEvent) {
                PrimeDetachedGuideRecord guideRecord;
                guideRecord.physicalOrigin = surface.position;
                guideRecord.sourcePrimitive = uint2(
                        surface.sectionIndex,
                        primeSurfaceTriangleIndex(surface));
                guideRecord.rayDirection = guideScatter.bsdf.direction;
                guideRecord.mediumIor = primeDetachedGuideMediumIor(
                        guideScatter.volumeStack);
                primeStoreDetachedGuideRecord(pixel, guideRecord);
                primeStoreWavefrontPsrState(recordIndex, psrState);
                record.throughputAndReserved.w =
                        psrState.firstDirectionLength.w;
                record = primeWavefrontWithQueuedPsrControl(
                        record, primeQueuedPsrStorageFlags(psrState));
                record = primeWavefrontWithGuideTransitions(
                        record, primeQueuedPsrCount(psrState));
                record = primeWavefrontWithGuideFallback(record, true);
                primeStoreWavefrontTransportRecord(recordIndex, record);
                primeAppendWavefrontPath(
                        PRIME_WAVEFRONT_GUIDE_QUEUE, recordIndex);
                guideDetached = true;
                observesGuide = false;
            }
        } else {
            guideEnabled = false;
            uint disabledControl = control & ~PRIME_WAVEFRONT_GUIDE_ENABLED;
            record.rayDirectionAndDenoiserControl.w = asfloat(disabledControl);
            primeStoreWavefrontTransportRecord(recordIndex, record);
            control = disabledControl;
            observesGuide = false;
        }
    }
    if (!primeHasScatter(bsdf)) {
        return false;
    }
    volumeStack = scatter.volumeStack;
    if (!primeAdvancePathWithoutRoulette(
            context,
            path,
            surface,
            bsdf,
            float3(0.0))) {
        return false;
    }
    path.control = primeIncrementPathBounce(path.control);

    PrimeWavefrontTransportRecord updated =
            primeMakeUpdatedTransparentWavefrontRecord(
                    path,
                    volumeStack,
                    result,
                    psrState,
                    hadGuide,
                    diffusePath,
                    guideBounce,
                    directionalGuide,
                    control,
                    guideDetached,
                    true);
    if (guideDetached) {
        PrimeWavefrontTransportRecord guideOwner =
                primeWavefrontPaths[recordIndex].transport;
        updated.throughputAndReserved.w = guideOwner.throughputAndReserved.w;
        updated.sourceAndPathControl.z = guideOwner.sourceAndPathControl.z;
    }
    primeStoreWavefrontTransportRecord(recordIndex, updated);
    if (observesGuide) {
        primeStoreWavefrontPsrState(recordIndex, psrState);
    }
    primeStoreTransparentBranchActiveIntermediate(
            pixel,
            transmissionBranch,
            false,
            false,
            (control & PRIME_WAVEFRONT_PRIMARY_REFLECTION_ONLY) != 0u,
            result);
    return true;
}
