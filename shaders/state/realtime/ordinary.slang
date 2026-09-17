// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

#language slang 2026
module "state/realtime/ordinary.slang";

import "prime_abi_types.slang";
import "math/hash.slang";
import "state/path.slang";
import "contract/realtime/guide.slang";
import "policy/runtime/config.slang";
import "math/sampling.slang";
import "math/transport.slang";
import "state/realtime/medium.slang";
import "state/realtime/path_storage.slang";
import "state/realtime/contract.slang";
import "state/realtime/transport.slang";
import "model/realtime/transport.slang";
import "contract/medium.slang";
import "contract/realtime/ordinary.slang";
import "contract/path.slang";

public PathState primeWavefrontPath(
        uint2 pixel, PrimeWavefrontTransportRecord record) {
    PathState path;
    uint pathControl = record.sourceAndPathControl.w;
    path.physicalOrigin = record.physicalOriginAndPreviousBsdfPdf.xyz;
    path.previousBsdfPdf = record.physicalOriginAndPreviousBsdfPdf.w;
    path.rayDirection = record.rayDirectionAndDenoiserControl.xyz;
    // Queued scatter has already consumed hit-emission MIS before this state is reconstructed.
    path.previousLightNormal = float3(0.0);
    path.etaScale = asfloat(record.medium.w);
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

public PrimeMedium primeWavefrontMedium(PrimeWavefrontTransportRecord record) {
    return primeUnpackWavefrontMediumState(record.medium,
            primeWavefrontMediumId(record.sourceAndPathControl.w),
            primeUnpackWavefrontMediumIor(record.sourceAndPathControl.z));
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
        PrimeMedium medium,
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
            medium,
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
