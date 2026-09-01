#language slang 2026
module "transport/scatter/transparent.slang";

import "prime_abi.slang";
import "prime_abi_types.slang";
import "state/path.slang";
import "service/bsdf/contract/sample.slang";
import "service/bsdf/support/sanitization.slang";
import "service/reconstruct/nrd/common.slang";
import "math/packed_geometry.slang";
import "math/queued_psr.slang";
import "policy/runtime/config.slang";
import "math/sampling.slang";
import "model/material/defaults.slang";
import "model/material/transparent.slang";
import "service/bsdf/contract/albedo.slang";
import "service/bsdf/support/conversion.slang";
import "service/bsdf/dispatch/sample.slang";
import "service/bsdf/dispatch/traits.slang";
import "service/bsdf/dielectric/sample.slang";
import "service/trace/context.slang";
import "transport/direct/contract.slang";
import "transport/guide/queued_psr.slang";
import "transport/scatter/ordinary.slang";
import "transport/scatter/contract.slang";
import "transport/scatter/path_update.slang";
import "transport/scatter/phase_contract.slang";
import "transport/surface/access.slang";
import "transport/surface/accumulate.slang";
import "transport/surface/emission.slang";
import "transport/surface/medium.slang";
import "bsdf/compact/contract/volume.slang";
import "contract/path.slang";
import "contract/material/normal.slang";
import "policy/transport/path.slang";
import "contract/realtime/transparent.slang";

