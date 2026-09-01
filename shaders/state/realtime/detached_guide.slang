#language slang 2026
module "state/realtime/detached_guide.slang";

import "prime_realtime_abi.slang";
import "bsdf/compact/contract/volume.slang";
import "state/realtime/contract.slang";
import "state/realtime/detached_guide_medium.slang";

// T and R guides are drained serially, so one exact 40-byte ray record per pixel is sufficient.
// Extinction is radiance state; deterministic guide traversal retains exact boundary identities and
// IOR sources without carrying the continuous absorption values.
public struct PrimeDetachedGuideRecord {
    public float3 physicalOrigin;
    public uint2 sourcePrimitive;
    public float3 rayDirection;
    public uint2 mediumState;
};

public static const uint PRIME_DETACHED_GUIDE_RECORD_WORDS = 10u;

uint primeDetachedGuideRecordWord(uint2 pixel) {
    uint pixelCount = primeWavefrontPixelCount();
    // Detached guide and later staged-light records own this phase region in sequence.
    uint precedingWords = pixelCount * PRIME_WAVEFRONT_AREA_GUIDE_WORDS
            + pixelCount * PRIME_WAVEFRONT_PATH_SLOTS_PER_PIXEL
                    * (PRIME_WAVEFRONT_SURFACE_RECORD_SIZE / 4u);
    return precedingWords
            + primeWavefrontIndex(pixel) * PRIME_DETACHED_GUIDE_RECORD_WORDS;
}

public void primeStoreDetachedGuideRecord(
        uint2 pixel, PrimeDetachedGuideRecord record) {
    uint word = primeDetachedGuideRecordWord(pixel);
    primeWavefrontQueue[word + 0u] = asuint(record.physicalOrigin.x);
    primeWavefrontQueue[word + 1u] = asuint(record.physicalOrigin.y);
    primeWavefrontQueue[word + 2u] = asuint(record.physicalOrigin.z);
    primeWavefrontQueue[word + 3u] = record.sourcePrimitive.x;
    primeWavefrontQueue[word + 4u] = asuint(record.rayDirection.x);
    primeWavefrontQueue[word + 5u] = asuint(record.rayDirection.y);
    primeWavefrontQueue[word + 6u] = asuint(record.rayDirection.z);
    primeWavefrontQueue[word + 7u] = record.sourcePrimitive.y;
    primeWavefrontQueue[word + 8u] = record.mediumState.x;
    primeWavefrontQueue[word + 9u] = record.mediumState.y;
}

public PrimeDetachedGuideRecord primeLoadDetachedGuideRecord(uint2 pixel) {
    uint word = primeDetachedGuideRecordWord(pixel);
    PrimeDetachedGuideRecord record;
    record.physicalOrigin = float3(
            asfloat(primeWavefrontQueue[word + 0u]),
            asfloat(primeWavefrontQueue[word + 1u]),
            asfloat(primeWavefrontQueue[word + 2u]));
    record.sourcePrimitive.x = primeWavefrontQueue[word + 3u];
    record.rayDirection = float3(
            asfloat(primeWavefrontQueue[word + 4u]),
            asfloat(primeWavefrontQueue[word + 5u]),
            asfloat(primeWavefrontQueue[word + 6u]));
    record.sourcePrimitive.y = primeWavefrontQueue[word + 7u];
    record.mediumState = uint2(
            primeWavefrontQueue[word + 8u],
            primeWavefrontQueue[word + 9u]);
    return record;
}

public PrimeOpenPbrVolumeStack primeDetachedGuideVolumeStack(
        PrimeDetachedGuideRecord record) {
    return primeUnpackDetachedGuideMediumStack(record.mediumState);
}
