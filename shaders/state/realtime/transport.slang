#language slang 2026
module "state/realtime/transport.slang";

import "state/realtime/contract.slang";
import "prime_realtime_abi.slang";
import "prime_abi_types.slang";
import "state/path.slang";
import "contract/realtime/guide.slang";
import "math/queued_psr.slang";
import "math/transport.slang";
import "state/realtime/medium.slang";
import "state/realtime/path_storage.slang";
import "model/realtime/transport.slang";
import "bsdf/compact/contract/volume.slang";
import "contract/path.slang";

public uint primePackWavefrontPathControl(PathState path) {
    // Bits 8..23 remain zero and reserved.
    return (min(primePathBounce(path.control), PRIME_WAVEFRONT_BYTE_MASK)
                    << PRIME_WAVEFRONT_BOUNCE_SHIFT)
            | ((primePathFlags(path.control)
                    & PRIME_WAVEFRONT_PATH_FLAGS_MASK)
                    << PRIME_WAVEFRONT_PATH_FLAGS_SHIFT);
}

public bool primeWavefrontActive(PrimeWavefrontTransportRecord record) {
    uint denoiserControl =
            asuint(record.rayDirectionAndDenoiserControl.w);
    return (denoiserControl & PRIME_WAVEFRONT_ACTIVE_MASK) != 0u;
}

public PrimeWavefrontTransportRecord primeWavefrontWithActive(
        PrimeWavefrontTransportRecord record, bool enabled) {
    uint denoiserControl =
            asuint(record.rayDirectionAndDenoiserControl.w);
    if (enabled) {
        denoiserControl |= PRIME_WAVEFRONT_ACTIVE_MASK;
    } else {
        denoiserControl &= ~PRIME_WAVEFRONT_ACTIVE_MASK;
    }
    record.rayDirectionAndDenoiserControl.w = asfloat(denoiserControl);
    return record;
}

public PrimeWavefrontTransportRecord primeWavefrontWithQueuedPsrControl(
        PrimeWavefrontTransportRecord record,
        uint control) {
    record.sourceAndPathControl.z =
            (record.sourceAndPathControl.z & ~PRIME_WAVEFRONT_PSR_CONTROL_MASK)
            | (control & PRIME_WAVEFRONT_PSR_CONTROL_MASK);
    return record;
}

public PrimeWavefrontTransportRecord primeWavefrontWithGuideFallback(
        PrimeWavefrontTransportRecord record,
        bool fallback) {
    if (fallback) {
        record.sourceAndPathControl.z |= PRIME_WAVEFRONT_GUIDE_FALLBACK;
    } else {
        record.sourceAndPathControl.z &= ~PRIME_WAVEFRONT_GUIDE_FALLBACK;
    }
    return record;
}

static const uint PRIME_WAVEFRONT_GUIDE_TRANSITIONS_SHIFT = 8u;
static const uint PRIME_WAVEFRONT_GUIDE_TRANSITIONS_MASK = 0x7fu;

public uint primeWavefrontGuideTransitions(uint guideControl) {
    return (guideControl >> PRIME_WAVEFRONT_GUIDE_TRANSITIONS_SHIFT)
            & PRIME_WAVEFRONT_GUIDE_TRANSITIONS_MASK;
}

public PrimeWavefrontTransportRecord primeWavefrontWithGuideTransitions(
        PrimeWavefrontTransportRecord record,
        uint transitions) {
    uint mask = PRIME_WAVEFRONT_GUIDE_TRANSITIONS_MASK
            << PRIME_WAVEFRONT_GUIDE_TRANSITIONS_SHIFT;
    record.sourceAndPathControl.z =
            (record.sourceAndPathControl.z & ~mask)
            | ((min(transitions, PRIME_WAVEFRONT_GUIDE_TRANSITIONS_MASK)
                    & PRIME_WAVEFRONT_GUIDE_TRANSITIONS_MASK)
                    << PRIME_WAVEFRONT_GUIDE_TRANSITIONS_SHIFT);
    return record;
}

