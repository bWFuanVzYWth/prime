#language slang 2026
module "state/realtime/direct.slang";

import "state/realtime/contract.slang";
import "state/realtime/medium.slang";
import "state/realtime/path_storage.slang";
import "state/realtime/phase_scratch.slang";
import "bsdf/compact/contract/volume.slang";

// NEE state stays narrow. Reverse-MIS state is loaded only after NEE so origin/PDF/normal do not
// remain live through the shadow-trace call tree.
public struct PrimeWavefrontDirectState {
    public float3 viewDirection;
    public float3 throughput;
    public uint bounce;
    public uint control;
    public PrimeOpenPbrVolumeStack volumeStack;
};

public struct PrimeWavefrontHitEmissionState {
    public float3 viewDirection;
    public uint bounce;
    public uint pathFlags;
    public uint control;
    public uint guideControl;
    public float3 physicalOrigin;
    public float previousBsdfPdf;
    public float3 previousLightNormal;
};

PrimeOpenPbrVolumeStack primeUnpackWavefrontDirectVolumeStack(
        uint4 sourceAndPathControl,
        float4 rayDirectionAndDenoiserControl,
        uint4 medium0,
        uint4 medium1) {
    PrimeOpenPbrVolumeStack volumeStack;
    volumeStack.values[0] = primeUnpackWavefrontMediumState(
            medium0,
            medium1,
            0u,
            primeUnpackWavefrontMediumIor(sourceAndPathControl.z, 0u));
    volumeStack.values[1] = primeUnpackWavefrontMediumState(
            medium0,
            medium1,
            1u,
            primeUnpackWavefrontMediumIor(sourceAndPathControl.z, 1u));
    volumeStack.count = min(
            (asuint(rayDirectionAndDenoiserControl.w)
                    >> PRIME_WAVEFRONT_MEDIUM_COUNT_SHIFT)
                    & PRIME_WAVEFRONT_MEDIUM_COUNT_MASK,
            2u);
    return volumeStack;
}

public PrimeWavefrontDirectState primeLoadWavefrontDirectState(uint pathIndex) {
    uint4 sourceAndPathControl =
            primeWavefrontPaths[pathIndex].transport.sourceAndPathControl;
    float4 rayDirectionAndDenoiserControl =
            primeWavefrontPaths[pathIndex]
                    .transport.rayDirectionAndDenoiserControl;
    float4 throughputAndReserved =
            primeWavefrontPaths[pathIndex].transport.throughputAndReserved;
    uint4 medium0 = primeWavefrontPaths[pathIndex].transport.medium0;
    uint4 medium1 = primeWavefrontPaths[pathIndex].transport.medium1;
    PrimeWavefrontDirectState state;
    state.viewDirection = -rayDirectionAndDenoiserControl.xyz;
    state.throughput = throughputAndReserved.xyz;
    state.bounce = (sourceAndPathControl.w >> PRIME_WAVEFRONT_BOUNCE_SHIFT)
            & PRIME_WAVEFRONT_BYTE_MASK;
    state.control = asuint(rayDirectionAndDenoiserControl.w);
    state.volumeStack = primeUnpackWavefrontDirectVolumeStack(
            sourceAndPathControl,
            rayDirectionAndDenoiserControl,
            medium0,
            medium1);
    return state;
}

public PrimeWavefrontHitEmissionState primeLoadWavefrontHitEmissionState(
        uint pathIndex) {
    uint4 sourceAndPathControl =
            primeWavefrontPaths[pathIndex].transport.sourceAndPathControl;
    float4 rayDirectionAndDenoiserControl =
            primeWavefrontPaths[pathIndex]
                    .transport.rayDirectionAndDenoiserControl;
    float4 physicalOriginAndPreviousBsdfPdf =
            primeWavefrontPaths[pathIndex]
                    .transport.physicalOriginAndPreviousBsdfPdf;
    PrimeWavefrontHitEmissionState state;
    state.viewDirection = -rayDirectionAndDenoiserControl.xyz;
    state.bounce = (sourceAndPathControl.w >> PRIME_WAVEFRONT_BOUNCE_SHIFT)
            & PRIME_WAVEFRONT_BYTE_MASK;
    state.pathFlags = (sourceAndPathControl.w >> PRIME_WAVEFRONT_PATH_FLAGS_SHIFT)
            & PRIME_WAVEFRONT_PATH_FLAGS_MASK;
    state.control = asuint(rayDirectionAndDenoiserControl.w);
    state.guideControl = sourceAndPathControl.z;
    state.physicalOrigin = physicalOriginAndPreviousBsdfPdf.xyz;
    state.previousBsdfPdf = physicalOriginAndPreviousBsdfPdf.w;
    state.previousLightNormal =
            primeLoadWavefrontPreviousLightNormal(pathIndex);
    return state;
}

public PrimeOpenPbrVolumeStack primeLoadWavefrontHitVolumeStack(
        uint pathIndex) {
    uint4 sourceAndPathControl =
            primeWavefrontPaths[pathIndex].transport.sourceAndPathControl;
    float4 rayDirectionAndDenoiserControl =
            primeWavefrontPaths[pathIndex]
                    .transport.rayDirectionAndDenoiserControl;
    uint4 medium0 = primeWavefrontPaths[pathIndex].transport.medium0;
    uint4 medium1 = primeWavefrontPaths[pathIndex].transport.medium1;
    return primeUnpackWavefrontDirectVolumeStack(
            sourceAndPathControl,
            rayDirectionAndDenoiserControl,
            medium0,
            medium1);
}
