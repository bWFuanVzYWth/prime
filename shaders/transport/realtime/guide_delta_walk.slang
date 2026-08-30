#language slang 2026
module "transport/realtime/guide_delta_walk.slang";

import "prime_realtime_abi.slang";
import "prime_abi_types.slang";
import "bsdf/compact/contract/volume.slang";
import "contract/realtime/transparent.slang";
import "math/packed_geometry.slang";
import "math/queued_psr.slang";
import "policy/runtime/config.slang";
import "service/bsdf/contract/albedo.slang";
import "service/bsdf/dispatch/guide_albedo.slang";
import "service/reconstruct/nrd/common.slang";
import "service/trace/context.slang";
import "service/trace/surface.slang";
import "state/realtime/contract.slang";
import "state/realtime/detached_guide.slang";
import "state/realtime/guide_normal.slang";
import "state/realtime/light.slang";
import "state/realtime/ordinary.slang";
import "state/realtime/psr.slang";
import "state/realtime/queue.slang";
import "state/realtime/transparent.slang";
import "state/realtime/transport.slang";
import "transport/guide/queued_psr.slang";
import "transport/scatter/delta.slang";
import "transport/scatter/contract.slang";
import "transport/scatter/path_update.slang";
import "transport/surface/access.slang";
import "contract/material/normal.slang";
import "contract/path.slang";

PrimeQueuedPsrState primeLoadDetachedGuidePsrState(
        uint recordIndex,
        PrimeWavefrontTransportRecord owner) {
    PrimeQueuedPsrState state = primeLoadWavefrontPsrState(recordIndex, owner);
    return primeQueuedPsrWithRuntimeControl(
            state,
            primeWavefrontGuideTransitions(owner.sourceAndPathControl.z),
            primeQueuedPsrStorageFlags(state));
}

void primeFinishDetachedGuide(uint recordIndex, bool directional) {
    PrimeWavefrontTransportRecord owner =
            primeLoadWavefrontTransportRecord(recordIndex);
    uint control = asuint(owner.rayDirectionAndDenoiserControl.w);
    control = directional
            ? control | PRIME_WAVEFRONT_DIRECTIONAL_GUIDE
            : control & ~PRIME_WAVEFRONT_DIRECTIONAL_GUIDE;
    if (!directional) {
        control &= ~PRIME_WAVEFRONT_GUIDE_ENABLED;
    }
    owner.rayDirectionAndDenoiserControl.w = asfloat(control);
    owner = primeWavefrontWithQueuedPsrControl(owner, 0u);
    owner = primeWavefrontWithGuideTransitions(owner, 0u);
    owner = primeWavefrontWithGuideFallback(owner, false);
    primeStoreWavefrontTransportRecord(recordIndex, owner);
}

void primePublishDetachedGuideSurface(
        uint recordIndex,
        PrimeDetachedGuideRecord guide,
        PrimeOpenPbrVolumeStack volumeStack,
        SurfaceInteraction surface) {
    uint2 pixel = primeWavefrontPixel(recordIndex);
    PrimeWavefrontTransportRecord owner =
            primeLoadWavefrontTransportRecord(recordIndex);
    uint control = asuint(owner.rayDirectionAndDenoiserControl.w);
    bool transmission = primeWavefrontTransmissionBranch(control);
    PrimeTransparentBranchResult result =
            primeLoadTransparentBranchActiveIntermediate(
                    pixel, transmission, control);
    PrimeQueuedPsrState psrState = primeLoadDetachedGuidePsrState(
            recordIndex, owner);
    float3 viewDirection = -guide.rayDirection;
    PrimeDenoiseAlbedos albedos = primeSurfaceGuideAlbedos(
            surface, viewDirection, volumeStack, primeUsesAirGap());
    result.guides = primeQueuedPsrGuide(
            result.guides,
            surface,
            primeSurfaceShadingNormal(surface, viewDirection),
            primeSurfaceLinearRoughness(surface),
            albedos,
            guide.physicalOrigin,
            psrState);
    if (transmission) {
        result.anchorDistance = primeQueuedPsrAnchorDistance(
                psrState, surface.position, surface.geometricNormal);
    }
    result.directionalGuide = false;
    primeStoreTransparentBranchActiveIntermediate(
            pixel,
            transmission,
            true,
            false,
            (control & PRIME_WAVEFRONT_PRIMARY_REFLECTION_ONLY) != 0u,
            result);

    control |= PRIME_WAVEFRONT_REACHED_SOLID_ANGLE;
    control &= ~PRIME_WAVEFRONT_DIRECTIONAL_GUIDE;
    control &= ~(PRIME_WAVEFRONT_PRIMARY_FLAGS_MASK
            << PRIME_WAVEFRONT_PRIMARY_FLAGS_SHIFT);
    control |= (result.guides.primaryMaterialFlags
            & PRIME_WAVEFRONT_PRIMARY_FLAGS_MASK)
            << PRIME_WAVEFRONT_PRIMARY_FLAGS_SHIFT;
    owner.rayDirectionAndDenoiserControl.w = asfloat(control);
    owner = primeWavefrontWithQueuedPsrControl(owner, 0u);
    owner = primeWavefrontWithGuideTransitions(owner, 0u);
    owner = primeWavefrontWithGuideFallback(owner, false);
    primeStoreWavefrontTransportRecord(recordIndex, owner);
    primeStoreWavefrontGuideNormal(
            recordIndex, result.guides.primaryNormal);
}

