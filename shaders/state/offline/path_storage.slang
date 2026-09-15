// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

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
static const uint PRIME_OFFLINE_STATE_IOR_SHIFT = 3u;
static const uint PRIME_OFFLINE_STATE_IOR_MASK = 0x1ffu;
static const uint PRIME_OFFLINE_STATE_BOUNCE_SHIFT = 21u;
static const uint PRIME_OFFLINE_STATE_FLAGS_SHIFT = 29u;
static const uint PRIME_OFFLINE_MEDIUM_ID_MASK = 0xffffu;

uint primePackOfflineState(
        PathState path,
        PrimeOpenPbrVolume medium,
        bool enabled) {
    return (enabled ? PRIME_OFFLINE_STATE_ACTIVE : 0u)
            | (medium.iorEncoding
                    & PRIME_OFFLINE_STATE_IOR_MASK)
                    << PRIME_OFFLINE_STATE_IOR_SHIFT
            | min(primePathBounce(path.control), PRIME_OFFLINE_PATH_BYTE_MASK)
                    << PRIME_OFFLINE_STATE_BOUNCE_SHIFT
            | (primePathFlags(path.control) & PRIME_OFFLINE_PATH_FLAGS_MASK)
                    << PRIME_OFFLINE_STATE_FLAGS_SHIFT;
}

uint primeOfflineMediumIorEncoding(uint state) {
    return (state >> PRIME_OFFLINE_STATE_IOR_SHIFT) & PRIME_OFFLINE_STATE_IOR_MASK;
}

public PrimeOfflineTransportRecord primePackOfflineTransport(
        PathState path,
        PrimeOpenPbrVolume medium,
        bool enabled) {
    PrimeOfflineTransportRecord record;
    record.physicalOriginAndPreviousBsdfPdf = asuint(
            float4(path.physicalOrigin, path.previousBsdfPdf));
    record.sourceStateAndMediumId = uint4(
            path.sourcePrimitive,
            primePackOfflineState(path, medium, enabled),
            medium.mediumId);
    record.rayDirectionAndEtaScale =
            asuint(float4(path.rayDirection, path.etaScale));
    record.throughputAndPreviousLightNormalX =
            asuint(float4(path.throughput, path.previousLightNormal.x));
    record.mediumAndPreviousLightNormalY = uint4(
            asuint(medium.extinction),
            asuint(path.previousLightNormal.y));
    record.previousLightNormalZAndReserved = uint4(
            uint3(0u),
            asuint(path.previousLightNormal.z));
    return record;
}

public void primeUnpackOfflineTransportPath(
        PrimeOfflineTransportRecord record,
        out PathState path) {
    uint state = record.sourceStateAndMediumId.z;
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
    path.sourcePrimitive = record.sourceStateAndMediumId.xy;
    path.sampleSeed = 0u;
    path.previousLightNormal = asfloat(uint3(
            record.throughputAndPreviousLightNormalX.w,
            record.mediumAndPreviousLightNormalY.w,
            record.previousLightNormalZAndReserved.w));
}

public PrimeOpenPbrVolume primeUnpackOfflineTransportMedium(PrimeOfflineTransportRecord record) {
    return primeUnpackWavefrontMedium(
            uint4(record.mediumAndPreviousLightNormalY.xyz, record.sourceStateAndMediumId.w),
            primeOfflineMediumIorEncoding(record.sourceStateAndMediumId.z));
}

public bool primeOfflineTransportIsActive(PrimeOfflineTransportRecord record) {
    return (record.sourceStateAndMediumId.z & PRIME_OFFLINE_STATE_ACTIVE) != 0u;
}

public float3 primeOfflinePhysicalOrigin(PrimeOfflineTransportRecord record) {
    return asfloat(record.physicalOriginAndPreviousBsdfPdf.xyz);
}

public float3 primeOfflineRayDirection(PrimeOfflineTransportRecord record) {
    return asfloat(record.rayDirectionAndEtaScale.xyz);
}

public uint2 primeOfflineSourcePrimitive(PrimeOfflineTransportRecord record) {
    return record.sourceStateAndMediumId.xy;
}
