#include "core/prime_test_abi.slang"
import "prime_realtime_abi.slang";
import "prime_abi_types.slang";
import "state/realtime/path_storage.slang";
import "bsdf/compact/contract/volume.slang";

static const uint PRIME_REALTIME_STATE_INPUT_WORDS = 2;
static const uint PRIME_REALTIME_STATE_WITNESS_WORDS = 4;

void primeCheckRealtimeQueueLayout(
        uint4 control,
        out uint mask,
        out uint4 first,
        out uint4 second)
{
    uint pixelCount = control.y;
    uint queue = control.z;
    uint entry = control.w;
    uint capacity = primeRealtimeQueueCapacity(pixelCount, queue);
    uint scratchEnd = pixelCount
            * (PRIME_WAVEFRONT_AREA_RECORD_SIZE / 4u);
    uint commandWord = primeRealtimeQueueCommandWord(pixelCount, queue);
    uint indexBegin = primeRealtimeQueueCommandWord(
            pixelCount, PRIME_WAVEFRONT_QUEUE_COUNT);
    uint indexWord = primeRealtimeQueueWord(pixelCount, queue, entry);
    uint indexEnd = indexBegin
            + pixelCount * PRIME_WAVEFRONT_QUEUE_STORAGE_ENTRIES_PER_PIXEL;
    uint expectedSlot = queue == PRIME_WAVEFRONT_TRACE_QUEUE_0 ? 0u
            : queue == PRIME_WAVEFRONT_TRACE_QUEUE_1 ? 1u
            : queue == PRIME_WAVEFRONT_TRANSPARENT_TRACE_QUEUE_1 ? 2u
            : queue == PRIME_WAVEFRONT_TRANSPARENT_TRACE_QUEUE_0 ? 4u
            : queue == PRIME_WAVEFRONT_AREA_QUEUE ? 6u
            : queue == PRIME_WAVEFRONT_TRANSPARENT_RESOLVE_QUEUE ? 8u
            : 6u;
    uint expectedCapacity = pixelCount * (
            queue == PRIME_WAVEFRONT_TRACE_QUEUE_0
                    || queue == PRIME_WAVEFRONT_TRANSPARENT_TRACE_QUEUE_0
                    || queue == PRIME_WAVEFRONT_TRANSPARENT_TRACE_QUEUE_1
                    || queue == PRIME_WAVEFRONT_AREA_QUEUE
            ? PRIME_WAVEFRONT_QUEUE_ENTRIES_PER_PIXEL
            : 1u);
    bool exact = PRIME_WAVEFRONT_AREA_GUIDE_RECORD_SIZE
                    == 4u * PRIME_WAVEFRONT_QUEUE_INDEX_SIZE
            && PRIME_WAVEFRONT_AREA_RECORD_SIZE
                    == PRIME_WAVEFRONT_AREA_GUIDE_RECORD_SIZE
                            + PRIME_WAVEFRONT_PATH_SLOTS_PER_PIXEL
                                    * PRIME_WAVEFRONT_SURFACE_RECORD_SIZE
                            + max(
                                    PRIME_WAVEFRONT_DETACHED_GUIDE_RECORD_SIZE,
                                    PRIME_WAVEFRONT_PATH_SLOTS_PER_PIXEL
                                            * PRIME_WAVEFRONT_STAGED_LIGHT_RECORD_SIZE)
            && capacity == expectedCapacity
            && entry < capacity
            && commandWord == scratchEnd
                    + queue * (PRIME_WAVEFRONT_QUEUE_COMMAND_STRIDE / 4u)
            && indexWord == indexBegin + expectedSlot * pixelCount + entry
            && indexWord >= indexBegin
            && indexWord < indexEnd;
    if (queue == PRIME_WAVEFRONT_GUIDE_QUEUE) {
        exact = exact && indexWord == primeRealtimeQueueWord(
                pixelCount, PRIME_WAVEFRONT_AREA_QUEUE, entry);
    }
    mask = exact ? 0u : PRIME_TEST_FAILURE_IDENTITY;
    first = uint4(scratchEnd, commandWord, indexBegin, indexWord);
    second = uint4(indexEnd, capacity, expectedSlot, entry);
}

[numthreads(1, 1, 1)]
void main(uint3 dispatchThreadId : SV_DispatchThreadID)
{
    uint caseIndex;
    if (!primeTestBegin(dispatchThreadId, caseIndex)) return;
    uint inputOffset = primeTestInputOffset(
            caseIndex, PRIME_REALTIME_STATE_INPUT_WORDS);
    uint4 control = primeTestInput[inputOffset];
    float4 values = asfloat(primeTestInput[inputOffset + 1u]);

    if (control.x == 1u) {
        uint mask;
        uint4 first;
        uint4 second;
        primeCheckRealtimeQueueLayout(control, mask, first, second);
        uint outputWords = primeTestOutputWords();
        uint outputOffset = primeTestOutputOffset(caseIndex, outputWords);
        primeTestOutput[outputOffset] = uint4(mask, control.x, caseIndex, 0u);
        if (outputWords >= PRIME_REALTIME_STATE_WITNESS_WORDS) {
            primeTestOutput[outputOffset + 1u] = control;
            primeTestOutput[outputOffset + 2u] = first;
            primeTestOutput[outputOffset + 3u] = second;
        }
        return;
    }

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
