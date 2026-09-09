// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

#language slang 2026
module "math/sampling.slang";

import "prime_abi_types.slang";
import "math/hash.slang";

public static const uint PRIME_SAMPLE_EFFECT_CAMERA = 0;
public static const uint PRIME_SAMPLE_EFFECT_DIRECT_SUN = 2;
public static const uint PRIME_SAMPLE_EFFECT_SCATTER_BSDF = 3;
public static const uint PRIME_SAMPLE_EFFECT_DIRECT_AREA_LIGHT = 5;
public static const uint PRIME_SAMPLE_DIMENSION_PRIMARY = 0;
public static const uint PRIME_SAMPLE_DIMENSION_SECONDARY = 1;

public static const uint PRIME_SOBOL_INDEX_MASK = 0xffff0000;
public static const float PRIME_UINT32_TO_FLOAT_EXCLUSIVE_SCALE = 1.0 / 4294967808.0;

public struct PrimeSampleBase
{
    public uint2 pixel;
    public uint sampleIndex;
    public uint sampleEpoch;
    public uint vertexIndex;
    public uint pathIndex;
};

public struct PrimePreparedSampleBase
{
    public uint sampleIndex;
    public uint seed;
};

// Joe-Kuo direction numbers for the first four dimensions, flattened to keep the lookup ABI
// explicit across Slang backends.
public static const uint PRIME_SOBOL_BURLEY_TABLE[128] = {
    0x00000001, 0x00000002, 0x00000004, 0x00000008,
    0x00000010, 0x00000020, 0x00000040, 0x00000080,
    0x00000100, 0x00000200, 0x00000400, 0x00000800,
    0x00001000, 0x00002000, 0x00004000, 0x00008000,
    0x00010000, 0x00020000, 0x00040000, 0x00080000,
    0x00100000, 0x00200000, 0x00400000, 0x00800000,
    0x01000000, 0x02000000, 0x04000000, 0x08000000,
    0x10000000, 0x20000000, 0x40000000, 0x80000000,
    0x00000001, 0x00000003, 0x00000005, 0x0000000f,
    0x00000011, 0x00000033, 0x00000055, 0x000000ff,
    0x00000101, 0x00000303, 0x00000505, 0x00000f0f,
    0x00001111, 0x00003333, 0x00005555, 0x0000ffff,
    0x00010001, 0x00030003, 0x00050005, 0x000f000f,
    0x00110011, 0x00330033, 0x00550055, 0x00ff00ff,
    0x01010101, 0x03030303, 0x05050505, 0x0f0f0f0f,
    0x11111111, 0x33333333, 0x55555555, 0xffffffff,
    0x00000001, 0x00000003, 0x00000006, 0x00000009,
    0x00000017, 0x0000003a, 0x00000071, 0x000000a3,
    0x00000116, 0x00000339, 0x00000677, 0x000009aa,
    0x00001601, 0x00003903, 0x00007706, 0x0000aa09,
    0x00010117, 0x0003033a, 0x00060671, 0x000909a3,
    0x00171616, 0x003a3939, 0x00717777, 0x00a3aaaa,
    0x01170001, 0x033a0003, 0x06710006, 0x09a30009,
    0x16160017, 0x3939003a, 0x77770071, 0xaaaa00a3,
    0x00000001, 0x00000003, 0x00000004, 0x0000000a,
    0x0000001f, 0x0000002e, 0x00000045, 0x000000c9,
    0x0000011b, 0x000002a4, 0x0000079a, 0x00000b67,
    0x0000101e, 0x0000302d, 0x00004041, 0x0000a0c3,
    0x0001f104, 0x0002e28a, 0x000457df, 0x000c9bae,
    0x0011a105, 0x002a7289, 0x0079e7db, 0x00b6dba4,
    0x0100011a, 0x030002a7, 0x0400079e, 0x0a000b6d,
    0x1f001001, 0x2e003003, 0x45004004, 0xc900a00a
};


public uint primeHighQualityHash(uint value)
{
    value ^= value >> 16;
    value *= 0x21f0aaad;
    value ^= value >> 15;
    value *= 0xd35a2d97;
    value ^= value >> 15;
    return value ^ 0xe6fe3beb;
}



public uint primeSampleBaseSeed(PrimeSampleBase base)
{
    return primeHashCombine(
            primePathSampleSeed(
                    base.pixel, base.sampleEpoch, base.pathIndex),
            base.vertexIndex);
}

public PrimePreparedSampleBase primePrepareSampleBase(PrimeSampleBase base)
{
    PrimePreparedSampleBase result;
    result.sampleIndex = base.sampleIndex;
    result.seed = primeSampleBaseSeed(base);
    return result;
}

