#language slang 2026
module "transport/offline/light_select.slang";

import "prime_abi.slang";
import "prime_offline_abi.slang";
import "prime_abi_types.slang";
import "math/sampling.slang";
import "policy/runtime/config.slang";
import "service/bsdf/contract/sample.slang";
import "service/bsdf/dispatch/traits.slang";
import "service/light/tree_select.slang";
import "state/path.slang";
import "state/offline/contract.slang";
import "state/offline/path_storage.slang";
import "state/offline/index.slang";
import "state/offline/queue.slang";
import "state/offline/staged_light.slang";
import "state/offline/surface.slang";
import "state/offline/transport.slang";
import "transport/direct/selection.slang";
import "bsdf/compact/contract/volume.slang";
import "contract/path.slang";

public void primeSelectOfflineLight(uint sourceQueue, uint launchIndex) {
    uint pathIndex = primeOfflineQueuedPath(sourceQueue, launchIndex);
    PrimeOfflineStagedLightSelection selection =
            primeDisabledOfflineStagedLightSelection();
    SurfaceInteraction surface = primeLoadOfflineSurface(pathIndex);
    if (surface.hitKind == PRIME_HIT_SURFACE) {
        PrimeOfflineTransportRecord record = primeOfflinePaths[pathIndex].transport;
        uint2 pixel = primeOfflinePixel(pathIndex);
        PathState path = primeOfflinePath(pixel, record);
        PrimeOpenPbrVolumeStack volumeStack = primeOfflineVolumeStack(record);
        float3 viewDirection = -path.rayDirection;
        PrimeClosureTraits closureTraits = primeSurfaceClosureTraits(
                surface, viewDirection, volumeStack, primeUsesAirGap());
        if (primeClosureSupports(closureTraits, PRIME_MEASURE_SOLID_ANGLE)) {
            PrimePreparedSampleBase preparedSample = primePreparePathSample(
                    path.sampleSeed,
                    primeSampleIndex(),
                    primePathBounce(path.control) + 1u);
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
