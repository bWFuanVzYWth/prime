#language slang 2026
module "state/realtime/phase_scratch.slang";

import "prime_realtime_abi.slang";
import "state/realtime/contract.slang";

// Light selection and the exact previous receiver normal coexist per path. Their 24-byte records
// replace the earlier 40-byte detached-guide tail only after detached guide work has completed.
public uint primeWavefrontPhaseWord(uint pathIndex) {
    uint pixelCount = primeWavefrontPixelCount();
    uint guideWords = pixelCount * PRIME_WAVEFRONT_AREA_GUIDE_WORDS;
    uint surfaceWords = pixelCount
            * PRIME_WAVEFRONT_PATH_SLOTS_PER_PIXEL
            * (PRIME_WAVEFRONT_SURFACE_RECORD_SIZE / 4u);
    return guideWords + surfaceWords
            + pathIndex * (PRIME_WAVEFRONT_STAGED_LIGHT_RECORD_SIZE / 4u);
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
