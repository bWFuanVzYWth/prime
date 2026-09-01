#language slang 2026
module "state/realtime/direct.slang";

import "state/realtime/contract.slang";
import "state/realtime/medium.slang";
import "bsdf/compact/contract/volume.slang";

// Direct lighting needs no origin, previous-event PDF/normal, source identity or eta scale.
public struct PrimeWavefrontDirectState {
    public float3 viewDirection;
    public float3 throughput;
    public uint bounce;
    public uint control;
    public PrimeOpenPbrVolumeStack volumeStack;
};

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
    state.volumeStack.values[0] = primeUnpackWavefrontMedium(
            medium0,
            primeUnpackWavefrontMediumIor(sourceAndPathControl.z, 0u));
    state.volumeStack.values[1] = primeUnpackWavefrontMedium(
            medium1,
            primeUnpackWavefrontMediumIor(sourceAndPathControl.z, 1u));
    state.volumeStack.count = min(
            (state.control >> PRIME_WAVEFRONT_MEDIUM_COUNT_SHIFT)
                    & PRIME_WAVEFRONT_MEDIUM_COUNT_MASK,
            2u);
    return state;
}
