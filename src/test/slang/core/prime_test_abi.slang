// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

#ifndef PRIME_TEST_ABI_SLANG
#define PRIME_TEST_ABI_SLANG

static const uint PRIME_TEST_WORD_BYTES = 16;
static const uint PRIME_TEST_SWEEP_WORDS = 1;

static const uint PRIME_TEST_FAILURE_NAN = 1 << 0;
static const uint PRIME_TEST_FAILURE_POSITIVE_INFINITY = 1 << 1;
static const uint PRIME_TEST_FAILURE_NEGATIVE_INFINITY = 1 << 2;
static const uint PRIME_TEST_FAILURE_RANGE = 1 << 3;
static const uint PRIME_TEST_FAILURE_UNIT_LENGTH = 1 << 4;
static const uint PRIME_TEST_FAILURE_HEMISPHERE = 1 << 5;
static const uint PRIME_TEST_FAILURE_ORTHOGONAL = 1 << 6;
static const uint PRIME_TEST_FAILURE_ROUND_TRIP = 1 << 7;
static const uint PRIME_TEST_FAILURE_IDENTITY = 1 << 8;
static const uint PRIME_TEST_FAILURE_SYMMETRY = 1 << 9;
static const uint PRIME_TEST_FAILURE_RECIPROCITY = 1 << 10;
static const uint PRIME_TEST_FAILURE_EVENT = 1 << 11;
static const uint PRIME_TEST_FAILURE_PDF = 1 << 12;
static const uint PRIME_TEST_FAILURE_VALUE = 1 << 13;
static const uint PRIME_TEST_FAILURE_STATE = 1 << 14;
static const uint PRIME_TEST_FAILURE_INDEX = 1 << 15;

[[vk::binding(0, 0)]] StructuredBuffer<uint4> primeTestInput;
[[vk::binding(1, 0)]] RWStructuredBuffer<uint4> primeTestOutput;

bool primeTestBegin(uint3 dispatchThreadId, out uint caseIndex)
{
    caseIndex = dispatchThreadId.x;
    return caseIndex < primeTestInput[0].x;
}

uint primeTestInputOffset(uint caseIndex, uint wordsPerCase)
{
    return 1 + caseIndex * wordsPerCase;
}

uint primeTestOutputOffset(uint caseIndex, uint wordsPerCase)
{
    return caseIndex * wordsPerCase;
}

uint primeTestOutputWords()
{
    return primeTestInput[0].y;
}

bool primeTestWitnessEnabled()
{
    return primeTestOutputWords() > PRIME_TEST_SWEEP_WORDS;
}

uint primeTestClassify(float value)
{
    if (isnan(value)) return PRIME_TEST_FAILURE_NAN;
    if (isinf(value)) {
        return value < 0.0
                ? PRIME_TEST_FAILURE_NEGATIVE_INFINITY
                : PRIME_TEST_FAILURE_POSITIVE_INFINITY;
    }
    return 0;
}

uint primeTestClassify(float2 value)
{
    return primeTestClassify(value.x) | primeTestClassify(value.y);
}

uint primeTestClassify(float3 value)
{
    return primeTestClassify(value.x)
            | primeTestClassify(value.y)
            | primeTestClassify(value.z);
}

uint primeTestClassify(float4 value)
{
    return primeTestClassify(value.xyz) | primeTestClassify(value.w);
}

bool primeTestClose(float first, float second, float absoluteTolerance, float relativeTolerance)
{
    if (primeTestClassify(first) != 0 || primeTestClassify(second) != 0) return false;
    float scale = max(abs(first), abs(second));
    return abs(first - second) <= absoluteTolerance + relativeTolerance * scale;
}

bool primeTestClose(float3 first, float3 second, float absoluteTolerance, float relativeTolerance)
{
    return primeTestClose(first.x, second.x, absoluteTolerance, relativeTolerance)
            && primeTestClose(first.y, second.y, absoluteTolerance, relativeTolerance)
            && primeTestClose(first.z, second.z, absoluteTolerance, relativeTolerance);
}

#endif
