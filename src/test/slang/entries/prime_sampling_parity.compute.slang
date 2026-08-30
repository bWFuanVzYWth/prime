#include "core/prime_test_abi.slang"
import "prime_abi_types.slang";
import "math/sampling.slang";
import "contract/path.slang";
import "state/path.slang";

static const uint PRIME_SAMPLING_INPUT_WORDS = 2;
static const uint PRIME_SAMPLING_OUTPUT_WORDS = 4;

[numthreads(64, 1, 1)]
void main(uint3 dispatchThreadId : SV_DispatchThreadID)
{
    uint caseIndex;
    if (!primeTestBegin(dispatchThreadId, caseIndex)) return;
    uint inputOffset = primeTestInputOffset(caseIndex, PRIME_SAMPLING_INPUT_WORDS);
    uint4 first = primeTestInput[inputOffset];
    uint4 second = primeTestInput[inputOffset + 1];

    PrimeSampleBase sampleBase;
    sampleBase.pixel = first.xy;
    sampleBase.sampleIndex = first.z;
    sampleBase.sampleEpoch = first.w;
    sampleBase.vertexIndex = second.x;
    sampleBase.pathIndex = second.y;
    uint effect = second.z;
    uint dimension = second.w;
    PrimePreparedSampleBase prepared = primePrepareSampleBase(sampleBase);
    PathState path = {};
    path.control = primeMakePathStateControl(second.x, second.z);
    path.sampleSeed = primePathSampleSeed(
            sampleBase.pixel, sampleBase.sampleEpoch, sampleBase.pathIndex);
    PrimePreparedSampleBase compact = primePreparePathSample(
            path.sampleSeed,
            sampleBase.sampleIndex,
            sampleBase.vertexIndex);

    uint outputOffset = primeTestOutputOffset(caseIndex, PRIME_SAMPLING_OUTPUT_WORDS);
    primeTestOutput[outputOffset] = uint4(
            primeHash32(first.z),
            primeHighQualityHash(first.w),
            prepared.seed,
            primeEffectSeed(prepared, effect));
    float2 sample2D = primeSobolSample2D(prepared, effect, dimension);
    primeTestOutput[outputOffset + 1] = asuint(float4(
            primeSobolSample1D(prepared, effect, dimension),
            sample2D,
            primeHashSample1D(prepared, effect, dimension)));
    primeTestOutput[outputOffset + 2] = asuint(
            primeSobolSample4D(prepared, effect, dimension));
    uint repackedControl = primeMakePathStateControl(
            primePathBounce(path.control), primePathFlags(path.control));
    primeTestOutput[outputOffset + 3] = uint4(
            compact.seed ^ prepared.seed,
            compact.sampleIndex ^ prepared.sampleIndex,
            path.control ^ repackedControl,
            0u);
}
