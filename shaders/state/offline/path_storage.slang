#language slang 2026
module "state/offline/path_storage.slang";

import "state/path.slang";
import "state/realtime/medium.slang";
import "bsdf/compact/contract/volume.slang";
import "contract/path.slang";
import "prime_abi_types.slang";
import "prime_offline_abi.slang";

// Descriptor-free exact codec for the 96-byte offline transport core.
public static const uint PRIME_OFFLINE_PATH_BYTE_MASK = 0xffu;
public static const uint PRIME_OFFLINE_PATH_FLAGS_MASK = 0x3u;
static const uint PRIME_OFFLINE_STATE_ACTIVE = 1u;
static const uint PRIME_OFFLINE_STATE_MEDIUM_COUNT_SHIFT = 1u;
static const uint PRIME_OFFLINE_STATE_MEDIUM_COUNT_MASK = 0x3u;
static const uint PRIME_OFFLINE_STATE_FIRST_IOR_SHIFT = 3u;
static const uint PRIME_OFFLINE_STATE_SECOND_IOR_SHIFT = 12u;
static const uint PRIME_OFFLINE_STATE_IOR_MASK = 0x1ffu;
static const uint PRIME_OFFLINE_STATE_BOUNCE_SHIFT = 21u;
static const uint PRIME_OFFLINE_STATE_FLAGS_SHIFT = 29u;
static const uint PRIME_OFFLINE_MEDIUM_ID_MASK = 0xffffu;

uint primePackOfflineState(
        PathState path,
        PrimeOpenPbrVolumeStack volumeStack,
        bool enabled) {
    return (enabled ? PRIME_OFFLINE_STATE_ACTIVE : 0u)
            | min(volumeStack.count, 2u)
                    << PRIME_OFFLINE_STATE_MEDIUM_COUNT_SHIFT
            | (volumeStack.values[0].iorEncoding
                    & PRIME_OFFLINE_STATE_IOR_MASK)
                    << PRIME_OFFLINE_STATE_FIRST_IOR_SHIFT
            | (volumeStack.values[1].iorEncoding
                    & PRIME_OFFLINE_STATE_IOR_MASK)
                    << PRIME_OFFLINE_STATE_SECOND_IOR_SHIFT
            | min(primePathBounce(path.control), PRIME_OFFLINE_PATH_BYTE_MASK)
                    << PRIME_OFFLINE_STATE_BOUNCE_SHIFT
            | (primePathFlags(path.control) & PRIME_OFFLINE_PATH_FLAGS_MASK)
                    << PRIME_OFFLINE_STATE_FLAGS_SHIFT;
}

uint primeOfflineMediumIorEncoding(uint state, uint index) {
    return state >> (index == 0u
            ? PRIME_OFFLINE_STATE_FIRST_IOR_SHIFT
            : PRIME_OFFLINE_STATE_SECOND_IOR_SHIFT)
            & PRIME_OFFLINE_STATE_IOR_MASK;
}

public PrimeOfflineTransportRecord primePackOfflineTransport(
        PathState path,
        PrimeOpenPbrVolumeStack volumeStack,
        bool enabled) {
    PrimeOfflineTransportRecord record;
    record.physicalOriginAndPreviousBsdfPdf = asuint(
            float4(path.physicalOrigin, path.previousBsdfPdf));
    record.sourceStateAndMediumIds = uint4(
            path.sourcePrimitive,
            primePackOfflineState(path, volumeStack, enabled),
            (volumeStack.values[0].mediumId & PRIME_OFFLINE_MEDIUM_ID_MASK)
                    | (volumeStack.values[1].mediumId
                            & PRIME_OFFLINE_MEDIUM_ID_MASK) << 16u);
    record.rayDirectionAndEtaScale =
            asuint(float4(path.rayDirection, path.etaScale));
    record.throughputAndPreviousLightNormalX =
            asuint(float4(path.throughput, path.previousLightNormal.x));
    record.medium0AndPreviousLightNormalY = uint4(
            asuint(volumeStack.values[0].extinction),
            asuint(path.previousLightNormal.y));
    record.medium1AndPreviousLightNormalZ = uint4(
            asuint(volumeStack.values[1].extinction),
            asuint(path.previousLightNormal.z));
    return record;
}

public void primeUnpackOfflineTransportPath(
        PrimeOfflineTransportRecord record,
        out PathState path) {
    uint state = record.sourceStateAndMediumIds.z;
    path.physicalOrigin = asfloat(record.physicalOriginAndPreviousBsdfPdf.xyz);
    path.previousBsdfPdf = asfloat(record.physicalOriginAndPreviousBsdfPdf.w);
    path.rayDirection = asfloat(record.rayDirectionAndEtaScale.xyz);
    path.etaScale = asfloat(record.rayDirectionAndEtaScale.w);
    path.throughput = asfloat(record.throughputAndPreviousLightNormalX.xyz);
    uint bounce = (state >> PRIME_OFFLINE_STATE_BOUNCE_SHIFT)
            & PRIME_OFFLINE_PATH_BYTE_MASK;
    uint flags = (state >> PRIME_OFFLINE_STATE_FLAGS_SHIFT)
            & PRIME_OFFLINE_PATH_FLAGS_MASK;
    path.control = primeMakePathStateControl(bounce, flags);
    path.sourcePrimitive = record.sourceStateAndMediumIds.xy;
    path.sampleSeed = 0u;
    path.previousLightNormal = asfloat(uint3(
            record.throughputAndPreviousLightNormalX.w,
            record.medium0AndPreviousLightNormalY.w,
            record.medium1AndPreviousLightNormalZ.w));
}

public PrimeOpenPbrVolumeStack primeUnpackOfflineTransportVolumeStack(
        PrimeOfflineTransportRecord record) {
    PrimeOpenPbrVolumeStack stack;
    uint state = record.sourceStateAndMediumIds.z;
    uint mediumIds = record.sourceStateAndMediumIds.w;
    stack.values[0] = primeUnpackWavefrontMedium(
            uint4(
                    record.medium0AndPreviousLightNormalY.xyz,
                    mediumIds & PRIME_OFFLINE_MEDIUM_ID_MASK),
            primeOfflineMediumIorEncoding(state, 0u));
    stack.values[1] = primeUnpackWavefrontMedium(
            uint4(
                    record.medium1AndPreviousLightNormalZ.xyz,
                    mediumIds >> 16u),
            primeOfflineMediumIorEncoding(state, 1u));
    stack.count = min(
            (state >> PRIME_OFFLINE_STATE_MEDIUM_COUNT_SHIFT)
                    & PRIME_OFFLINE_STATE_MEDIUM_COUNT_MASK,
            2u);
    return stack;
}

public bool primeOfflineTransportIsActive(PrimeOfflineTransportRecord record) {
    return (record.sourceStateAndMediumIds.z & PRIME_OFFLINE_STATE_ACTIVE) != 0u;
}

public float3 primeOfflinePhysicalOrigin(PrimeOfflineTransportRecord record) {
    return asfloat(record.physicalOriginAndPreviousBsdfPdf.xyz);
}

public float3 primeOfflineRayDirection(PrimeOfflineTransportRecord record) {
    return asfloat(record.rayDirectionAndEtaScale.xyz);
}

public uint2 primeOfflineSourcePrimitive(PrimeOfflineTransportRecord record) {
    return record.sourceStateAndMediumIds.xy;
}