// Advances one fixed primary-transparent branch by one queued vertex. Its signal lane remains
// branch-owned while traversal/SER sorts both continuations with every other path.
public bool primeIntegrateTransparentWavefrontSurface<Phase, SunNee, AreaNee>(
        Phase phase,
        SunNee sunNee,
        AreaNee areaNee,
        inout PrimeIntegratorContext context,
        inout PathState path,
        IntegratorRecord integrator,
        inout PrimeOpenPbrVolumeStack volumeStack,
        inout PrimeTransparentBranchResult result,
        inout PrimeQueuedPsrState psrState,
        inout bool hasGuide,
        inout bool diffusePath,
        inout uint guideBounce,
        inout bool directionalGuide,
        inout bool guideFallback,
        SurfaceInteraction surface,
        bool transmissionBranch,
        bool guideEnabled,
        bool skipRoulette,
        uint pathIndex)
    where Phase : IPrimeRealtimeTransportPhase
    where SunNee : IPrimeSecondarySunNee
    where AreaNee : IPrimeSecondaryAreaNee
{
    if (!primeKnownHitKind(surface)) {
        return false;
    }

    bool observesGuide = guideEnabled
            && !hasGuide
            && !guideFallback;

    float hitDistance = surface.hitKind == PRIME_HIT_NONE
            ? PRIME_NRD_FP16_MAX
            : primeNrdSanitizeHitDistance(surface.t);
    if (primePathBounce(path.control) == phase.transparentFirstHitBounce()) {
        result.firstHitDistance = hitDistance;
    }
    if (hasGuide && primePathBounce(path.control) == guideBounce + 1u) {
        if (diffusePath) {
            result.guides.diffuseHitDistance = hitDistance;
        } else {
            result.guides.specularHitDistance = hitDistance;
        }
    }
    if (surface.hitKind == PRIME_HIT_NONE) {
        if (guideEnabled && !transmissionBranch && !hasGuide
                && !guideFallback
                && primeQueuedPsrCount(psrState) > 0u
                && !primeQueuedPsrOverflowed(psrState)) {
            float3 direction =
                    primeQueuedPsrVirtualDirection(psrState, path.rayDirection);
            float lengthSquared = dot(direction, direction);
            if (primeNrdIsFinite(direction) && primeNrdIsFinite(lengthSquared)
                    && lengthSquared > 1.0e-12) {
                result.guides.primaryPosition =
                        direction * rsqrt(lengthSquared);
                directionalGuide = true;
            }
        }
        primeAccumulateTransparentBranch(
                context,
                result,
                guideEnabled ? diffusePath : transmissionBranch,
                primeEvaluateEnvironmentContribution(context, path, integrator));
        return false;
    }
    if (!primeApplySegmentMedium(path.throughput, surface, volumeStack)) {
        return false;
    }
    if (observesGuide
            && (primeSurfaceHasMotion(surface)
                    || !primeNrdIsFinite(path.throughput))) {
        guideFallback = true;
        observesGuide = false;
    }

    float3 viewDirection = -path.rayDirection;
    PrimePreparedSampleBase preparedSample =
            primePreparePathSample(
                    path.sampleSeed,
                    primeSampleIndex(),
                    primePathBounce(path.control) + 1u);
    bool transmissive = primeMaterialIsTransmissive(surface.materialControl);
    float surfaceLinearRoughness = (transmissive || !hasGuide)
            ? primeSurfaceLinearRoughness(surface)
            : PRIME_DEFAULT_REFERENCE_LINEAR_ROUGHNESS;
    PrimeClosureTraits closureTraits = primeSurfaceClosureTraits(
            surface, viewDirection, volumeStack, primeUsesAirGap());
    bool supportsSolidAngle = primeClosureSupports(
            closureTraits, PRIME_MEASURE_SOLID_ANGLE);
    bool discreteOnlyInterface = primeClosureIsDiscreteOnly(closureTraits);
    bool discreteContinuation = discreteOnlyInterface;
    bool primarySurfaceReplacement =
            observesGuide && !discreteContinuation;
    bool contributionDiffuse = guideEnabled
            ? (primarySurfaceReplacement || diffusePath)
            : transmissionBranch;
    bool previousUsedAreaNee = phase.transparentPreviousUsedAreaNee(
            areaNee.enabled(), primePathBounce(path.control));
    float3 emitted = primeEvaluateHitEmission(
            context, path, surface, previousUsedAreaNee);
    primeAccumulateTransparentBranch(
            context, result, contributionDiffuse, emitted);

    if (supportsSolidAngle && !primarySurfaceReplacement) {
        // Wavefront Step evaluates primary transparent direct lighting after its surface trace,
        // keeping both shadow traces out of this register-heavy BSDF continuation.
        float3 direct = path.throughput * primeEstimateDirectLighting(
                sunNee,
                areaNee,
                context,
                integrator,
                surface,
                viewDirection,
                preparedSample,
                volumeStack,
                path.throughput,
                pathIndex);
        primeAccumulateTransparentBranch(
                context,
                result,
                guideEnabled ? diffusePath : transmissionBranch,
                direct);
    }

    float3 scatterSample = primeSobolSample3D(
            preparedSample,
            PRIME_SAMPLE_EFFECT_SCATTER_BSDF,
            PRIME_SAMPLE_DIMENSION_PRIMARY);
    PrimePathScatter scatter;
    PrimeDenoiseAlbedos surfaceAlbedos;
    surfaceAlbedos.diffuse = float3(0.0);
    surfaceAlbedos.specular = float3(0.0);
    if (primarySurfaceReplacement) {
        PrimeGuidedPathScatter guided = primeSampleGuidedPathSurface(
                context,
                surface,
                viewDirection,
                scatterSample,
                volumeStack,
                primePathBounce(path.control),
                primeUsesAirGap());
        scatter = guided.scatter;
        surfaceAlbedos = guided.albedos;
        result.guides = primeQueuedPsrGuide(
                result.guides,
                surface,
                primeSurfaceShadingNormal(surface, viewDirection),
                surfaceLinearRoughness,
                surfaceAlbedos,
                path.physicalOrigin,
                psrState);
        if (transmissionBranch) {
            result.anchorDistance = primeQueuedPsrAnchorDistance(
                    psrState, surface.position, surface.geometricNormal);
        }
        hasGuide = true;
        guideBounce = primePathBounce(path.control);
    } else {
        scatter = primeSamplePathSurface(
                context,
                surface,
                viewDirection,
                scatterSample,
                volumeStack,
                primeUsesAirGap());
    }
    BsdfSample bsdf = scatter.bsdf;
    if (!primeHasScatter(bsdf)) {
        return false;
    }
    if (primarySurfaceReplacement) {
        diffusePath = (bsdf.eventFlags & PRIME_BSDF_EVENT_DIFFUSE) != 0u;
        float3 virtualDirection =
                primeQueuedPsrVirtualDirection(psrState, bsdf.direction);
        if (diffusePath) {
            result.guides.diffuseDirection = virtualDirection;
        } else {
            result.guides.specularDirection = virtualDirection;
        }
    } else if (guideEnabled && !hasGuide && discreteContinuation
            && (bsdf.eventFlags & PRIME_BSDF_EVENT_DISCRETE) != 0u) {
        psrState = primeAppendQueuedPsrDiscrete(
                psrState, path.physicalOrigin, surface, bsdf);
    }
    volumeStack = scatter.volumeStack;
    if (!primeAdvanceRealtimePath(
            context,
            path,
            surface,
            bsdf,
            preparedSample,
            discreteContinuation,
            skipRoulette || primeTransparentRouletteBlocked(
                    primeTransparentTransportOwnsGuide(
                            false,
                            guideFallback),
                    guideEnabled,
                    hasGuide))) {
        return false;
    }
    path.control = primeIncrementPathBounce(path.control);
    return true;
}
