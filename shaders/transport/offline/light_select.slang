// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

#language slang 2026
module "transport/offline/light_select.slang";

import "prime_abi.slang";
import "prime_offline_abi.slang";
import "prime_abi_types.slang";
import "math/sampling.slang";
import "math/hash.slang";
import "policy/runtime/config.slang";
import "service/bsdf/contract/sample.slang";
import "service/bsdf/dispatch/classify.slang";
import "state/light_receiver.slang";
import "contract/light_receiver.slang";
import "service/light/tree_select.slang";
import "state/path.slang";
import "state/offline/contract.slang";
import "state/offline/path_storage.slang";
import "state/offline/index.slang";
import "state/offline/queue.slang";
import "state/offline/staged_light.slang";
import "transport/direct/selection.slang";
import "contract/path.slang";

public void primeSelectOfflineLight(uint sourceQueue, uint launchIndex) {
    uint pathIndex = primeOfflineQueuedPath(sourceQueue, launchIndex);
    PrimeOfflineStagedLightSelection selection =
            primeDisabledOfflineStagedLightSelection();
    PrimeLightReceiver surface = primeLoadLightReceiver(primeOfflineQueue,
            pathIndex * (PRIME_OFFLINE_WAVEFRONT_SURFACE_RECORD_SIZE / 4u));
    if (surface.hitKind == PRIME_HIT_SURFACE) {
        uint state = primeOfflinePaths[pathIndex].transport.sourceStateAndMediumId.z;
        uint mediumId = primeOfflinePaths[pathIndex].transport.sourceStateAndMediumId.w;
        uint2 pixel = primeOfflinePixel(pathIndex);
        float3 viewDirection = -asfloat(primeOfflinePaths[pathIndex].transport.rayDirectionAndEtaScale.xyz);
        PrimeClosureTraits closureTraits = primeClassifySurface(surface.classification, primeOfflineIncidentIor(state, mediumId), primeUsesAirGap());
        if (primeClosureSupports(closureTraits, PRIME_MEASURE_SOLID_ANGLE)) {
            PrimePreparedSampleBase preparedSample = primePreparePathSample(
                    primePathSampleSeed(pixel, primeSampleEpoch(), 0u),
                    primeSampleIndex(),
                    primeOfflineBounce(state) + 1u);
            float3 areaSample = primeSobolSample3D(
                    preparedSample,
                    PRIME_SAMPLE_EFFECT_DIRECT_AREA_LIGHT,
                    PRIME_SAMPLE_DIMENSION_PRIMARY);
            selection.area = primeSelectAreaLight(
                    surface.position,
                    primeAreaLightReceiverNormal(surface, viewDirection),
                    areaSample.x);
        }
    }
    primeStoreOfflineStagedLightSelection(pathIndex, selection);
}
