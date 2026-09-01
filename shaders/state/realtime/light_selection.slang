#language slang 2026
module "state/realtime/light_selection.slang";

import "state/realtime/contract.slang";
import "state/realtime/medium.slang";
import "state/realtime/path_storage.slang";
import "bsdf/compact/contract/volume.slang";

// Light selection consumes only the incident direction, local bounce and volume identity. Keep
// the remaining transport state out of this phase so it does not extend across material dispatch.
public struct PrimeWavefrontLightSelectionState {
    public float3 viewDirection;
    public uint bounce;
    public PrimeOpenPbrVolumeStack volumeStack;
};

public PrimeWavefrontLightSelectionState primeLoadWavefrontLightSelectionState(
        uint pathIndex) {
    uint4 sourceAndPathControl =
            primeWavefrontPaths[pathIndex].transport.sourceAndPathControl;
    float4 rayDirectionAndDenoiserControl =
            primeWavefrontPaths[pathIndex]
                    .transport.rayDirectionAndDenoiserControl;
    uint4 medium0 = primeWavefrontPaths[pathIndex].transport.medium0;
    uint4 medium1 = primeWavefrontPaths[pathIndex].transport.medium1;

    PrimeWavefrontLightSelectionState state;
    state.viewDirection = -rayDirectionAndDenoiserControl.xyz;
    state.bounce = (sourceAndPathControl.w >> PRIME_WAVEFRONT_BOUNCE_SHIFT)
            & PRIME_WAVEFRONT_BYTE_MASK;
    state.volumeStack.values[0] = primeUnpackWavefrontMediumState(
            medium0,
            medium1,
            0u,
            primeUnpackWavefrontMediumIor(sourceAndPathControl.z, 0u));
    state.volumeStack.values[1] = primeUnpackWavefrontMediumState(
            medium0,
            medium1,
            1u,
            primeUnpackWavefrontMediumIor(sourceAndPathControl.z, 1u));
    uint denoiserControl = asuint(rayDirectionAndDenoiserControl.w);
    state.volumeStack.count = min(
            (denoiserControl >> PRIME_WAVEFRONT_MEDIUM_COUNT_SHIFT)
                    & PRIME_WAVEFRONT_MEDIUM_COUNT_MASK,
            2u);
    return state;
}
