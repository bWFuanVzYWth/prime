#language slang 2026
module "math/lambert_roulette.slang";

import "math/hash.slang";
import "math/transport.slang";
import "prime_abi_types.slang";

public float primeLambertRouletteSample(uint2 pixel, uint sampleIndex, uint epoch, uint completedScatters)
{
    // Exactly the ordinary path's hash domain, with the sole Lambert branch fixed to zero.
    uint seed = primeHashCombine(primePathSampleSeed(pixel, epoch, 0u), completedScatters);
    return primeHashSampleValue(seed, sampleIndex, PRIME_SAMPLE_EFFECT_RUSSIAN_ROULETTE, 0u);
}

// Apply after current-vertex lighting. Only surviving future transport receives compensation.
// The caller supplies finite, nonnegative throughput and a sample in [0, 1).
public bool primeLambertRoulette(inout float3 throughput, float sample)
{
    if (!any(throughput > 0.0)) return false;
    float survival = primeRussianRouletteSurvival(throughput, 1.0);
    if (sample >= survival) return false;
    throughput = primeRussianRouletteReweight(throughput, survival);
    return true;
}

public bool primeLambertRoulette(inout float3 throughput, uint completedScatters, float sample)
{
    if (!any(throughput > 0.0)) return false;
    if (!primeRussianRouletteApplies(completedScatters, PRIME_RUSSIAN_ROULETTE_START)) return true;
    return primeLambertRoulette(throughput, sample);
}
