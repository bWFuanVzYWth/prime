// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

#language slang 2026
module "transport/realtime/light_select.slang";

import "prime_abi.slang";
import "prime_abi_types.slang";
import "contract/light_sample.slang";
import "contract/path.slang";
import "math/direct_sampling.slang";
import "math/direct_discrete_sampling.slang";
import "policy/runtime/config.slang";
import "service/bsdf/contract/sample.slang";
import "service/bsdf/dispatch/classify.slang";
import "contract/light_receiver.slang";
import "state/light_receiver.slang";
import "math/realtime_storage.slang";
import "service/light/tree_select.slang";
import "state/realtime/contract.slang";
import "state/realtime/staged_light.slang";
import "state/realtime/light_selection.slang";
import "transport/direct/selection.slang";

public void primeSelectWavefrontLight(uint recordIndex) {
    PrimeLightReceiver surface = primeLoadLightReceiver(primeWavefrontQueue,
            primeRealtimeSurfaceWord(recordIndex, primeWavefrontPixelCount()));
    PrimeStagedLightSelection selection = primeDisabledStagedLightSelection();
    if (surface.hitKind == PRIME_HIT_SURFACE) {
        selection = primeHitOnlyStagedLightSelection();
        uint2 pixel = primeWavefrontPixel(recordIndex);
        PrimeWavefrontLightSelectionState path =
                primeLoadWavefrontLightSelectionState(recordIndex);
        PrimeClosureTraits closureTraits = primeClassifySurface(surface.classification, path.mediumIor, primeUsesAirGap());
        if (primeClosureSupports(
                closureTraits, PRIME_MEASURE_SOLID_ANGLE)) {
            PrimeDirectSampleBase directSample = primeMakeDirectSampleBase(
                    pixel,
                    primeSampleIndex(),
                    primeSampleEpoch(),
                    path.bounce + 1u,
                    uint(recordIndex >= primeWavefrontPixelCount()));
            selection.area = primeSelectAreaLight(
                    surface.position,
                    primeAreaLightReceiverNormal(surface, path.viewDirection),
                    primeDirectDiscreteSample1D(directSample, primeDirectDiscreteSeed(pixel, primeSampleIndex(), primeSampleEpoch(),
                        path.bounce + 1u, uint(recordIndex >= primeWavefrontPixelCount())), 0u));
        }
    }
    primeStoreStagedLightSelection(recordIndex, selection);
}