public PrimePreparedSampleBase primePreparePathSample(
        uint sampleSeed, uint sampleIndex, uint vertexIndex)
{
    PrimePreparedSampleBase result;
    result.sampleIndex = sampleIndex;
    result.seed = primeHashCombine(sampleSeed, vertexIndex);
    return result;
}

public uint primeEffectSeed(PrimePreparedSampleBase base, uint effect)
{
    return primeHashCombine(base.seed, effect);
}

public uint primeReversedBitOwen(uint value, uint seed)
{
    value ^= value * 0x3d20adea;
    value += seed;
    value *= (seed >> 16) | 1;
    value ^= value * 0x05526c56;
    value ^= value * 0x53a22864;
    return value;
}

public float primeUintToFloatExclusive(uint value)
{
    return float(value) * PRIME_UINT32_TO_FLOAT_EXCLUSIVE_SCALE;
}


public float primeSobolBurley(uint reversedBitIndex, uint dimension, uint seed)
{
    uint result = 0;
    if (dimension == 0) {
        result = reversebits(reversedBitIndex);
    } else {
        uint index = reversedBitIndex;
        uint tableIndex = 0;
        while (index != 0) {
            uint leadingZeroes = uint(31 - firstbithigh(index));
            result ^= PRIME_SOBOL_BURLEY_TABLE[
                    dimension * 32 + tableIndex + leadingZeroes];
            tableIndex += leadingZeroes + 1;
            index <<= leadingZeroes;
            index <<= 1;
        }
    }
    return primeUintToFloatExclusive(
            reversebits(primeReversedBitOwen(result, seed)));
}

public float primeSobolSample1D(
        PrimePreparedSampleBase base,
        uint effect,
        uint dimension)
{
    uint mixedSeed = primeEffectSeed(base, effect) ^ primeHighQualityHash(dimension);
    uint shuffledIndex = primeReversedBitOwen(
            reversebits(base.sampleIndex), mixedSeed ^ 0xbff95bfe)
            & PRIME_SOBOL_INDEX_MASK;
    return primeSobolBurley(shuffledIndex, 0, mixedSeed ^ 0x635c77bd);
}

public float2 primeSobolSample2D(
        PrimePreparedSampleBase base,
        uint effect,
        uint dimensionSet)
{
    uint mixedSeed = primeEffectSeed(base, effect) ^ primeHighQualityHash(dimensionSet);
    uint shuffledIndex = primeReversedBitOwen(
            reversebits(base.sampleIndex), mixedSeed ^ 0xf8ade99a)
            & PRIME_SOBOL_INDEX_MASK;
    return float2(
            primeSobolBurley(shuffledIndex, 0, mixedSeed ^ 0xe0aaaf76),
            primeSobolBurley(shuffledIndex, 1, mixedSeed ^ 0x94964d4e));
}

public float3 primeSobolSample3D(
        PrimePreparedSampleBase base,
        uint effect,
        uint dimensionSet)
{
    uint mixedSeed = primeEffectSeed(base, effect) ^ primeHighQualityHash(dimensionSet);
    uint shuffledIndex = primeReversedBitOwen(
            reversebits(base.sampleIndex), mixedSeed ^ 0xcaa726ac)
            & PRIME_SOBOL_INDEX_MASK;
    return float3(
            primeSobolBurley(shuffledIndex, 0, mixedSeed ^ 0x9e78e391),
            primeSobolBurley(shuffledIndex, 1, mixedSeed ^ 0x67c33241),
            primeSobolBurley(shuffledIndex, 2, mixedSeed ^ 0x78c395c5));
}

public float4 primeSobolSample4D(
        PrimePreparedSampleBase base,
        uint effect,
        uint dimensionSet)
{
    uint mixedSeed = primeEffectSeed(base, effect) ^ primeHighQualityHash(dimensionSet);
    uint shuffledIndex = primeReversedBitOwen(
            reversebits(base.sampleIndex), mixedSeed ^ 0xc2c1a055)
            & PRIME_SOBOL_INDEX_MASK;
    return float4(
            primeSobolBurley(shuffledIndex, 0, mixedSeed ^ 0x39468210),
            primeSobolBurley(shuffledIndex, 1, mixedSeed ^ 0xe9d8a845),
            primeSobolBurley(shuffledIndex, 2, mixedSeed ^ 0x5f32b482),
            primeSobolBurley(shuffledIndex, 3, mixedSeed ^ 0x1524cc56));
}

public float primeHashSample1D(
        PrimePreparedSampleBase base,
        uint effect,
        uint streamIndex)
{
    return primeHashSampleValue(base.seed, base.sampleIndex, effect, streamIndex);
}
