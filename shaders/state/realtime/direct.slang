// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

#language slang 2026
module "state/realtime/direct.slang";

import "state/realtime/contract.slang";
import "state/realtime/medium.slang";
import "state/realtime/path_storage.slang";
import "state/realtime/phase_scratch.slang";
import "contract/medium.slang";

// NEE state stays narrow. Reverse-MIS state is loaded only after NEE so origin/PDF/normal do not
// remain live through the shadow-trace call tree.
public struct PrimeWavefrontDirectState {
    public float3 viewDirection;
    public float3 throughput;
    public uint bounce;
    public uint control;
    public PrimeMedium medium;
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

PrimeMedium primeUnpackWavefrontDirectMedium(uint4 control, uint4 medium) {
    return primeUnpackWavefrontMediumState(medium,
            primeWavefrontMediumId(control.w), primeUnpackWavefrontMediumIor(control.z));
}

public PrimeWavefrontDirectState primeLoadWavefrontDirectState(uint pathIndex) {
    uint4 sourceAndPathControl =
            primeWavefrontPaths[primeWavefrontTransportIndex(pathIndex)].transport.sourceAndPathControl;
    float4 rayDirectionAndDenoiserControl =
            primeWavefrontPaths[primeWavefrontTransportIndex(pathIndex)]
                    .transport.rayDirectionAndDenoiserControl;
    float4 throughputAndReserved =
            primeWavefrontPaths[primeWavefrontTransportIndex(pathIndex)].transport.throughputAndReserved;
    uint4 medium = primeWavefrontPaths[primeWavefrontTransportIndex(pathIndex)].transport.medium;
    PrimeWavefrontDirectState state;
    state.viewDirection = -rayDirectionAndDenoiserControl.xyz;
    state.throughput = throughputAndReserved.xyz;
    state.bounce = (sourceAndPathControl.w >> PRIME_WAVEFRONT_BOUNCE_SHIFT)
            & PRIME_WAVEFRONT_BYTE_MASK;
    state.control = asuint(rayDirectionAndDenoiserControl.w);
    state.medium = primeUnpackWavefrontDirectMedium(
            sourceAndPathControl, medium);
    return state;
}

public PrimeWavefrontHitEmissionState primeLoadWavefrontHitEmissionState(
        uint pathIndex) {
    uint4 sourceAndPathControl =
            primeWavefrontPaths[primeWavefrontTransportIndex(pathIndex)].transport.sourceAndPathControl;
    float4 rayDirectionAndDenoiserControl =
            primeWavefrontPaths[primeWavefrontTransportIndex(pathIndex)]
                    .transport.rayDirectionAndDenoiserControl;
    float4 physicalOriginAndPreviousBsdfPdf =
            primeWavefrontPaths[primeWavefrontTransportIndex(pathIndex)]
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

public PrimeMedium primeLoadWavefrontHitMedium(
        uint pathIndex) {
    uint4 sourceAndPathControl =
            primeWavefrontPaths[primeWavefrontTransportIndex(pathIndex)].transport.sourceAndPathControl;
    uint4 medium = primeWavefrontPaths[primeWavefrontTransportIndex(pathIndex)].transport.medium;
    return primeUnpackWavefrontDirectMedium(
            sourceAndPathControl, medium);
}
