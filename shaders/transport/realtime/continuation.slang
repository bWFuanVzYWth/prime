// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

#language slang 2026
module "transport/realtime/continuation.slang";
import "prime_abi.slang";
import "prime_abi_types.slang";
import "prime_realtime_abi.slang";
import "math/hash.slang";
import "math/sampling.slang";
import "math/transport.slang";
import "policy/runtime/config.slang";
import "policy/transport/path.slang";
import "policy/realtime/continuation.slang";
import "state/realtime/contract.slang";

// Runs after scatter's helper returns and all signal / guide stores are complete. Only scalar
// control, throughput and etaScale are loaded; BSDF and guide vectors do not cross this boundary.
public bool primeContinueRealtimePath(uint recordIndex) {
    uint storage = primeWavefrontTransportIndex(recordIndex);
    uint secondaryBounce = primeWavefrontPaths[storage].transport.sourceAndPathControl.w & PRIME_WAVEFRONT_BYTE_MASK;
    uint control = asuint(primeWavefrontPaths[storage].transport.rayDirectionAndDenoiserControl.w);
    uint minimum = primeMinimumRealtimeBounces();
    uint maximum = primeMaximumBounces();
    bool continuation = primeRealtimeCanContinue(secondaryBounce, minimum, maximum);
    bool rouletteBlocked = false;
    if (primeWavefrontTransparentBranch(control)) {
        uint guide = primeWavefrontPaths[storage].transport.sourceAndPathControl.z;
        rouletteBlocked = primeTransparentRouletteBlocked(
                primeTransparentTransportOwnsGuide(false, primeWavefrontGuideFallback(guide)),
                primeWavefrontGuideEnabled(control), (control & PRIME_WAVEFRONT_REACHED_SOLID_ANGLE) != 0u);
    }
    if (!rouletteBlocked && primeRealtimeNeedsRoulette(secondaryBounce, minimum, maximum)) {
        uint branch = primeWavefrontTransmissionBranch(control) ? 1u : 0u;
        PrimePreparedSampleBase sample = primePreparePathSample(
                primePathSampleSeed(primeWavefrontPixel(recordIndex), primeSampleEpoch(), branch),
                primeSampleIndex(), secondaryBounce);
        float3 throughput = primeWavefrontPaths[storage].transport.throughputAndReserved.xyz;
        float eta = asfloat(primeWavefrontPaths[storage].transport.medium.w);
        float survival = primeRussianRouletteSurvival(throughput, eta);
        continuation = primeHashSample1D(sample, PRIME_SAMPLE_EFFECT_RUSSIAN_ROULETTE,
                PRIME_SAMPLE_DIMENSION_PRIMARY) < survival;
        if (continuation) primeWavefrontPaths[storage].transport.throughputAndReserved.xyz =
                primeRussianRouletteReweight(throughput, survival);
    }
    if (!continuation) primeWavefrontPaths[storage].transport.rayDirectionAndDenoiserControl.w =
            asfloat(control & ~PRIME_WAVEFRONT_ACTIVE_MASK);
    return continuation;
}
