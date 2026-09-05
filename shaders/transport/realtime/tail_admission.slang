#language slang 2026
module "transport/realtime/tail_admission.slang";

import "prime_abi.slang";
import "math/hash.slang";
import "prime_realtime_abi.slang";
import "prime_abi_types.slang";
import "contract/path.slang";
import "math/sampling.slang";
import "math/transport.slang";
import "policy/realtime/queue.slang";
import "policy/runtime/config.slang";
import "policy/transport/path.slang";
import "state/realtime/contract.slang";
import "state/realtime/queue.slang";

// Compact only paths with remaining bounce budget into the register-heavy tail. This low-register
// stage is deliberately separate: rejected lanes must not acquire the tail's source closure.
public void primeAdmitRealtimeTailPath(uint sourceQueue, uint launchIndex) {
    uint recordIndex = primeWavefrontQueuedPath(sourceQueue, launchIndex);
    uint pathControl = primeWavefrontPaths[primeWavefrontTransportIndex(recordIndex)]
            .transport.sourceAndPathControl.w;
    uint control = asuint(primeWavefrontPaths[primeWavefrontTransportIndex(recordIndex)]
            .transport.rayDirectionAndDenoiserControl.w);
    uint secondaryBounce = pathControl & PRIME_WAVEFRONT_BYTE_MASK;
    bool continuation = primeRealtimeCompletedBounceCount(secondaryBounce)
            < primeMaximumBounces();
    bool rouletteBlocked = false;
    if (primeWavefrontTransparentBranch(control)) {
        bool guideEnabled = primeWavefrontGuideEnabled(control);
        bool hasGuide =
                (control & PRIME_WAVEFRONT_REACHED_SOLID_ANGLE) != 0u;
        uint guideControl = primeWavefrontPaths[primeWavefrontTransportIndex(recordIndex)]
                .transport.sourceAndPathControl.z;
        bool pathOwnsGuide = primeTransparentTransportOwnsGuide(
                false,
                primeWavefrontGuideFallback(guideControl));
        rouletteBlocked = primeTransparentRouletteBlocked(
                pathOwnsGuide, guideEnabled, hasGuide);
    }
    if (continuation
            && !rouletteBlocked
            && primeRussianRouletteApplies(
                    secondaryBounce, PRIME_RUSSIAN_ROULETTE_START)) {
        uint2 pixel = primeWavefrontPixel(recordIndex);
        uint samplePathIndex = primeWavefrontTransmissionBranch(control)
                ? 1u
                : 0u;
        PrimePreparedSampleBase preparedSample = primePreparePathSample(
                primePathSampleSeed(
                        pixel, primeSampleEpoch(), samplePathIndex),
                primeSampleIndex(),
                secondaryBounce);
        float3 throughput = primeWavefrontPaths[primeWavefrontTransportIndex(recordIndex)]
                .transport.throughputAndReserved.xyz;
        float etaScale = asfloat(
                primeWavefrontPaths[primeWavefrontTransportIndex(recordIndex)].transport.medium1.w);
        float survival = primeRussianRouletteSurvival(throughput, etaScale);
        float rouletteSample = primeHashSample1D(
                preparedSample,
                PRIME_SAMPLE_EFFECT_RUSSIAN_ROULETTE,
                PRIME_SAMPLE_DIMENSION_PRIMARY);
        continuation = rouletteSample < survival;
        if (continuation) {
            primeWavefrontPaths[primeWavefrontTransportIndex(recordIndex)]
                    .transport.throughputAndReserved.xyz =
                    primeRussianRouletteReweight(throughput, survival);
        }
    }
    primeAppendWavefrontContinuation(
            PrimeWavefrontContinuationAppender(),
            PRIME_WAVEFRONT_AREA_QUEUE,
            recordIndex,
            continuation);
}
