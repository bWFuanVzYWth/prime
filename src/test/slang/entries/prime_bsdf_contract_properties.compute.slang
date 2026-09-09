// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

#include "core/prime_test_abi.slang"
import "model/material/medium_boundary.slang";
import "service/bsdf/contract/sample.slang";
import "service/bsdf/support/sanitization.slang";

static const uint PRIME_BSDF_CONTRACT_INPUT_WORDS = 4;
static const uint PRIME_BSDF_CONTRACT_OUTPUT_WORDS = 5;

[numthreads(64, 1, 1)]
void main(uint3 dispatchThreadId : SV_DispatchThreadID)
{
    uint caseIndex;
    if (!primeTestBegin(dispatchThreadId, caseIndex)) return;

    uint inputOffset = primeTestInputOffset(caseIndex, PRIME_BSDF_CONTRACT_INPUT_WORDS);
    uint4 control = primeTestInput[inputOffset];
    float4 first = asfloat(primeTestInput[inputOffset + 1]);
    float4 second = asfloat(primeTestInput[inputOffset + 2]);
    uint kind = control.x;
    uint mask = 0;
    uint4 firstResult = uint4(0);
    uint4 secondResult = uint4(0);
    uint4 thirdResult = uint4(0);

    if (kind == 0) {
        uint adjacentControl = control.y;
        uint materialControl = primeAdjacentMaterialControl(adjacentControl);
        uint fresnelCode = primeAdjacentFresnelCode(adjacentControl);
        if (materialControl != (adjacentControl & 0xff)
                || fresnelCode != ((adjacentControl >> 8) & 0xff)) {
            mask |= PRIME_TEST_FAILURE_STATE;
        }
        firstResult = uint4(
                materialControl,
                fresnelCode,
                adjacentControl & PRIME_ADJACENT_FRESNEL_MASK,
                adjacentControl & (PRIME_ADJACENT_INTERFACE_VALID
                        | PRIME_ADJACENT_MICRO_GAP_ELIGIBLE));
    } else if (kind == 1) {
        PrimeClosureTraits traits;
        traits.measureMask = control.y;
        traits.eventMask = control.z;
        bool solidAngle = primeClosureSupports(traits, PRIME_MEASURE_SOLID_ANGLE);
        bool discrete = primeClosureSupports(traits, PRIME_MEASURE_DISCRETE);
        bool discreteOnly = primeClosureIsDiscreteOnly(traits);
        if (solidAngle != ((traits.measureMask & PRIME_MEASURE_SOLID_ANGLE) != 0)
                || discrete != ((traits.measureMask & PRIME_MEASURE_DISCRETE) != 0)
                || discreteOnly != (traits.measureMask == PRIME_MEASURE_DISCRETE)) {
            mask |= PRIME_TEST_FAILURE_EVENT;
        }
        firstResult = uint4(
                solidAngle ? 1 : 0,
                discrete ? 1 : 0,
                discreteOnly ? 1 : 0,
                traits.eventMask);
    } else if (kind == 2) {
        BsdfEvaluation evaluation;
        evaluation.response = first.xyz;
        evaluation.pdf = first.w;
        BsdfEvaluation sanitized = primeSanitizeBsdfEvaluation(evaluation);
        bool valid = primeBsdfFinite(evaluation.response)
                && primeBsdfFinite(evaluation.pdf)
                && !any(evaluation.response < float3(0.0))
                && evaluation.pdf >= 0.0;
        if (valid != (asuint(sanitized.pdf) == asuint(evaluation.pdf)
                && all(asuint(sanitized.response) == asuint(evaluation.response)))) {
            mask |= PRIME_TEST_FAILURE_STATE;
        }
        mask |= primeTestClassify(sanitized.response) | primeTestClassify(sanitized.pdf);
        firstResult = uint4(asuint(sanitized.response), asuint(sanitized.pdf));
    } else if (kind == 3) {
        BsdfSample sampleValue;
        sampleValue.direction = first.xyz;
        sampleValue.pdf = first.w;
        sampleValue.response = second.xyz;
        sampleValue.relativeEta = second.w;
        sampleValue.eventFlags = control.y;
        BsdfSample sanitized = primeSanitizeBsdfSample(sampleValue);
        bool valid = sampleValue.eventFlags != 0
                && primeBsdfDirection(sampleValue.direction)
                && primeBsdfFinite(sampleValue.response)
                && primeBsdfFinite(sampleValue.pdf)
                && primeBsdfFinite(sampleValue.relativeEta)
                && !any(sampleValue.response < float3(0.0))
                && sampleValue.pdf > 0.0
                && sampleValue.relativeEta > 0.0;
        bool outputValid = sanitized.eventFlags != 0;
        if (valid != outputValid
                || (valid && sanitized.eventFlags != sampleValue.eventFlags)) {
            mask |= PRIME_TEST_FAILURE_EVENT;
        }
        mask |= primeTestClassify(sanitized.direction)
                | primeTestClassify(sanitized.response)
                | primeTestClassify(sanitized.pdf)
                | primeTestClassify(sanitized.relativeEta);
        firstResult = uint4(asuint(sanitized.direction), sanitized.eventFlags);
        secondResult = uint4(asuint(sanitized.response), asuint(sanitized.pdf));
        thirdResult = uint4(asuint(sanitized.relativeEta), 0, 0, 0);
    } else {
        mask |= PRIME_TEST_FAILURE_EVENT;
    }

    uint outputWords = primeTestOutputWords();
    uint outputOffset = primeTestOutputOffset(caseIndex, outputWords);
    primeTestOutput[outputOffset] = uint4(mask, kind, caseIndex, 0);
    if (outputWords >= PRIME_BSDF_CONTRACT_OUTPUT_WORDS) {
        primeTestOutput[outputOffset + 1] = control;
        primeTestOutput[outputOffset + 2] = firstResult;
        primeTestOutput[outputOffset + 3] = secondResult;
        primeTestOutput[outputOffset + 4] = thirdResult;
    }
}
