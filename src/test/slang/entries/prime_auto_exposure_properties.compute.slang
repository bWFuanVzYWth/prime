#include "core/prime_test_abi.slang"
import "service/reconstruct/auto_exposure.slang";
import "math/color_space.slang";
import "service/reconstruct/display.slang";

static const uint PRIME_AUTO_EXPOSURE_INPUT_WORDS = 2;
static const uint PRIME_AUTO_EXPOSURE_WITNESS_WORDS = 4;

[numthreads(64, 1, 1)]
void main(uint3 dispatchThreadId : SV_DispatchThreadID)
{
    uint caseIndex;
    if (!primeTestBegin(dispatchThreadId, caseIndex)) return;
    uint inputOffset =
            primeTestInputOffset(caseIndex, PRIME_AUTO_EXPOSURE_INPUT_WORDS);
    uint4 control = primeTestInput[inputOffset];
    float4 values = asfloat(primeTestInput[inputOffset + 1]);
    uint kind = control.x;
    uint mask = 0;
    float2 actual = float2(0.0);
    float2 expected = float2(0.0);

    if (kind == 0) {
        actual.x = primeAutoExposureTargetEv(
                values.x, values.y, values.z, values.w);
        float range = values.z - values.y;
        float keyBiasEv = range > 0.0
                ? 2.0 * (2.0 * values.x - values.y - values.z) / max(range, 2.0)
                : 0.0;
        expected.x = values.w * clamp(
                log2(0.16) - values.x + keyBiasEv,
                -16.0,
                16.0);
        if (actual.x < -16.0 || actual.x > 16.0) {
            mask |= PRIME_TEST_FAILURE_RANGE;
        }
        if (!primeTestClose(actual.x, expected.x, 1.0e-6, 1.0e-6)) {
            mask |= PRIME_TEST_FAILURE_IDENTITY;
        }
    } else if (kind == 1) {
        float t90 = values.y < values.x ? 0.5 : 2.0;
        actual.x = primeAutoExposureAdapt(values.x, values.y, t90);
        expected.x = values.y + (values.x - values.y) * 0.1;
        if (!primeTestClose(actual.x, expected.x, 2.0e-6, 2.0e-6)) {
            mask |= PRIME_TEST_FAILURE_STATE;
        }
    } else if (kind == 2) {
        actual.x = primeAutoExposureAlbedoScale(float3(values.x), values.y);
        float fullScale = 0.18 / max(clamp(values.x, 0.0, 1.0), 0.02);
        expected.x = lerp(1.0, fullScale, clamp(values.y, 0.0, 1.0));
        if (actual.x < 0.179999 || actual.x > 9.00001) {
            mask |= PRIME_TEST_FAILURE_RANGE;
        }
        if (!primeTestClose(actual.x, expected.x, 2.0e-6, 2.0e-6)) {
            mask |= PRIME_TEST_FAILURE_IDENTITY;
        }
    } else if (kind == 3) {
        uint materialClass = uint(clamp(round(values.x), 0.0, 3.0));
        actual.x = primeAutoExposureMaterialConfidence(materialClass, values.y);
        bool diffuseSurface = materialClass == 0 || materialClass == 3;
        expected.x = diffuseSurface && values.y >= 0.0 ? 1.0 : 0.0;
        if (!primeTestClose(actual.x, expected.x, 1.0e-6, 1.0e-6)) {
            mask |= PRIME_TEST_FAILURE_IDENTITY;
        }
    } else if (kind == 4) {
        actual.x = primeAutoExposureSceneKeyBiasEv(values.x, values.y, values.z);
        float range = values.z - values.y;
        expected.x = range > 0.0
                ? 2.0 * (2.0 * values.x - values.y - values.z) / max(range, 2.0)
                : 0.0;
        if (actual.x < -2.00001 || actual.x > 2.00001) {
            mask |= PRIME_TEST_FAILURE_RANGE;
        }
        if (!primeTestClose(actual.x, expected.x, 1.0e-6, 1.0e-6)) {
            mask |= PRIME_TEST_FAILURE_IDENTITY;
        }
    } else if (kind == 5) {
        float exposure = exp2(values.w);
        actual.x = primeAutoExposureBrightness(values.xyz);
        actual.y = primeAutoExposureBrightness(values.xyz * exposure);
        expected.x = actual.x * exposure;
        expected.y = dot(
                max(values.xyz, float3(0.0)),
                float3(0.2627, 0.6780, 0.0593));
        if (actual.x < 0.0 || actual.y < 0.0) {
            mask |= PRIME_TEST_FAILURE_RANGE;
        }
        if (!primeTestClose(actual.x, expected.y, 2.0e-6, 2.0e-6)
                || !primeTestClose(actual.y, expected.x, 2.0e-5, 2.0e-6)) {
            mask |= PRIME_TEST_FAILURE_IDENTITY;
        }
    } else if (kind == 6) {
        PrimeReinhardAgxParameters parameters =
                primeDefaultReinhardAgxParameters();
        if (parameters.gamutExpansion != 0.04
                || parameters.compressionStart != 0.18 || parameters.outputPeak != 1.0
                || parameters.hueRetention != 0.5) {
            mask |= PRIME_TEST_FAILURE_IDENTITY;
        }
        parameters.outputPeak = values.w;
        parameters.shoulderCoefficient = primeReinhardShoulderCoefficient(values.w);
        float value = max(values.x, 0.0);
        actual.x = primeReinhardCurveComponent(value, parameters);
        actual.y = parameters.shoulderCoefficient;
        // CPU-derived output and coefficient independently check the production Slang curve.
        expected = values.yz;
        // Rounding 1 + distance before log2 costs at most one f32 ULP times output room.
        // Keep the tighter bound in the cancellation-safe series at the join.
        float extent = parameters.outputPeak - parameters.compressionStart;
        float absoluteTolerance = 3.0e-6;
        if ((value - parameters.compressionStart) / extent >= 0.001)
            absoluteTolerance += 1.2e-7 * extent;
        if (!primeTestClose(actual.x, expected.x, absoluteTolerance, 3.0e-6)
                || !primeTestClose(actual.y, expected.y, 3.0e-6, 3.0e-6)) {
            mask |= PRIME_TEST_FAILURE_IDENTITY;
        }
        float limit = 0.18 + (values.w - 0.18) * pow(parameters.shoulderCoefficient, -1.0 / 5.0);
        if (actual.x < -1.0e-6 || actual.x > limit * (1.0 + 3.0e-6)) {
            mask |= PRIME_TEST_FAILURE_RANGE;
        }
    } else if (kind == 7) {
        PrimeReinhardAgxParameters parameters =
                primeDefaultReinhardAgxParameters();
        float3 rgb = max(values.xyz, float3(0.0));
        float3 expanded = primeReinhardToExpandedGamut(rgb, parameters);
        float3 restored = primeReinhardFromExpandedGamut(expanded, parameters);
        float3 rgbRoundTrip = primeReinhardHsvToRgb(
                primeReinhardRgbToHsv(rgb));
        float3 gray = max(values.w, 0.0).xxx;
        float3 grayExpanded = primeReinhardToExpandedGamut(gray, parameters);

        float3 mappedLinear = primeReinhardFromExpandedGamut(
                primeReinhardCurve(expanded, parameters),
                parameters);
        float3 mappedDisplay = primeReinhardEncodeSrgb(mappedLinear);
        float3 repaired = primeReinhardProtectHsvHue(
                rgb, mappedDisplay, parameters);
        float3 originalHsv = primeReinhardRgbToHsv(
                primeReinhardEncodeSrgb(rgb));
        float3 mappedHsv = primeReinhardRgbToHsv(mappedDisplay);
        float3 repairedHsv = primeReinhardRgbToHsv(repaired);
        float hueError = 0.0;
        float svError = 0.0;
        if (originalHsv.y > 1.0e-7 && mappedHsv.y > 1.0e-7) {
            float hueOffset = originalHsv.x - mappedHsv.x;
            hueOffset -= floor(hueOffset + 0.5);
            float expectedHue = frac(mappedHsv.x + 0.5 * hueOffset);
            float repairedOffset = repairedHsv.x - expectedHue;
            repairedOffset -= floor(repairedOffset + 0.5);
            // Hue is ill-conditioned near neutral; bound its RGB displacement by 6 * chroma.
            hueError = abs(repairedOffset) * 6.0 * mappedHsv.y * mappedHsv.z;
            svError = max(
                    abs(repairedHsv.y - mappedHsv.y),
                    abs(repairedHsv.z - mappedHsv.z));
        } else {
            float3 repairError = abs(repaired - mappedDisplay);
            hueError = max(repairError.x, max(repairError.y, repairError.z));
        }

        float3 inverseError = abs(restored - rgb);
        float3 hsvError = abs(rgbRoundTrip - rgb);
        float3 grayError = abs(grayExpanded - gray);
        actual.x = max(
                max(inverseError.x, max(inverseError.y, inverseError.z)),
                max(
                        max(hsvError.x, max(hsvError.y, hsvError.z)),
                        max(grayError.x, max(grayError.y, grayError.z))));
        actual.y = max(hueError, svError);
        expected = float2(0.0);
        if (!primeTestClose(actual.x, 0.0, 5.0e-6, 5.0e-6)
                || !primeTestClose(actual.y, 0.0, 8.0e-6, 8.0e-6)) {
            mask |= PRIME_TEST_FAILURE_IDENTITY;
        }
        float originalSpan = max(rgb.x, max(rgb.y, rgb.z))
                - min(rgb.x, min(rgb.y, rgb.z));
        float expandedSpan = max(expanded.x, max(expanded.y, expanded.z))
                - min(expanded.x, min(expanded.y, expanded.z));
        if (expandedSpan > originalSpan + 2.0e-6) {
            mask |= PRIME_TEST_FAILURE_RANGE;
        }
        mask |= primeTestClassify(expanded);
        mask |= primeTestClassify(restored);
        mask |= primeTestClassify(repaired);
    } else if (kind == 8) {
        float3 hdrRec2020 = max(values.xyz, float3(0.0));
        float outputPeak = clamp(values.w, 1.0, 10000.0);
        float shoulderCoefficient = primeReinhardShoulderCoefficient(outputPeak);
        PrimeReinhardAgxParameters parameters =
                primeDefaultReinhardAgxParameters();
        parameters.outputPeak = outputPeak;
        parameters.shoulderCoefficient = shoulderCoefficient;
        float encodedPeak = primeReinhardEncodeSrgb(outputPeak.xxx).x;
        float3 expectedReinhard = clamp(
                primeReinhardAgxTonemap(
                        max(
                                primeLinearRec2020ToLinearBt709(hdrRec2020),
                                float3(0.0)),
                        parameters),
                float3(0.0),
                encodedPeak.xxx);
        float3 output = primeDisplayTransformToSrgb(
                hdrRec2020, 1.0, outputPeak, shoulderCoefficient);
        float3 error = abs(output - expectedReinhard);
        if (outputPeak == 1.0) {
            float3 sdrError = abs(output - primeDisplayTransformToSrgb(hdrRec2020, 1.0));
            error = max(error, sdrError);
        }
        actual = float2(
                max(error.x, max(error.y, error.z)),
                max(output.x, max(output.y, output.z)));
        expected = float2(0.0, actual.y);
        if (!primeTestClose(actual.x, 0.0, 1.0e-7, 1.0e-7)) {
            mask |= PRIME_TEST_FAILURE_IDENTITY;
        }
        if (any(output < float3(0.0)) || any(output > encodedPeak.xxx)) {
            mask |= PRIME_TEST_FAILURE_RANGE;
        }
        mask |= primeTestClassify(output);
    } else {
        mask |= PRIME_TEST_FAILURE_EVENT;
    }
    mask |= primeTestClassify(actual);

    uint outputWords = primeTestOutputWords();
    uint outputOffset = primeTestOutputOffset(caseIndex, outputWords);
    primeTestOutput[outputOffset] = uint4(mask, kind, caseIndex, 0);
    if (outputWords >= PRIME_AUTO_EXPOSURE_WITNESS_WORDS) {
        primeTestOutput[outputOffset + 1] = control;
        primeTestOutput[outputOffset + 2] = asuint(values);
        primeTestOutput[outputOffset + 3] = asuint(float4(actual, expected));
    }
}
