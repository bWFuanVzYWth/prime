// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

#language slang 2026
module "state/realtime/light_selection.slang";

import "state/realtime/contract.slang";
import "state/realtime/medium.slang";
import "state/realtime/path_storage.slang";
import "contract/medium.slang";

// Light selection consumes only the incident direction, local bounce and volume identity. Keep
// the remaining transport state out of this phase so it does not extend across material dispatch.
public struct PrimeWavefrontLightSelectionState {
    public float3 viewDirection;
    public uint bounce;
    public PrimeMedium medium;
};

public PrimeWavefrontLightSelectionState primeLoadWavefrontLightSelectionState(
        uint pathIndex) {
    uint4 sourceAndPathControl =
            primeWavefrontPaths[primeWavefrontTransportIndex(pathIndex)].transport.sourceAndPathControl;
    float4 rayDirectionAndDenoiserControl =
            primeWavefrontPaths[primeWavefrontTransportIndex(pathIndex)]
                    .transport.rayDirectionAndDenoiserControl;
    uint4 medium = primeWavefrontPaths[primeWavefrontTransportIndex(pathIndex)].transport.medium;

    PrimeWavefrontLightSelectionState state;
    state.viewDirection = -rayDirectionAndDenoiserControl.xyz;
    state.bounce = (sourceAndPathControl.w >> PRIME_WAVEFRONT_BOUNCE_SHIFT)
            & PRIME_WAVEFRONT_BYTE_MASK;
    state.medium = primeUnpackWavefrontMediumState(medium,
            primeWavefrontMediumId(sourceAndPathControl.w),
            primeUnpackWavefrontMediumIor(sourceAndPathControl.z));
    return state;
}
