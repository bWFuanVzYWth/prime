// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

#language slang 2026
module "transport/realtime/scatter_hit.slang";

import "prime_abi_types.slang";
import "state/path.slang";
import "state/realtime/contract.slang";
import "state/realtime/transport.slang";
import "state/realtime/light.slang";
import "state/realtime/ordinary.slang";
import "state/realtime/guide.slang";
import "state/realtime/guide_normal.slang";
import "state/realtime/transparent.slang";
import "state/realtime/psr.slang";
import "state/realtime/phase_scratch.slang";
import "state/realtime/scatter_signal.slang";
import "model/realtime/transport.slang";
import "policy/runtime/config.slang";
import "policy/realtime/hit_distance.slang";
import "math/sampling.slang";
import "math/packed_geometry.slang";
import "math/queued_psr.slang";
import "service/trace/context.slang";
import "service/bsdf/contract/sample.slang";
import "service/bsdf/contract/albedo.slang";
import "service/bsdf/dispatch/sample.slang";
import "service/bsdf/dispatch/traits.slang";
import "service/reconstruct/nrd/common.slang";
import "bsdf/compact/contract/volume.slang";
import "transport/scatter/contract.slang";
import "transport/scatter/path_update.slang";
import "transport/scatter/ordinary.slang";
import "transport/scatter/surface.slang";
import "transport/scatter/transparent.slang";
import "transport/scatter/phase_contract.slang";
import "transport/surface/access.slang";
import "transport/surface/medium.slang";
import "contract/realtime/ordinary.slang";
import "contract/realtime/transparent.slang";

struct PrimeWavefrontScatter {
    PrimePathScatter scatter;
    PrimeDenoiseAlbedos albedos;
    PrimeClosureTraits traits;
    bool guided;
};

PrimeWavefrontScatter primeSampleWavefrontHit(inout PrimeIntegratorContext context, PathState path,
        PrimeOpenPbrVolumeStack volume, SurfaceInteraction surface, bool needsGuide, bool transparent) {
    PrimeWavefrontScatter result;
    float3 viewDirection = -path.rayDirection;
    result.traits = primeSurfaceClosureTraits(surface, viewDirection, volume, primeUsesAirGap());
    result.guided = needsGuide && (!transparent || !primeClosureIsDiscreteOnly(result.traits));
    PrimePreparedSampleBase prepared = primePreparePathSample(
            path.sampleSeed, primeSampleIndex(), primePathBounce(path.control) + 1u);
    float3 sample = primeSobolSample3D(prepared, PRIME_SAMPLE_EFFECT_SCATTER_BSDF, PRIME_SAMPLE_DIMENSION_PRIMARY);
    if (result.guided) {
        PrimeGuidedPathScatter guided = primeSampleGuidedPathSurface(context, surface, viewDirection,
                sample, volume, primePathBounce(path.control), primeUsesAirGap());
        result.scatter = guided.scatter;
        result.albedos = guided.albedos;
    } else {
        result.scatter = primeSamplePathSurface(context, surface, viewDirection, sample, volume, primeUsesAirGap());
        result.albedos.diffuse = float3(0.0);
        result.albedos.specular = float3(0.0);
    }
    return result;
}

