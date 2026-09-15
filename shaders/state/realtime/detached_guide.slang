// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

#language slang 2026
module "state/realtime/detached_guide.slang";

import "prime_realtime_abi.slang";
import "math/realtime_storage.slang";
import "bsdf/compact/contract/volume.slang";
import "state/realtime/contract.slang";
import "state/realtime/detached_guide_medium.slang";

// T and R guides are drained serially, so one exact 36-byte ray record per pixel is sufficient.
// Extinction is radiance state; deterministic guide traversal retains exact boundary identities and
// IOR sources without carrying the continuous absorption values.
public struct PrimeDetachedGuideRecord {
    public float3 physicalOrigin;
    public uint2 sourcePrimitive;
    public float3 rayDirection;
    public uint mediumState;
};

public static const uint PRIME_DETACHED_GUIDE_RECORD_WORDS = 9u;

uint primeDetachedGuideRecordWord(uint2 pixel) {
    return primeRealtimePhaseWord(primeWavefrontIndex(pixel), primeWavefrontPixelCount());
}

void primeStoreGuideRay(uint word, PrimeDetachedGuideRecord record) {
    primeWavefrontQueue[word + 0u] = asuint(record.physicalOrigin.x);
    primeWavefrontQueue[word + 1u] = asuint(record.physicalOrigin.y);
    primeWavefrontQueue[word + 2u] = asuint(record.physicalOrigin.z);
    primeWavefrontQueue[word + 3u] = record.sourcePrimitive.x;
    primeWavefrontQueue[word + 4u] = asuint(record.rayDirection.x);
    primeWavefrontQueue[word + 5u] = asuint(record.rayDirection.y);
    primeWavefrontQueue[word + 6u] = asuint(record.rayDirection.z);
    primeWavefrontQueue[word + 7u] = record.sourcePrimitive.y;
    primeWavefrontQueue[word + 8u] = record.mediumState;
}

PrimeDetachedGuideRecord primeLoadGuideRay(uint word) {
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
    record.mediumState = primeWavefrontQueue[word + 8u];
    return record;
}

public void primeStoreDetachedGuideRecord(uint2 pixel, PrimeDetachedGuideRecord record) {
    primeStoreGuideRay(primeDetachedGuideRecordWord(pixel), record);
}

public PrimeDetachedGuideRecord primeLoadDetachedGuideRecord(uint2 pixel) {
    return primeLoadGuideRay(primeDetachedGuideRecordWord(pixel));
}

uint primeMissingGuideRecordWord(uint pathIndex) {
    return primeRealtimeSurfaceWord(pathIndex, primeWavefrontPixelCount());
}

// A first-surface branch omitted from transport owns its otherwise unused 100-byte surface slot.
// Keep its 36-byte ray here while split still writes the later phase region for other pixels.
public void primeStoreMissingGuideRecord(uint pathIndex, PrimeDetachedGuideRecord record) {
    primeStoreGuideRay(primeMissingGuideRecordWord(pathIndex), record);
}

public PrimeDetachedGuideRecord primeLoadMissingGuideRecord(uint pathIndex) {
    return primeLoadGuideRay(primeMissingGuideRecordWord(pathIndex));
}

public PrimeOpenPbrVolume primeDetachedGuideMedium(
        PrimeDetachedGuideRecord record) {
    return primeUnpackDetachedGuideMedium(record.mediumState);
}
