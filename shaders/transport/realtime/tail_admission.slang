#language slang 2026
module "transport/realtime/tail_admission.slang";

import "prime_abi.slang";
import "prime_realtime_abi.slang";
import "prime_abi_types.slang";
import "contract/path.slang";
import "math/sampling.slang";
import "math/transport.slang";
import "policy/realtime/queue.slang";
import "policy/runtime/config.slang";
import "policy/transport/path.slang";
import "state/path.slang";
import "state/realtime/contract.slang";
import "state/realtime/light.slang";
import "state/realtime/ordinary.slang";
import "state/realtime/queue.slang";
import "state/realtime/transport.slang";

// The fixed prefix has already scattered this path. Apply its deferred roulette decision, then
// compact only paths with remaining bounce budget into the register tail.
public void primeAdmitRealtimeTailPath(uint sourceQueue, uint launchIndex) {
    uint recordIndex = primeWavefrontQueuedPath(sourceQueue, launchIndex);
    PrimeWavefrontTransportRecord record =
            primeLoadWavefrontTransportRecord(recordIndex);
    uint control = asuint(record.rayDirectionAndDenoiserControl.w);
    PathState path = primeWavefrontPath(
            primeWavefrontPixel(recordIndex), record);
    uint secondaryBounce = primePathBounce(path.control);
    bool continuation = primeRealtimeCompletedBounceCount(secondaryBounce)
            < primeMaximumBounces();
    bool rouletteBlocked = false;
    if (primeWavefrontTransparentBranch(control)) {
        bool guideEnabled = primeWavefrontGuideEnabled(control);
        bool hasGuide =
                (control & PRIME_WAVEFRONT_REACHED_SOLID_ANGLE) != 0u;
        bool pathOwnsGuide = primeTransparentTransportOwnsGuide(
                false,
                primeWavefrontGuideFallback(
                        record.sourceAndPathControl.z));
        rouletteBlocked = primeTransparentRouletteBlocked(
                pathOwnsGuide, guideEnabled, hasGuide);
    }
    uint rouletteScatterIndex = secondaryBounce;
    if (continuation
            && !rouletteBlocked
            && primeRussianRouletteApplies(
                    rouletteScatterIndex, PRIME_RUSSIAN_ROULETTE_START)) {
        PrimePreparedSampleBase preparedSample = primePreparePathSample(
                path.sampleSeed,
                primeSampleIndex(),
                rouletteScatterIndex);
        float survival = primeRussianRouletteSurvival(
                path.throughput, path.etaScale);
        float rouletteSample = primeHashSample1D(
                preparedSample,
                PRIME_SAMPLE_EFFECT_RUSSIAN_ROULETTE,
                PRIME_SAMPLE_DIMENSION_PRIMARY);
        continuation = rouletteSample < survival;
        if (continuation) {
            path.throughput = primeRussianRouletteReweight(
                    path.throughput, survival);
            record.throughputAndReserved.xyz = path.throughput;
            primeStoreWavefrontTransportRecord(recordIndex, record);
        }
    }
    primeAppendWavefrontContinuation(
            PrimeWavefrontContinuationAppender(),
            PRIME_WAVEFRONT_AREA_QUEUE,
            recordIndex,
            continuation);
}
