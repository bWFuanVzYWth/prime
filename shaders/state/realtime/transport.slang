// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

#language slang 2026
module "state/realtime/transport.slang";

import "state/realtime/contract.slang";
import "prime_realtime_abi.slang";
import "prime_abi_types.slang";
import "state/path.slang";
import "contract/realtime/guide.slang";
import "math/transport.slang";
import "state/realtime/medium.slang";
import "state/realtime/path_storage.slang";
import "model/realtime/transport.slang";
import "contract/medium.slang";
import "contract/path.slang";

public uint primePackWavefrontPathControl(PathState path) {
    // Bits 8..23 remain zero and reserved.
    return (min(primePathBounce(path.control), PRIME_WAVEFRONT_BYTE_MASK)
                    << PRIME_WAVEFRONT_BOUNCE_SHIFT)
            | ((primePathFlags(path.control)
                    & PRIME_WAVEFRONT_PATH_FLAGS_MASK)
                    << PRIME_WAVEFRONT_PATH_FLAGS_SHIFT);
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

public PrimeWavefrontTransportRecord primeMakeWavefrontTransportRecord(
        PathState path,
        PrimeMedium medium,
        PrimeDenoiserState denoiserState,
        uint primaryMaterialFlags,
        bool enabled) {
    PrimeWavefrontTransportRecord record;
    record.physicalOriginAndPreviousBsdfPdf =
            float4(path.physicalOrigin, path.previousBsdfPdf);
    record.sourceAndPathControl = uint4(
            path.sourcePrimitive,
            primePackWavefrontMediumIor(medium),
            primePackWavefrontPathControl(path) | (medium.mediumId << 8u));
    record.medium = primePackWavefrontMediumState(medium, path.etaScale);
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
            | ((primaryMaterialFlags & PRIME_WAVEFRONT_PRIMARY_FLAGS_MASK)
                    << PRIME_WAVEFRONT_PRIMARY_FLAGS_SHIFT);
    record.rayDirectionAndDenoiserControl =
            float4(path.rayDirection, asfloat(denoiserControl));
    record.throughputAndReserved = float4(path.throughput, 0.0);
    return record;
}
