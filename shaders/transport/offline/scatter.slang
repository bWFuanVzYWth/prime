// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

#language slang 2026
module "transport/offline/scatter.slang";

import "prime_abi.slang";
import "math/hash.slang";
import "prime_abi_types.slang";
import "state/path.slang";
import "service/bsdf/contract/sample.slang";
import "service/bsdf/dispatch/sample.slang";
import "math/sampling.slang";
import "math/packed_geometry.slang";
import "math/transport.slang";
import "policy/runtime/config.slang";
import "service/trace/context.slang";
import "state/offline/contract.slang";
import "state/offline/index.slang";
import "state/offline/queue.slang";
import "state/offline/staged_light.slang";
import "state/offline/surface.slang";
import "state/offline/transport.slang";
import "transport/scatter/path_update.slang";
import "transport/scatter/contract.slang";
import "contract/medium.slang";
import "contract/path.slang";

public bool primeAdvanceOfflinePath(
        inout PrimeIntegratorContext context,
        inout PathState path,
        SurfaceInteraction surface,
        BsdfSample bsdf,
        PrimePreparedSampleBase bounceSample) {
    if (!primeApplyPathScatter(context, path, surface, bsdf)) {
        return false;
    }
    if (!primeRussianRouletteApplies(
            primePathBounce(path.control), PRIME_RUSSIAN_ROULETTE_START)) {
        return true;
    }
    float survival = primeRussianRouletteSurvival(
            path.throughput, path.etaScale);
    float rouletteSample = primeHashSample1D(
            bounceSample,
            PRIME_SAMPLE_EFFECT_RUSSIAN_ROULETTE,
            PRIME_SAMPLE_DIMENSION_PRIMARY);
    if (rouletteSample >= survival) {
        return false;
    }
    path.throughput = primeRussianRouletteReweight(path.throughput, survival);
    return true;
}

public void primeScatterOfflinePath<Appender>(
        Appender appender,
        inout PrimeIntegratorContext context,
        uint sourceQueue,
        uint targetQueue,
        uint launchIndex)
    where Appender : IPrimeOfflineContinuationAppender
{
    uint pathIndex = primeOfflineQueuedPath(sourceQueue, launchIndex);
    PrimeOfflinePathRecord record = primeOfflinePaths[pathIndex];
    if (!primeOfflineIsActive(record)) {
        return;
    }
    uint2 pixel = primeOfflinePixel(pathIndex);
    PathState path = primeOfflinePath(pixel, record.transport);
    PrimeMedium medium = primeOfflineMedium(record.transport);
    SurfaceInteraction surface = primeLoadOfflineSurface(pathIndex);
    PrimePreparedSampleBase preparedSample = primePreparePathSample(
            path.sampleSeed,
            primeSampleIndex(),
            primePathBounce(path.control) + 1u);
    PrimePathScatter scatter = primeSamplePathSurface(
            context,
            surface,
            -path.rayDirection,
            primeSobolSample3D(
                    preparedSample,
                    PRIME_SAMPLE_EFFECT_SCATTER_BSDF,
                    PRIME_SAMPLE_DIMENSION_PRIMARY),
            medium,
            primeUsesAirGap());
    BsdfSample bsdf = scatter.bsdf;
    medium = scatter.medium;
    bool usedAreaNee = primeOfflineStagedDirectEnabled(
            primeLoadOfflineStagedLightSelection(pathIndex));
    bool continuation = false;
    if (!primeHasScatter(bsdf)) {
        path.physicalOrigin = surface.position;
        path.sourcePrimitive = uint2(
                surface.sectionIndex, primeSurfaceTriangleIndex(surface));
        path.control = primePathWithFlags(
                path.control,
                usedAreaNee ? PRIME_OFFLINE_PATH_PREVIOUS_AREA_NEE : 0u);
    } else if (!primeAdvanceOfflinePath(
            context, path, surface, bsdf, preparedSample)) {
        path.control = primePathWithFlags(
                path.control,
                usedAreaNee ? PRIME_OFFLINE_PATH_PREVIOUS_AREA_NEE : 0u);
    } else {
        path.control = primePathWithAddedFlags(
                path.control,
                usedAreaNee ? PRIME_OFFLINE_PATH_PREVIOUS_AREA_NEE : 0u);
        path.control = primeIncrementPathBounce(path.control);
        continuation = primePathBounce(path.control) < primeMaximumBounces();
    }
    record.transport = primeMakeOfflineTransport(
            path, medium, continuation);
    primeOfflinePaths[pathIndex] = record;
    primeAppendOfflineContinuation(
            appender, targetQueue, pathIndex, continuation);
}