public uint4 primePackWavefrontPsrState(PrimeQueuedPsrState state) {
    return asuint(state.rotation);
}

public PrimeWavefrontPathRecord primeWavefrontWithQueuedPsrState(
        PrimeWavefrontPathRecord record,
        PrimeQueuedPsrState state) {
    record.transport = primeWavefrontWithQueuedPsrControl(
            record.transport, primeQueuedPsrStorageFlags(state));
    record.psrPacked = primePackWavefrontPsrState(state);
    record.transport.throughputAndReserved.w = state.firstDirectionLength.w;
    return record;
}

public PrimeWavefrontPathRecord primeWavefrontWithPrimaryPreviousPosition(
        PrimeWavefrontPathRecord record, PrimeDenoiserGuides guides) {
    record.psrPacked.xyz = asuint(guides.primaryHasMotion
            ? guides.primaryPreviousPosition
            : guides.primaryPosition);
    record.psrPacked.w = (record.psrPacked.w & ~PRIME_WAVEFRONT_PRIMARY_MOTION)
            | (guides.primaryHasMotion ? PRIME_WAVEFRONT_PRIMARY_MOTION : 0u);
    return record;
}

public PrimeWavefrontTransportRecord primeMakeWavefrontTransportRecord(
        PathState path,
        PrimeOpenPbrVolumeStack volumeStack,
        PrimeDenoiserState denoiserState,
        uint primaryMaterialFlags,
        bool enabled) {
    PrimeWavefrontTransportRecord record;
    record.physicalOriginAndPreviousBsdfPdf =
            float4(path.physicalOrigin, path.previousBsdfPdf);
    record.sourceAndPathControl = uint4(
            path.sourcePrimitive,
            primePackWavefrontMediumIors(volumeStack),
            primePackWavefrontPathControl(path));
    primePackWavefrontMediumState(
            volumeStack, path.etaScale, record.medium0, record.medium1);
    uint denoiserControl = enabled ? PRIME_WAVEFRONT_ACTIVE_MASK : 0u;
    if (denoiserState.reachedSolidAngle) {
        denoiserControl |= PRIME_WAVEFRONT_REACHED_SOLID_ANGLE;
    }
    if (denoiserState.diffusePath) {
        denoiserControl |= PRIME_WAVEFRONT_DIFFUSE_PATH;
    }
    denoiserControl = denoiserControl
            | (min(denoiserState.primaryBounce, PRIME_WAVEFRONT_BYTE_MASK)
                    << PRIME_WAVEFRONT_PRIMARY_BOUNCE_SHIFT)
            | (min(volumeStack.count, 2u)
                    << PRIME_WAVEFRONT_MEDIUM_COUNT_SHIFT)
            | ((primaryMaterialFlags & PRIME_WAVEFRONT_PRIMARY_FLAGS_MASK)
                    << PRIME_WAVEFRONT_PRIMARY_FLAGS_SHIFT);
    record.rayDirectionAndDenoiserControl =
            float4(path.rayDirection, asfloat(denoiserControl));
    record.throughputAndReserved = float4(path.throughput, 0.0);
    return record;
}

public PrimeWavefrontPathRecord primeMakeWavefrontRecord(
        PathState path,
        PrimeOpenPbrVolumeStack volumeStack,
        PrimeDenoiserState denoiserState,
        PrimeDenoiserGuides guides,
        bool enabled) {
    PrimeWavefrontPathRecord record;
    record.transport = primeMakeWavefrontTransportRecord(
            path,
            volumeStack,
            denoiserState,
            guides.primaryMaterialFlags,
            enabled);
    record = primeWavefrontWithQueuedPsrState(
            record, primeEmptyQueuedPsrState());
    record = primeWavefrontWithPrimaryPreviousPosition(record, guides);
    return record;
}
