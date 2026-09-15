// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

#include "core/prime_test_abi.slang"
import "prime_realtime_abi.slang";
import "prime_abi_types.slang";
import "state/realtime/path_storage.slang";
import "contract/medium.slang";

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
    bool exact = PRIME_WAVEFRONT_PATH_SLOTS_PER_PIXEL == 1u
            && PRIME_WAVEFRONT_BRANCH_SLOTS_PER_PIXEL == 2u
            && PRIME_WAVEFRONT_AREA_GUIDE_RECORD_SIZE
                    == 8u * PRIME_WAVEFRONT_QUEUE_INDEX_SIZE
            && PRIME_WAVEFRONT_AREA_RECORD_SIZE
                    == PRIME_WAVEFRONT_AREA_GUIDE_RECORD_SIZE
                            + PRIME_WAVEFRONT_BRANCH_SLOTS_PER_PIXEL
                                    * PRIME_WAVEFRONT_SURFACE_RECORD_SIZE
                            + max(
                                    PRIME_WAVEFRONT_DETACHED_GUIDE_RECORD_SIZE,
                                    PRIME_WAVEFRONT_BRANCH_SLOTS_PER_PIXEL
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

    PrimeMedium medium = primeVacuumMedium();
    medium.extinction = values.yzw;
    medium.mediumId = control.y;
    uint4 first = primePackWavefrontMediumState(medium, values.x);
    uint4 second = uint4(control.y, 0u, 0u, 0u);
    PrimeMedium restored = primeUnpackWavefrontMediumState(first, control.y, 0u);
    uint mask = 0u;
    if (asuint(asfloat(first.w)) != asuint(values.x)
            || restored.mediumId != control.y
            || any(asuint(restored.extinction) != asuint(values.yzw))) {
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