// One BSDF sample site for ordinary / transparent, pending / completed guides. Reconstruction
// state is loaded only after sampling; completed guides need no noisy RGB or PSR reads at all.
public bool primeAdvanceWavefrontHit<Phase>(Phase phase, inout PrimeIntegratorContext context,
        uint recordIndex, PrimeWavefrontTransportRecord record, SurfaceInteraction surface, uint control)
    where Phase : IPrimeRealtimeTransportPhase
{
    uint2 pixel = primeWavefrontPixel(recordIndex);
    bool transparent = primeWavefrontTransparentBranch(control);
    bool hadGuide = (control & PRIME_WAVEFRONT_REACHED_SOLID_ANGLE) != 0u;
    bool guideEnabled = (control & PRIME_WAVEFRONT_GUIDE_ENABLED) != 0u;
    bool guideFallback = transparent && primeWavefrontGuideFallback(record.sourceAndPathControl.z);
    uint guideBounce = (control >> PRIME_WAVEFRONT_PRIMARY_BOUNCE_SHIFT) & PRIME_WAVEFRONT_BYTE_MASK;
    PathState path = primeWavefrontPath(pixel, record);
    PrimeOpenPbrVolumeStack volume = primeWavefrontVolumeStack(record);
    bool segmentAlive = primeApplySegmentMedium(path.throughput, surface, volume);
    uint updates = primeScatterHitDistanceUpdates(phase, transparent, segmentAlive, primePathBounce(path.control), guideBounce);
    if (transparent && !hadGuide) updates &= ~PRIME_SCATTER_GUIDE_HIT;
    if (hadGuide && updates != 0u) primeStoreScatterHitDistances(recordIndex, control, surface.t,
            (updates & PRIME_SCATTER_FIRST_HIT) != 0u, (updates & PRIME_SCATTER_GUIDE_HIT) != 0u);
    bool needsGuide = !hadGuide && (!transparent || (guideEnabled && !guideFallback));
    PrimeWavefrontScatter sampled = {};
    if (segmentAlive) {
        if (transparent && needsGuide && (primeSurfaceHasMotion(surface) || !primeNrdIsFinite(path.throughput))) {
            guideFallback = true;
            needsGuide = false;
        }
        sampled = primeSampleWavefrontHit(context, path, volume, surface, needsGuide, transparent);
    }

    bool continuation = false;
    if (hadGuide) {
        if (segmentAlive) {
            bool hasScatter = primeHasScatter(sampled.scatter.bsdf);
            // Preserve the different invalid-sample volume assignments of the two signal paths.
            if (!transparent || hasScatter) volume = sampled.scatter.volumeStack;
            continuation = hasScatter && primeAdvanceRealtimePath(context, path, surface,
                    sampled.scatter.bsdf, primeClosureIsDiscreteOnly(sampled.traits));
            if (continuation) path.control = primeIncrementPathBounce(path.control);
        }
        PrimeDenoiserState state = primeInitialDenoiserState();
        state.hasPrimarySurface = true;
        state.reachedSolidAngle = true;
        state.diffusePath = (control & PRIME_WAVEFRONT_DIFFUSE_PATH) != 0u;
        state.primaryBounce = guideBounce;
        PrimeWavefrontTransportRecord updated = primeMakeUpdatedWavefrontRecord(path, volume, state, control, 0u, continuation);
        primeStoreWavefrontTransportRecord(recordIndex, primeWavefrontWithGuideFallback(updated, guideFallback));
    } else if (!transparent) {
        PrimeOrdinarySample result = primeLoadWavefrontActiveIntermediate(pixel, control);
        PrimeDenoiserState state = primeWavefrontDenoiserState(control, result);
        if ((updates & PRIME_SCATTER_GUIDE_HIT) != 0u) {
            if (state.diffusePath) result.guides.diffuseHitDistance = primeNrdSanitizeHitDistance(surface.t);
            else result.guides.specularHitDistance = primeNrdSanitizeHitDistance(surface.t);
        }
        if (segmentAlive) continuation = primeFinishWavefrontSurface(context, path, volume, state,
                result.guides, surface, sampled.scatter, sampled.albedos, sampled.traits);
        primeStoreWavefrontTransportRecord(recordIndex,
                primeMakeUpdatedWavefrontRecord(path, volume, state, control, 0u, continuation));
        if (state.reachedSolidAngle) primeStoreWavefrontGuideSurface(recordIndex,
                result.guides.primaryNormal, result.guides.primaryLinearRoughness);
        primeStoreWavefrontActiveIntermediate(pixel, true, result);
    } else {
        bool transmission = primeWavefrontTransmissionBranch(control);
        PrimeTransparentBranchResult result = primeLoadTransparentBranchActiveIntermediate(pixel, transmission, control);
        // Keep this f32 until all guide outputs consume it; do not round-trip through an FP16 image.
        if ((updates & PRIME_SCATTER_FIRST_HIT) != 0u) result.firstHitDistance = primeNrdSanitizeHitDistance(surface.t);
        PrimeQueuedPsrState psr = guideEnabled ? primeLoadWavefrontPsrState(recordIndex, record) : primeEmptyQueuedPsrState();
        bool hasGuide = false;
        bool diffuse = (control & PRIME_WAVEFRONT_DIFFUSE_PATH) != 0u;
        bool directional = primeWavefrontDirectionalGuide(control);
        if (segmentAlive) continuation = primeFinishTransparentWavefrontSurface(context, path, volume,
                result, psr, hasGuide, diffuse, guideBounce, surface, transmission, guideEnabled,
                sampled.guided, primeClosureIsDiscreteOnly(sampled.traits), sampled.scatter, sampled.albedos);
        primeStoreWavefrontTransportRecord(recordIndex, primeMakeUpdatedTransparentWavefrontRecord(path, volume,
                result, psr, hasGuide, diffuse, guideBounce, directional, control, guideFallback, continuation));
        if (continuation && guideEnabled && !hasGuide) primeStoreWavefrontPsrState(recordIndex, psr);
        if (hasGuide) primeStoreWavefrontGuideSurface(recordIndex, result.guides.primaryNormal, result.guides.primaryLinearRoughness);
        primeStoreTransparentBranchActiveIntermediate(pixel, transmission, hasGuide, false,
                (control & PRIME_WAVEFRONT_PRIMARY_REFLECTION_ONLY) != 0u, result);
    }
    primeStoreWavefrontPreviousLightNormal(recordIndex, path.previousLightNormal);
    return continuation;
}
