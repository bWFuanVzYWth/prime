// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

#language slang 2026
module "state/realtime/light_selection.slang";

import "state/realtime/contract.slang";
import "state/realtime/medium.slang";
import "state/realtime/path_storage.slang";
import "service/bsdf/support/medium_identity.slang";

// Classification needs only incident IOR; extinction and etaScale never enter light selection.
public struct PrimeWavefrontLightSelectionState {
    public float3 viewDirection;
    public uint bounce;
    public float mediumIor;
};

public PrimeWavefrontLightSelectionState primeLoadWavefrontLightSelectionState(
        uint pathIndex) {
    uint4 sourceAndPathControl =
            primeWavefrontPaths[primeWavefrontTransportIndex(pathIndex)].transport.sourceAndPathControl;
    float4 rayDirectionAndDenoiserControl =
            primeWavefrontPaths[primeWavefrontTransportIndex(pathIndex)]
                    .transport.rayDirectionAndDenoiserControl;

    PrimeWavefrontLightSelectionState state;
    state.viewDirection = -rayDirectionAndDenoiserControl.xyz;
    state.bounce = (sourceAndPathControl.w >> PRIME_WAVEFRONT_BOUNCE_SHIFT)
            & PRIME_WAVEFRONT_BYTE_MASK;
    state.mediumIor = primeDecodeMediumIor(primeWavefrontMediumId(sourceAndPathControl.w),
            primeUnpackWavefrontMediumIor(sourceAndPathControl.z));
    return state;
}
