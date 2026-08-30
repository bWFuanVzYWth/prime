#language slang 2026
module "state/realtime/detached_guide.slang";

import "prime_realtime_abi.slang";
import "state/realtime/contract.slang";
import "bsdf/compact/contract/volume.slang";

// T and R guides are drained serially, so one exact 40-byte ray record per pixel is sufficient.
// Extinction is radiance state; deterministic guide traversal consumes only the two exact f32 IORs.
public struct PrimeDetachedGuideRecord {
    public float3 physicalOrigin;
    public uint2 sourcePrimitive;
    public float3 rayDirection;
    public float2 mediumIor;
};

public static const uint PRIME_DETACHED_GUIDE_RECORD_WORDS = 10u;

uint primeDetachedGuideRecordWord(uint2 pixel) {
    uint pixelCount = primeWavefrontPixelCount();
    uint precedingWords = pixelCount
            * (PRIME_WAVEFRONT_AREA_RECORD_SIZE / 4u
                    - PRIME_DETACHED_GUIDE_RECORD_WORDS);
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
    primeWavefrontQueue[word + 8u] = asuint(record.mediumIor.x);
    primeWavefrontQueue[word + 9u] = asuint(record.mediumIor.y);
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
    record.mediumIor = float2(
            asfloat(primeWavefrontQueue[word + 8u]),
            asfloat(primeWavefrontQueue[word + 9u]));
    return record;
}

public float2 primeDetachedGuideMediumIor(PrimeOpenPbrVolumeStack stack) {
    return float2(
            stack.count > 0u ? stack.values[0].ior : 0.0,
            stack.count > 1u ? stack.values[1].ior : 0.0);
}

public PrimeOpenPbrVolumeStack primeDetachedGuideVolumeStack(
        PrimeDetachedGuideRecord record) {
    PrimeOpenPbrVolumeStack stack;
    [unroll]
    for (uint index = 0u; index < 2u; ++index) {
        stack.values[index].extinction = float3(0.0);
        stack.values[index].albedo = float3(0.0);
        stack.values[index].anisotropy = 0.0;
        stack.values[index].ior = record.mediumIor[index];
    }
    stack.count = record.mediumIor.y > 0.0
            ? 2u
            : (record.mediumIor.x > 0.0 ? 1u : 0u);
    return stack;
}
