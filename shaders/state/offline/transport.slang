#language slang 2026
module "state/offline/transport.slang";

import "prime_abi_types.slang";
import "state/path.slang";
import "policy/runtime/config.slang";
import "state/realtime/medium.slang";
import "state/offline/contract.slang";
import "bsdf/compact/contract/volume.slang";
import "math/sampling.slang";
import "contract/path.slang";

public uint primePackOfflinePathControl(PathState path) {
    return (min(primePathBounce(path.control), PRIME_OFFLINE_PATH_BYTE_MASK)
                    << PRIME_OFFLINE_PATH_BOUNCE_SHIFT)
            | ((primePathFlags(path.control) & PRIME_OFFLINE_PATH_FLAGS_MASK)
                    << PRIME_OFFLINE_PATH_FLAGS_SHIFT);
}

public PrimeOfflineTransportRecord primeMakeOfflineTransport(
        PathState path,
        PrimeOpenPbrVolumeStack volumeStack,
        bool enabled) {
    PrimeOfflineTransportRecord record;
    record.physicalOriginAndPreviousBsdfPdf =
            float4(path.physicalOrigin, path.previousBsdfPdf);
    record.sourceAndPathControl = uint4(
            path.sourcePrimitive,
            0u,
            primePackOfflinePathControl(path));
    uint control = enabled ? PRIME_OFFLINE_CONTROL_ACTIVE : 0u;
    control |= min(volumeStack.count, 2u) << PRIME_OFFLINE_CONTROL_MEDIUM_SHIFT;
    record.rayDirectionAndControl =
            float4(path.rayDirection, asfloat(control));
    record.throughput = float4(path.throughput, path.etaScale);
    record.medium0 = primePackWavefrontMedium(volumeStack.values[0]);
    record.medium1 = primePackWavefrontMedium(volumeStack.values[1]);
    record.previousLightNormal = float4(path.previousLightNormal, 0.0);
    return record;
}

public PathState primeOfflinePath(
        uint2 pixel,
        PrimeOfflineTransportRecord record) {
    PathState path;
    uint pathControl = record.sourceAndPathControl.w;
    path.physicalOrigin = record.physicalOriginAndPreviousBsdfPdf.xyz;
    path.previousBsdfPdf = record.physicalOriginAndPreviousBsdfPdf.w;
    path.rayDirection = record.rayDirectionAndControl.xyz;
    path.etaScale = record.throughput.w;
    path.throughput = record.throughput.xyz;
    uint bounce = (pathControl >> PRIME_OFFLINE_PATH_BOUNCE_SHIFT)
            & PRIME_OFFLINE_PATH_BYTE_MASK;
    uint flags = (pathControl >> PRIME_OFFLINE_PATH_FLAGS_SHIFT)
            & PRIME_OFFLINE_PATH_FLAGS_MASK;
    path.control = primeMakePathStateControl(bounce, flags);
    path.sourcePrimitive = record.sourceAndPathControl.xy;
    path.sampleSeed = primePathSampleSeed(pixel, primeSampleEpoch(), 0u);
    path.previousLightNormal = record.previousLightNormal.xyz;
    return path;
}

public PrimeOpenPbrVolumeStack primeOfflineVolumeStack(
        PrimeOfflineTransportRecord record) {
    PrimeOpenPbrVolumeStack stack;
    stack.values[0] = primeUnpackWavefrontMedium(record.medium0);
    stack.values[1] = primeUnpackWavefrontMedium(record.medium1);
    uint control = asuint(record.rayDirectionAndControl.w);
    stack.count = min(
            (control >> PRIME_OFFLINE_CONTROL_MEDIUM_SHIFT)
                    & PRIME_OFFLINE_CONTROL_MEDIUM_MASK,
            2u);
    return stack;
}

public bool primeOfflineIsActive(PrimeOfflinePathRecord record) {
    return (asuint(record.transport.rayDirectionAndControl.w)
            & PRIME_OFFLINE_CONTROL_ACTIVE) != 0u;
}
