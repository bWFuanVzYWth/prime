// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

#language slang 2026
module "math/zsobol_sampling.slang";

public static const uint PRIME_DIRECT_SAMPLE_AREA_PRIMARY = 0u;
public static const uint PRIME_DIRECT_SAMPLE_AREA_SECONDARY = 1u;
public static const uint PRIME_DIRECT_SAMPLE_SUN = 2u;

static const uint PRIME_DIRECT_SAMPLE_STREAM_COUNT = 3u;
// Keep the temporal suffix inside the hierarchical bijection. Applying it as a
// separate XOR shift reuses the same screen-wide Sobol set in every frame.
static const uint PRIME_ZSOBOL_LOG2_SAMPLES_PER_PIXEL = 16u;
static const uint PRIME_ZSOBOL_LOG4_SAMPLES_PER_PIXEL = 8u;
static const float PRIME_ZSOBOL_UINT_SCALE = 1.0 / 4294967296.0;
static const float PRIME_ZSOBOL_ONE_MINUS_EPSILON = 0.9999999403953552;

public struct PrimeDirectSampleBase
{
    public uint64_t mortonIndex;
    public uint dimensionBase;
    public uint digitCount;
    public uint seed;
};

uint64_t primeZSobolConstant(uint high, uint low)
{
    return (uint64_t(high) << 32u) | uint64_t(low);
}

// Non-cryptographic bit finalizer. It only decorrelates permutation and Owen
// selectors; the sampler's exact properties come from the surrounding bijections.
uint primeZSobolAvalanche(uint value)
{
    value ^= value >> 16u;
    value *= 0x7feb352du;
    value ^= value >> 15u;
    value *= 0x846ca68bu;
    value ^= value >> 16u;
    return value;
}

// The selector only chooses one of 24 digit permutations. A 32-bit avalanche of
// both prefix limbs retains nested scrambling without paying for 64-bit products.
uint primeZSobolSelector(uint64_t higherDigits, uint dimensionHash)
{
    return primeZSobolAvalanche(
            uint(higherDigits)
                    ^ (uint(higherDigits >> 32u) * 0x9e3779b9u)
                    ^ dimensionHash);
}

uint64_t primeZSobolLeftShift2(uint value)
{
    uint64_t expanded = uint64_t(value);
    expanded = (expanded ^ (expanded << 16u))
            & primeZSobolConstant(0x0000ffff, 0x0000ffff);
    expanded = (expanded ^ (expanded << 8u))
            & primeZSobolConstant(0x00ff00ff, 0x00ff00ff);
    expanded = (expanded ^ (expanded << 4u))
            & primeZSobolConstant(0x0f0f0f0f, 0x0f0f0f0f);
    expanded = (expanded ^ (expanded << 2u))
            & primeZSobolConstant(0x33333333, 0x33333333);
    expanded = (expanded ^ (expanded << 1u))
            & primeZSobolConstant(0x55555555, 0x55555555);
    return expanded;
}

uint64_t primeZSobolMorton(uint2 pixel)
{
    return (primeZSobolLeftShift2(pixel.y) << 1u)
            | primeZSobolLeftShift2(pixel.x);
}

uint primeZSobolResolutionDigits(uint2 extent)
{
    uint remaining = max(extent.x, extent.y) - 1u;
    return remaining == 0u ? 0u : uint(firstbithigh(remaining)) + 1u;
}

public PrimeDirectSampleBase primeMakeDirectSampleBase(
        uint2 pixel,
        uint2 outputExtent,
        uint sampleIndex,
        uint sampleEpoch,
        uint vertexIndex,
        uint pathBranch)
{
    PrimeDirectSampleBase result;
    result.mortonIndex = (primeZSobolMorton(pixel)
                    << PRIME_ZSOBOL_LOG2_SAMPLES_PER_PIXEL)
            | uint64_t(sampleIndex);
    uint pathPair = vertexIndex * 2u + pathBranch;
    result.dimensionBase = pathPair
            * PRIME_DIRECT_SAMPLE_STREAM_COUNT * 2u;
    result.digitCount = primeZSobolResolutionDigits(outputExtent)
            + PRIME_ZSOBOL_LOG4_SAMPLES_PER_PIXEL;
    result.seed = sampleEpoch;
    return result;
}

