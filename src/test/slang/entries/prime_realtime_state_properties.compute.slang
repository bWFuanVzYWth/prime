#include "core/prime_test_abi.slang"
import "prime_abi_types.slang";
import "state/realtime/path_storage.slang";

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

    PathState path;
    path.physicalOrigin = float3(0.0);
    path.previousBsdfPdf = 1.0;
    path.rayDirection = float3(0.0, 0.0, 1.0);
    path.etaScale = values.x;
    path.throughput = float3(1.0);
    path.control = control.y;
    path.sourcePrimitive = uint2(0u);
    path.sampleSeed = 0u;
    path.previousLightNormal = values.yzw;

    float4 storedValue =
            primePackWavefrontPreviousLightNormalAndEtaScale(path);
    PathState restored;
    primeUnpackWavefrontPreviousLightNormalAndEtaScale(
            storedValue, restored);

    uint mask = 0u;
    uint4 stored = asuint(storedValue);
    uint4 expected = asuint(float4(path.previousLightNormal, path.etaScale));
    if (any(stored != expected)) mask |= PRIME_TEST_FAILURE_IDENTITY;
    if (asuint(restored.etaScale) != asuint(path.etaScale)
            || any(asuint(restored.previousLightNormal)
                    != asuint(path.previousLightNormal))) {
        mask |= PRIME_TEST_FAILURE_ROUND_TRIP;
    }

    uint outputWords = primeTestOutputWords();
    uint outputOffset = primeTestOutputOffset(caseIndex, outputWords);
    primeTestOutput[outputOffset] = uint4(mask, control.x, caseIndex, 0u);
    if (outputWords >= PRIME_REALTIME_STATE_WITNESS_WORDS) {
        primeTestOutput[outputOffset + 1u] = control;
        primeTestOutput[outputOffset + 2u] = stored;
        primeTestOutput[outputOffset + 3u] = expected;
    }
}
