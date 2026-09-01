#language slang 2026
module "transport/realtime/fixed_light_select.slang";

import "prime_abi.slang";
import "prime_abi_types.slang";
import "contract/light_sample.slang";
import "contract/path.slang";
import "math/direct_sampling.slang";
import "policy/runtime/config.slang";
import "service/bsdf/contract/sample.slang";
import "service/bsdf/dispatch/traits.slang";
import "service/light/tree_select.slang";
import "state/realtime/contract.slang";
import "state/realtime/staged_light.slang";
import "state/realtime/light_selection.slang";
import "state/realtime/queue.slang";
import "state/realtime/surface.slang";
import "transport/direct/selection.slang";
import "bsdf/compact/contract/volume.slang";

public void primeSelectFixedLight(uint sourceQueue, uint launchIndex) {
    uint recordIndex = primeWavefrontQueuedPath(sourceQueue, launchIndex);
    SurfaceInteraction surface = primeLoadWavefrontSurface(recordIndex);
    PrimeStagedLightSelection selection = primeDisabledStagedLightSelection();
    if (surface.hitKind == PRIME_HIT_SURFACE) {
        uint2 pixel = primeWavefrontPixel(recordIndex);
        PrimeWavefrontLightSelectionState path =
                primeLoadWavefrontLightSelectionState(recordIndex);
        PrimeClosureTraits closureTraits = primeSurfaceClosureTraits(
                surface, path.viewDirection, path.volumeStack, primeUsesAirGap());
        if (primeClosureSupports(
                closureTraits, PRIME_MEASURE_SOLID_ANGLE)) {
            selection.area = primeInvalidAreaLightSelection();
            PrimeDirectSampleBase directSample = primeMakeDirectSampleBase(
                    pixel,
                    primeSampleIndex(),
                    primeSampleEpoch(),
                    path.bounce + 1u,
                    uint(recordIndex >= primeWavefrontPixelCount()));
            selection.area = primeSelectAreaLight(
                    surface.position,
                    primeAreaLightReceiverNormal(surface, path.viewDirection),
                    primeDirectSample2D(
                            directSample,
                            PRIME_DIRECT_SAMPLE_AREA_PRIMARY).x);
        }
    }
    primeStoreStagedLightSelection(recordIndex, selection);
}
