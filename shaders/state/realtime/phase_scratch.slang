// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

#language slang 2026
module "state/realtime/phase_scratch.slang";

import "prime_realtime_abi.slang";
import "math/realtime_storage.slang";
import "state/realtime/contract.slang";

// Light selection and the exact previous receiver normal coexist per path. Their 24-byte records
// replace the earlier 36-byte detached-guide tail only after detached guide work has completed.
public uint primeWavefrontPhaseWord(uint pathIndex) {
    return primeRealtimePhaseWord(pathIndex, primeWavefrontPixelCount());
}

public void primeStoreWavefrontPreviousLightNormal(
        uint pathIndex, float3 normal) {
    uint word = primeWavefrontPhaseWord(pathIndex)
            + PRIME_WAVEFRONT_STAGED_RECEIVER_NORMAL_OFFSET / 4u;
    primeWavefrontQueue[word + 0u] = asuint(normal.x);
    primeWavefrontQueue[word + 1u] = asuint(normal.y);
    primeWavefrontQueue[word + 2u] = asuint(normal.z);
}

public float3 primeLoadWavefrontPreviousLightNormal(uint pathIndex) {
    uint word = primeWavefrontPhaseWord(pathIndex)
            + PRIME_WAVEFRONT_STAGED_RECEIVER_NORMAL_OFFSET / 4u;
    return asfloat(uint3(
            primeWavefrontQueue[word + 0u],
            primeWavefrontQueue[word + 1u],
            primeWavefrontQueue[word + 2u]));
}

public void primeClearWavefrontPreviousLightNormals(uint2 pixel) {
    primeStoreWavefrontPreviousLightNormal(
            primeWavefrontIndex(pixel, 0u), float3(0.0));
    primeStoreWavefrontPreviousLightNormal(
            primeWavefrontIndex(pixel, 1u), float3(0.0));
}
