
#include "core/prime_test_abi.slang"
import "service/reconstruct/nrd/common.slang";
import "model/material/defaults.slang";
import "service/reconstruct/fsr_input.slang";
import "service/reconstruct/streamline_input.slang";
import "service/reconstruct/control.slang";
import "prime_fsr_contract.slang";

static const uint PRIME_FSR_INPUT_WORDS = 3u;
static const uint PRIME_FSR_WITNESS_WORDS = 5u;

[numthreads(64, 1, 1)]
void main(uint3 dispatchThreadId : SV_DispatchThreadID) {
    uint caseIndex;
    if (!primeTestBegin(dispatchThreadId, caseIndex)) return;
    uint inputOffset = primeTestInputOffset(caseIndex, PRIME_FSR_INPUT_WORDS);
    uint4 control = primeTestInput[inputOffset];
    float4 values = asfloat(primeTestInput[inputOffset + 1u]);
    float4 second = asfloat(primeTestInput[inputOffset + 2u]);
    uint kind = control.x;
    uint mask = 0u;
    float4 actual = float4(0.0);
    float4 expected = float4(0.0);

    if (kind == 0u) {
        uint flags = control.y;
        PrimeFsrMasks masks = primeFsrMasks(flags);
        actual.xy = float2(masks.reactive, masks.transparencyAndComposition);
        expected.xy = primeMaterialIsTransmissive(flags)
                ? float2(0.0, 1.0)
                : float2(
                        0.0,
                        (flags & PRIME_MATERIAL_ANIMATED) != 0u
                                ? 0.75
                                : 0.0);
        if (!primeTestClose(actual.x, expected.x, 0.0, 0.0)
                || !primeTestClose(actual.y, expected.y, 0.0, 0.0)) {
            mask |= PRIME_TEST_FAILURE_STATE;
        }
    } else if (kind == 1u) {
        actual.x = primeStreamlineReversedDepth(values.x);
        expected.x = primeNrdIsFinite(values.x) && values.x > 0.0
                ? clamp(PRIME_FSR_NEAR_PLANE / values.x, 0.0, 1.0)
                : 0.0;
        mask |= primeTestClassify(actual.x);
        if (actual.x < 0.0 || actual.x > 1.0) {
            mask |= PRIME_TEST_FAILURE_RANGE;
        }
        if (!primeTestClose(actual.x, expected.x, 0.0, 0.0)) {
            mask |= PRIME_TEST_FAILURE_IDENTITY;
        }
    } else if (kind == 2u) {
        actual.xy = primeFsrNormalizedMotion(values.xy, values.zw);
        float2 raw = values.xy - values.zw;
        expected.xy = primeNrdIsFinite(raw)
                ? clamp(raw, float2(-1.0), float2(1.0))
                : float2(0.0);
        mask |= primeTestClassify(actual.xy);
        if (any(lessThan(actual.xy, float2(-1.0)))
                || any(greaterThan(actual.xy, float2(1.0)))) {
            mask |= PRIME_TEST_FAILURE_RANGE;
        }
        if (!primeTestClose(actual.x, expected.x, 0.0, 0.0)
                || !primeTestClose(actual.y, expected.y, 0.0, 0.0)) {
            mask |= PRIME_TEST_FAILURE_IDENTITY;
        }
    } else if (kind == 3u) {
        actual.x = primeFsrSurfaceViewZ(values.xyz, second.xyz);
        float raw = dot(values.xyz, second.xyz);
        expected.x = primeNrdIsFinite(raw) && raw > 0.0
                ? min(raw, PRIME_NRD_FP16_MAX)
                : PRIME_NRD_FP16_MAX;
        mask |= primeTestClassify(actual.x);
        if (actual.x <= 0.0 || actual.x > PRIME_NRD_FP16_MAX) {
            mask |= PRIME_TEST_FAILURE_RANGE;
        }
        if (!primeTestClose(actual.x, expected.x, 0.0, 0.0)) {
            mask |= PRIME_TEST_FAILURE_IDENTITY;
        }
    } else if (kind == 4u) {
        actual.x = primeFsrSanitizeMask(values.x);
        expected.x = primeNrdSanitizeUnit(values.x, 0.0);
        mask |= primeTestClassify(actual.x);
        if (actual.x < 0.0 || actual.x > 1.0) {
            mask |= PRIME_TEST_FAILURE_RANGE;
        }
        if (!primeTestClose(actual.x, expected.x, 0.0, 0.0)) {
            mask |= PRIME_TEST_FAILURE_IDENTITY;
        }
    } else if (kind == 5u) {
        actual.xy = primeStreamlineCanonicalMotion(values.xy);
        expected.xy = values.xy;
        if (!primeTestClose(actual.x, expected.x, 0.0, 0.0)
                || !primeTestClose(actual.y, expected.y, 0.0, 0.0)) {
            mask |= PRIME_TEST_FAILURE_IDENTITY;
        }
    } else if (kind == 6u) {
        float3 position;
        bool valid = primeStreamlineVisiblePosition(
                values.xyz, second.xyz, values.w, position);
        float denominator = dot(values.xyz, second.xyz);
        bool expectedValid = primeNrdIsFinite(values.xyz)
                && primeNrdIsFinite(second.xyz)
                && primeNrdIsFinite(values.w)
                && values.w > 0.0
                && primeNrdIsFinite(denominator)
                && denominator > 1.0e-6;
        float3 expectedPosition = expectedValid
                ? values.xyz * (values.w / denominator)
                : float3(0.0);
        expectedValid = expectedValid && primeNrdIsFinite(expectedPosition);
        if (!expectedValid) expectedPosition = float3(0.0);
        actual = float4(position, valid ? 1.0 : 0.0);
        expected = float4(expectedPosition, expectedValid ? 1.0 : 0.0);
        if (valid != expectedValid
                || !primeTestClose(actual.xyz, expected.xyz, 2.0e-6, 2.0e-6)
                || !primeTestClose(actual.w, expected.w, 0.0, 0.0)) {
            mask |= PRIME_TEST_FAILURE_IDENTITY;
        }
    } else if (kind == 7u) {
        float2 previousUv;
        bool valid = primeStreamlinePreviousUv(values, previousUv);
        bool expectedValid = values.w > 1.0e-6 && primeNrdIsFinite(values);
        float2 expectedUv = expectedValid
                ? values.xy / values.w * float2(0.5, -0.5) + float2(0.5)
                : float2(0.0);
        expectedValid = expectedValid && primeNrdIsFinite(expectedUv);
        actual = float4(previousUv, valid ? 1.0 : 0.0, 0.0);
        expected = float4(expectedUv, expectedValid ? 1.0 : 0.0, 0.0);
        if (valid != expectedValid
                || !primeTestClose(actual.xyz, expected.xyz, 2.0e-6, 2.0e-6)
                || !primeTestClose(actual.w, expected.w, 0.0, 0.0)) {
            mask |= PRIME_TEST_FAILURE_IDENTITY;
        }
    } else if (kind == 8u) {
        uint historyMask = control.z;
        bool localHistoryValid = control.w != 0u;
        bool valid = primeStreamlineHistoryValid(
                control.y, historyMask, localHistoryValid);
        bool expectedValid = localHistoryValid
                && (control.y & historyMask) != 0u;
        actual.x = valid ? 1.0 : 0.0;
        expected.x = expectedValid ? 1.0 : 0.0;
        if (valid != expectedValid) {
            mask |= PRIME_TEST_FAILURE_IDENTITY;
        }
    } else if (kind == 9u) {
        bool transmissiveHistoryExact = control.z != 0u;
        bool objectMotion = primeReconstructionObjectMotion(control.y);
        bool unsupportedTransmissive = objectMotion
                && primeReconstructionMaterialClass(control.y)
                        == PRIME_RECONSTRUCTION_MATERIAL_CLASS_TRANSMISSIVE
                && !transmissiveHistoryExact;
        float3 targetPosition;
        bool valid = primeStreamlineVisibleHistoryPosition(
                objectMotion,
                !unsupportedTransmissive,
                values.xyz,
                second.xyz,
                targetPosition);
        float3 expectedPosition = objectMotion ? second.xyz : values.xyz;
        bool expectedValid = !unsupportedTransmissive
                && primeNrdIsFinite(expectedPosition);
        actual = float4(targetPosition, valid ? 1.0 : 0.0);
        expected = float4(
                unsupportedTransmissive ? values.xyz : expectedPosition,
                expectedValid ? 1.0 : 0.0);
        if (valid != expectedValid
                || any(asuint(actual) != asuint(expected))) {
            mask |= PRIME_TEST_FAILURE_IDENTITY;
        }
    } else {
        mask |= PRIME_TEST_FAILURE_EVENT;
    }

    uint outputWords = primeTestOutputWords();
    uint outputOffset = primeTestOutputOffset(caseIndex, outputWords);
    primeTestOutput[outputOffset] = uint4(mask, kind, caseIndex, 0u);
    if (outputWords >= PRIME_FSR_WITNESS_WORDS) {
        primeTestOutput[outputOffset + 1u] = control;
        primeTestOutput[outputOffset + 2u] = asuint(values);
        primeTestOutput[outputOffset + 3u] = asuint(second);
        primeTestOutput[outputOffset + 4u] =
                asuint(float4(actual.xy, expected.xy));
    }
}
