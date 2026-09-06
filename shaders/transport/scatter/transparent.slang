#language slang 2026
module "transport/scatter/transparent.slang";

import "prime_abi_types.slang";
import "state/path.slang";
import "service/bsdf/contract/sample.slang";
import "service/reconstruct/nrd/common.slang";
import "math/packed_geometry.slang";
import "math/queued_psr.slang";
import "service/bsdf/contract/albedo.slang";
import "service/trace/context.slang";
import "transport/guide/queued_psr.slang";
import "transport/scatter/ordinary.slang";
import "transport/scatter/contract.slang";
import "transport/scatter/path_update.slang";
import "transport/scatter/phase_contract.slang";
import "transport/surface/access.slang";
import "transport/surface/accumulate.slang";
import "transport/surface/environment.slang";
import "bsdf/compact/contract/volume.slang";
import "contract/path.slang";
import "contract/realtime/transparent.slang";

// Advances one fixed primary-transparent branch by one queued vertex. Its signal lane remains
// branch-owned while traversal/SER sorts both continuations with every other path.
public bool primeIntegrateTransparentWavefrontMiss<Phase>(
        Phase phase,
        inout PrimeIntegratorContext context,
        inout PathState path,
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
        bool guideEnabled)
    where Phase : IPrimeRealtimeTransportPhase
{
    if (!primeKnownHitKind(surface)) {
        return false;
    }

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
                primeEvaluateEnvironmentContribution(context, path));
        return false;
    }
    return false;
}

public bool primeFinishTransparentWavefrontSurface(
        inout PrimeIntegratorContext context,
        inout PathState path,
        inout PrimeOpenPbrVolumeStack volumeStack,
        inout PrimeTransparentBranchResult result,
        inout PrimeQueuedPsrState psrState,
        inout bool hasGuide,
        inout bool diffusePath,
        inout uint guideBounce,
        SurfaceInteraction surface,
        bool transmissionBranch,
        bool guideEnabled,
        bool primarySurfaceReplacement,
        bool discreteContinuation,
        PrimePathScatter scatter,
        PrimeDenoiseAlbedos surfaceAlbedos) {
    float3 viewDirection = -path.rayDirection;
    if (primarySurfaceReplacement) {
        float surfaceLinearRoughness = primeSurfaceLinearRoughness(surface);
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
            discreteContinuation)) {
        return false;
    }
    path.control = primeIncrementPathBounce(path.control);
    return true;
}