void primePublishDetachedGuideMiss(
        uint recordIndex, PrimeDetachedGuideRecord guide) {
    uint2 pixel = primeWavefrontPixel(recordIndex);
    PrimeWavefrontTransportRecord owner =
            primeLoadWavefrontTransportRecord(recordIndex);
    uint control = asuint(owner.rayDirectionAndDenoiserControl.w);
    bool transmission = primeWavefrontTransmissionBranch(control);
    PrimeTransparentBranchResult result =
            primeLoadTransparentBranchActiveIntermediate(
                    pixel, transmission, control);
    PrimeQueuedPsrState psrState = primeLoadDetachedGuidePsrState(
            recordIndex, owner);
    float3 direction = primeQueuedPsrVirtualDirection(
            psrState, guide.rayDirection);
    float lengthSquared = dot(direction, direction);
    if (primeNrdIsFinite(direction) && primeNrdIsFinite(lengthSquared)
            && lengthSquared > 1.0e-12) {
        result.guides.primaryPosition = direction * rsqrt(lengthSquared);
        result.directionalGuide = true;
        primeStoreTransparentBranchActiveIntermediate(
                pixel,
                transmission,
                false,
                true,
                (control & PRIME_WAVEFRONT_PRIMARY_REFLECTION_ONLY) != 0u,
                result);
        primeFinishDetachedGuide(recordIndex, true);
        return;
    }
    primeFinishDetachedGuide(recordIndex, false);
}

public void primeDetachedGuideDeltaWalk(
        inout PrimeIntegratorContext context, uint launchIndex) {
    uint recordIndex = primeWavefrontQueuedPath(
            PRIME_WAVEFRONT_GUIDE_QUEUE, launchIndex);
    uint2 pixel = primeWavefrontPixel(recordIndex);
    PrimeDetachedGuideRecord guide = primeLoadDetachedGuideRecord(pixel);
    uint maximumTransitions = primeAdditionalSpecularBounces();

    [loop]
    for (uint step = 0u; step < maximumTransitions; ++step) {
        PrimeWavefrontTransportRecord owner =
                primeLoadWavefrontTransportRecord(recordIndex);
        uint ownerControl = asuint(owner.rayDirectionAndDenoiserControl.w);
        uint pathClass = primeWavefrontTransmissionBranch(ownerControl) ? 2u : 1u;
        SurfaceInteraction surface = primeTraceSurfaceClassified(
                PrimeSurfaceTracePolicy(),
                guide.physicalOrigin,
                guide.rayDirection,
                guide.sourcePrimitive,
                pathClass);
        if (surface.hitKind == PRIME_HIT_NONE) {
            primePublishDetachedGuideMiss(recordIndex, guide);
            return;
        }

        PrimeOpenPbrVolumeStack volumeStack =
                primeDetachedGuideVolumeStack(guide);
        if (!primeSurfaceIsDiscreteOnly(
                surface, -guide.rayDirection, volumeStack)) {
            primePublishDetachedGuideSurface(
                    recordIndex, guide, volumeStack, surface);
            return;
        }
        PrimeQueuedPsrState psrState = primeLoadDetachedGuidePsrState(
                recordIndex, owner);
        uint transitions = primeWavefrontGuideTransitions(
                owner.sourceAndPathControl.z);
        if (transitions >= maximumTransitions) {
            primeFinishDetachedGuide(recordIndex, false);
            return;
        }

        PrimePathScatter scatter =
                primeSamplePreferredTransmissionDiscretePathSurface(
                        context,
                        surface,
                        -guide.rayDirection,
                        volumeStack);
        if (!primeHasScatter(scatter.bsdf)) {
            primeFinishDetachedGuide(recordIndex, false);
            return;
        }
        psrState = primeAppendQueuedPsrDiscrete(
                psrState,
                guide.physicalOrigin,
                surface,
                scatter.bsdf);
        primeStoreWavefrontPsrState(recordIndex, psrState);
        owner.throughputAndReserved.w = psrState.firstDirectionLength.w;
        owner = primeWavefrontWithQueuedPsrControl(
                owner, primeQueuedPsrStorageFlags(psrState));
        owner = primeWavefrontWithGuideTransitions(
                owner, primeQueuedPsrCount(psrState));
        primeStoreWavefrontTransportRecord(recordIndex, owner);
        guide.physicalOrigin = surface.position;
        guide.sourcePrimitive = uint2(
                surface.sectionIndex, primeSurfaceTriangleIndex(surface));
        guide.rayDirection = scatter.bsdf.direction;
        guide.mediumIor = primeDetachedGuideMediumIor(scatter.volumeStack);
        primeStoreDetachedGuideRecord(pixel, guide);
    }
    primeFinishDetachedGuide(recordIndex, false);
}