// AGL(2, 2) has 6 invertible matrices and 4 translations, exactly covering all
// 24 permutations of a base-4 digit without a table or 64-bit remainder.
uint primeZSobolPermutation(uint selector, uint digit)
{
    uint matrixSelector = selector >> 2u;
    // matrixSelector is 30-bit, so this range reduction cannot overflow u32.
    uint matrixIndex = (matrixSelector + (matrixSelector << 1u)) >> 29u;
    uint matrix = (0x00b7e6d9u >> (matrixIndex * 4u)) & 15u;
    uint xMask = 0u - (digit & 1u);
    uint yMask = 0u - (digit >> 1u);
    return (selector & 3u)
            ^ (xMask & (matrix & 3u))
            ^ (yMask & (matrix >> 2u));
}

uint64_t primeZSobolSampleIndex(PrimeDirectSampleBase base, uint dimension)
{
    uint64_t source = base.mortonIndex
            << (64u - 2u * base.digitCount);
    uint64_t higherDigits = uint64_t(0);
    uint64_t sampleIndex = uint64_t(0);
    uint remainingDigits = base.digitCount;
    uint dimensionHash = 0x55555555u * dimension;
    while (remainingDigits != 0u) {
        uint digit = uint(source >> 62u);
        uint selector = primeZSobolSelector(higherDigits, dimensionHash);
        sampleIndex = (sampleIndex << 2u)
                | uint64_t(primeZSobolPermutation(selector, digit));
        higherDigits = (higherDigits << 2u) | uint64_t(digit);
        source <<= 2u;
        --remainingDigits;
    }
    return sampleIndex;
}

// The pinned dimension-one columns are reversebits((1 + x)^i). Folding the limbs
// accounts for their 32-column period. Returning the reversed-domain value also
// cancels FastOwen's immediately following input reversal.
uint primeZSobolDimensionOneReversed(uint64_t sampleIndex)
{
    uint value = uint(sampleIndex) ^ uint(sampleIndex >> 32u);
    value ^= (value & 0xaaaaaaaau) >> 1u;
    value ^= (value & 0xccccccccu) >> 2u;
    value ^= (value & 0xf0f0f0f0u) >> 4u;
    value ^= (value & 0xff00ff00u) >> 8u;
    value ^= (value & 0xffff0000u) >> 16u;
    return value;
}

uint primeZSobolFastOwenFromReversed(uint value, uint seed)
{
    value ^= value * 0x3d20adea;
    value += seed;
    value *= (seed >> 16u) | 1u;
    value ^= value * 0x05526c56;
    value ^= value * 0x53a22864;
    return reversebits(value);
}

// FastOwen only consumes two 32-bit seeds; independent salted avalanches avoid
// constructing a 64-bit hash that the shader immediately splits again.
uint2 primeZSobolSampleHash(uint dimension, uint seed)
{
    return uint2(
            primeZSobolAvalanche(
                    seed ^ (dimension * 0x9e3779b9u) ^ 0xa511e9b3u),
            primeZSobolAvalanche(
                    seed ^ (dimension * 0x85ebca6bu) ^ 0x63d83595u));
}

float primeZSobolFloat(uint value)
{
    return min(
            float(value) * PRIME_ZSOBOL_UINT_SCALE,
            PRIME_ZSOBOL_ONE_MINUS_EPSILON);
}

public float2 primeDirectSample2D(
        PrimeDirectSampleBase base, uint stream)
{
    uint dimension = base.dimensionBase + stream * 2u;
    uint64_t sampleIndex = primeZSobolSampleIndex(base, dimension);
    uint2 hash = primeZSobolSampleHash(dimension + 2u, base.seed);
    uint first = primeZSobolFastOwenFromReversed(
            uint(sampleIndex), hash.x);
    uint second = primeZSobolFastOwenFromReversed(
            primeZSobolDimensionOneReversed(sampleIndex), hash.y);
    return float2(primeZSobolFloat(first), primeZSobolFloat(second));
}
