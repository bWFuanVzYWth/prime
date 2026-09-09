// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

#include "core/prime_test_abi.slang"
import "math/zsobol_sampling.slang";

static const uint PRIME_ZSOBOL_INPUT_WORDS = 2u;
static const uint PRIME_ZSOBOL_OUTPUT_WORDS = 2u;

[numthreads(64, 1, 1)]
void main(uint3 dispatchThreadId : SV_DispatchThreadID)
{
    uint caseIndex;
    if (!primeTestBegin(dispatchThreadId, caseIndex)) return;
    uint inputOffset = primeTestInputOffset(caseIndex, PRIME_ZSOBOL_INPUT_WORDS);
    uint4 first = primeTestInput[inputOffset];
    uint4 second = primeTestInput[inputOffset + 1u];
    PrimeDirectSampleBase base = primeMakeDirectSampleBase(
            first.xy,
            second.xy,
            first.z,
            first.w,
            second.z,
            second.w);
    float2 primary = primeDirectSample2D(
            base, PRIME_DIRECT_SAMPLE_AREA_PRIMARY);
    float2 secondary = primeDirectSample2D(
            base, PRIME_DIRECT_SAMPLE_AREA_SECONDARY);
    float2 sun = primeDirectSample2D(base, PRIME_DIRECT_SAMPLE_SUN);
    uint outputOffset = primeTestOutputOffset(
            caseIndex, PRIME_ZSOBOL_OUTPUT_WORDS);
    primeTestOutput[outputOffset] = asuint(float4(primary, secondary));
    primeTestOutput[outputOffset + 1u] = uint4(asuint(sun), 0u, 0u);
}
