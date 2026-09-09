// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

#define PRIME_COLOR_WORKING_SPACE_LINEAR_REC2020_D65 1
#define PRIME_COLOR_TEXTURE_ENCODING_SRGB 1
#define PRIME_COLOR_DISPLAY_ENCODING_SRGB 1
#define PRIME_COLOR_DISPLAY_SPACE_REC709_D65 1
#include "core/prime_test_abi.slang"

bool primeUsesAirGap() { return true; }
bool primeUsesSeamlessGlass() { return false; }

import "service/bsdf/contract/sample.slang";
import "core/numerical.slang";
import "service/bsdf/support/conversion.slang";
import "bsdf/compact/contract/flags.slang";
import "bsdf/compact/contract/sample.slang";

static const uint PRIME_BSDF_DIAGNOSTIC_INPUT_WORDS = 1u;
static const uint PRIME_BSDF_DIAGNOSTIC_WITNESS_WORDS = 2u;

[numthreads(64, 1, 1)]
void main(uint3 dispatchThreadId : SV_DispatchThreadID)
{
    uint caseIndex;
    if (!primeTestBegin(dispatchThreadId, caseIndex)) return;
    uint4 expected = primeTestInput[
            primeTestInputOffset(caseIndex, PRIME_BSDF_DIAGNOSTIC_INPUT_WORDS)];

    PrimeOpenPbrSample sampleValue;
    sampleValue.throughput.value = float3(1.0);
    sampleValue.throughput.flags = PRIME_OPENPBR_FLAG_DIFFUSE;
    sampleValue.pdf = 1.0;
    sampleValue.eta = 1.0;
    sampleValue.wo = float3(0.0, 0.0, 1.0);
    bool enabled = expected.x != 5u;
    switch (expected.x) {
        case 1u:
            sampleValue.throughput.value.x = asfloat(0x7fc00001u);
            break;
        case 2u:
            sampleValue.throughput.value.x = -1.0;
            break;
        case 3u:
            sampleValue.wo = float3(0.0);
            break;
        case 4u:
        case 5u:
            sampleValue.pdf = asfloat(0x7f800000u);
            break;
        case 6u:
            sampleValue.eta = asfloat(0x7f800000u);
            break;
    }

    PrimeNumericalObservation observation =
            primeNumericalObservationCreate(enabled);
    primeObserveNonnegative(observation, sampleValue.throughput.value);
    primeObserveNonnegative(observation, sampleValue.pdf);
    primeObserveNonnegative(observation, sampleValue.eta);
    primeObserveDirection(observation, sampleValue.wo);
    bool accepted = primeOpenPbrHasSample(sampleValue);
    uint mask = 0u;
    if (uint(accepted) != expected.y) mask |= PRIME_TEST_FAILURE_STATE;
    if (observation.flags != expected.z) mask |= PRIME_TEST_FAILURE_VALUE;
    if (observation.firstField != expected.w) mask |= PRIME_TEST_FAILURE_INDEX;

    uint outputOffset = primeTestOutputOffset(
            caseIndex, primeTestOutputWords());
    primeTestOutput[outputOffset] = uint4(mask, expected.x, caseIndex, 0u);
    if (!primeTestWitnessEnabled()) return;
    primeTestOutput[outputOffset + 1u] = uint4(
            uint(accepted), observation.flags, observation.firstField, 0u);
}
