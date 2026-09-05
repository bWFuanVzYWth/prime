#language slang 2026
module "math/hash.slang";

// Shared hash-only sampling; consumers need not expose the Sobol table or its algorithms.
public static const uint PRIME_SAMPLE_EFFECT_RUSSIAN_ROULETTE = 4u;

public uint primeHash32(uint value)
{
    value ^= value >> 16;
    value *= 0x21f0aaad;
    value ^= value >> 15;
    value *= 0xf35a2d97;
    value ^= value >> 15;
    return value;
}

public uint primeHashCombine(uint seed, uint value)
{
    return seed ^ (primeHash32(value) + 0x9e3779b9 + (seed << 6) + (seed >> 2));
}

public uint primePathSampleSeed(uint2 pixel, uint sampleEpoch, uint pathIndex)
{
    uint seed = primeHash32(pixel.x);
    seed = primeHashCombine(seed, pixel.y);
    seed = primeHashCombine(seed, sampleEpoch);
    return primeHashCombine(seed, pathIndex);
}

public float primeHashToFloat(uint value)
{
    return float(value >> 8) * (1.0 / 16777216.0);
}

public float primeHashSampleValue(uint seed, uint sampleIndex, uint effect, uint streamIndex)
{
    seed = primeHashCombine(seed, effect);
    seed = primeHashCombine(seed, sampleIndex);
    seed = primeHashCombine(seed, streamIndex);
    return primeHashToFloat(primeHash32(seed));
}
