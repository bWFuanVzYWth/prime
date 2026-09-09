// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

#include "core/prime_test_abi.slang"
import "core/numerical.slang";

static const uint PRIME_NUMERICAL_TEST_INPUT_WORDS = 2;
static const uint PRIME_NUMERICAL_TEST_WITNESS_WORDS = 4;

[numthreads(64, 1, 1)]
void main(uint3 dispatchThreadId : SV_DispatchThreadID)
{
    uint caseIndex;
    if (!primeTestBegin(dispatchThreadId, caseIndex)) return;

    uint inputOffset = primeTestInputOffset(
            caseIndex, PRIME_NUMERICAL_TEST_INPUT_WORDS);
    uint4 control = primeTestInput[inputOffset];
    uint4 encoded = primeTestInput[inputOffset + 1];
    uint kind = control.x;
    uint expected = control.y;
    uint alternative = control.z;
    float4 value = asfloat(encoded);
    uint actual = 0;
    uint mask = 0;

    if (kind == 0) {
        actual = primeClassifyNonFinite(value.x);
    } else if (kind == 1) {
        actual = primeClassifyNonFinite(value.xyz);
    } else if (kind == 2) {
        actual = primeClassifyNonnegative(value.x);
    } else if (kind == 3) {
        actual = primeClassifyNonnegative(value.xyz);
    } else if (kind == 4) {
        actual = primeClassifyRadiance(value.x);
    } else if (kind == 5) {
        actual = primeClassifyRadiance(value.xyz);
    } else if (kind == 6) {
        actual = primeClassifyUnit(value.x);
    } else if (kind == 7) {
        actual = primeClassifyUnit(value.xyz);
    } else if (kind == 8) {
        actual = primeClassifyDirection(value.xyz);
    } else if (kind == 9) {
        actual = primeClassifyOptionalDirection(value.xyz);
    } else {
        mask |= PRIME_TEST_FAILURE_EVENT;
    }

    if (actual != expected && actual != alternative) {
        mask |= PRIME_TEST_FAILURE_IDENTITY;
    }

    uint outputWords = primeTestOutputWords();
    uint outputOffset = primeTestOutputOffset(caseIndex, outputWords);
    primeTestOutput[outputOffset] = uint4(mask, kind, caseIndex, 0);
    if (outputWords >= PRIME_NUMERICAL_TEST_WITNESS_WORDS) {
        primeTestOutput[outputOffset + 1] = control;
        primeTestOutput[outputOffset + 2] = encoded;
        primeTestOutput[outputOffset + 3] = uint4(
                actual, expected, alternative, 0);
    }
}
