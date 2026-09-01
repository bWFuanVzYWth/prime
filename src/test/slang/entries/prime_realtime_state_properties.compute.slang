#include "core/prime_test_abi.slang"
import "prime_abi_types.slang";
import "state/realtime/path_storage.slang";
import "bsdf/compact/contract/volume.slang";

static const uint PRIME_REALTIME_STATE_INPUT_WORDS = 2;
static const uint PRIME_REALTIME_STATE_WITNESS_WORDS = 4;

[numthreads(1, 1, 1)]
void main(uint3 dispatchThreadId : SV_DispatchThreadID)
{
    uint caseIndex;
    if (!primeTestBegin(dispatchThreadId, caseIndex)) return;
    uint inputOffset = primeTestInputOffset(
            caseIndex, PRIME_REALTIME_STATE_INPUT_WORDS);
    uint4 control = primeTestInput[inputOffset];
    float4 values = asfloat(primeTestInput[inputOffset + 1u]);

    PrimeOpenPbrVolumeStack stack;
    stack.values[0].extinction = values.yzw;
    stack.values[0].mediumId = control.y;
    stack.values[1].extinction = values.wzy;
    stack.values[1].mediumId = control.z;
    uint4 first;
    uint4 second;
    primePackWavefrontMediumState(stack, values.x, first, second);
    PrimeOpenPbrVolume restored0 = primeUnpackWavefrontMediumState(
            first, second, 0u, 0u);
    PrimeOpenPbrVolume restored1 = primeUnpackWavefrontMediumState(
            first, second, 1u, 0u);

    uint mask = 0u;
    uint packedIds = (control.y & 0xffffu) | (control.z & 0xffffu) << 16u;
    uint4 expectedFirst = uint4(asuint(stack.values[0].extinction), packedIds);
    uint4 expectedSecond = uint4(
            asuint(stack.values[1].extinction), asuint(values.x));
    if (any(first != expectedFirst) || any(second != expectedSecond)) {
        mask |= PRIME_TEST_FAILURE_IDENTITY;
    }
    if (asuint(primeUnpackWavefrontEtaScale(second)) != asuint(values.x)
            || restored0.mediumId != control.y
            || restored1.mediumId != control.z
            || any(asuint(restored0.extinction)
                    != asuint(stack.values[0].extinction))
            || any(asuint(restored1.extinction)
                    != asuint(stack.values[1].extinction))) {
        mask |= PRIME_TEST_FAILURE_ROUND_TRIP;
    }

    uint outputWords = primeTestOutputWords();
    uint outputOffset = primeTestOutputOffset(caseIndex, outputWords);
    primeTestOutput[outputOffset] = uint4(mask, control.x, caseIndex, 0u);
    if (outputWords >= PRIME_REALTIME_STATE_WITNESS_WORDS) {
        primeTestOutput[outputOffset + 1u] = control;
        primeTestOutput[outputOffset + 2u] = first;
        primeTestOutput[outputOffset + 3u] = second;
    }
}
