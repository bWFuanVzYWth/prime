
#include "core/prime_test_abi.slang"
import "core/numerical.slang";
import "math/direction.slang";
import "service/reconstruct/nrd/common.slang";
import "service/reconstruct/control.slang";
import "model/material/defaults.slang";
import "service/reconstruct/nrd/sh.slang";
import "contract/path.slang";

import "contract/realtime/resolved.slang";
import "service/reconstruct/ordinary.slang";
import "service/reconstruct/resolved.slang";

static const uint PRIME_NRD_INPUT_WORDS = 4u;
static const uint PRIME_NRD_WITNESS_WORDS = 9u;

void primeNrdCheckFinite(inout uint mask, float value) {
    mask |= primeTestClassify(value);
}

void primeNrdCheckFinite(inout uint mask, float3 value) {
    mask |= primeTestClassify(value);
}

void primeNrdCheckFinite(inout uint mask, float4 value) {
    mask |= primeTestClassify(value);
}

void primeNrdCheckUnit(inout uint mask, float value) {
    primeNrdCheckFinite(mask, value);
    if (value < 0.0 || value > 1.0) mask |= PRIME_TEST_FAILURE_RANGE;
}

void primeNrdCheckUnit(inout uint mask, float3 value) {
    primeNrdCheckFinite(mask, value);
    if (any(lessThan(value, float3(0.0))) || any(greaterThan(value, float3(1.0)))) {
        mask |= PRIME_TEST_FAILURE_RANGE;
    }
}

void primeNrdCheckUnit(inout uint mask, float4 value) {
    mask |= primeTestClassify(value);
    if (any(lessThan(value, float4(0.0))) || any(greaterThan(value, float4(1.0)))) {
        mask |= PRIME_TEST_FAILURE_RANGE;
    }
}

[numthreads(64, 1, 1)]
void main(uint3 dispatchThreadId : SV_DispatchThreadID) {
    uint caseIndex;
    if (!primeTestBegin(dispatchThreadId, caseIndex)) return;
    uint inputOffset = primeTestInputOffset(caseIndex, PRIME_NRD_INPUT_WORDS);
    uint4 control = primeTestInput[inputOffset];
    float4 first = asfloat(primeTestInput[inputOffset + 1u]);
    float4 second = asfloat(primeTestInput[inputOffset + 2u]);
    float4 third = asfloat(primeTestInput[inputOffset + 3u]);
    uint kind = control.x;
    uint mask = 0u;
    for (uint materialClass = 0u; materialClass < 4u; ++materialClass) {
        uint packedControl = primePackReconstructionControl(
                materialClass, true, true);
        uint withMotion = primeSetReconstructionMotionValid(packedControl, true);
        uint withoutMotion = primeSetReconstructionMotionValid(withMotion, false);
        if (primeReconstructionMaterialClass(packedControl) != materialClass
                || !primeReconstructionSurfaceValid(packedControl)
                || !primeReconstructionHistoryValid(packedControl)
                || primeReconstructionMotionValid(packedControl)
                || !primeReconstructionMotionValid(withMotion)
                || primeReconstructionMotionValid(withoutMotion)
                || (withMotion & ~PRIME_RECONSTRUCTION_CONTROL_VALID_MASK) != 0u) {
            mask |= PRIME_TEST_FAILURE_IDENTITY;
        }
    }
    PrimeResolvedSample reflectionOnly =
            primeResolveOrdinarySample(primeEmptyOrdinarySample());
    reflectionOnly.transparentPrimary = true;
    reflectionOnly.transparentReflectionOnly = true;
    reflectionOnly.reflectionGuides.primaryHitKind = PRIME_HIT_SURFACE;
    if (!primeRrUsesReflectionPrimaryGuide(reflectionOnly, 0.0)
            || primeRrUsesReflectionPrimaryGuide(reflectionOnly, 1.0)
            || primeRrUsesReflectionPrimaryGuide(reflectionOnly, -1.0)) {
        mask |= PRIME_TEST_FAILURE_STATE;
    }
    reflectionOnly.guides.primaryDistance = 3.0;
    reflectionOnly.transmissionGuides.primaryDistance = -1.0;
    reflectionOnly.reflectionGuides.primaryDistance = 7.0;
    if (primeSelectReconstructionPrimaryGuides(
                    reflectionOnly, true, false, false).primaryDistance != 3.0
            || primeSelectReconstructionPrimaryGuides(
                    reflectionOnly, false, false, true).primaryDistance != 7.0) {
        mask |= PRIME_TEST_FAILURE_STATE;
    }
    reflectionOnly.transparentReflectionOnly = false;
    if (primeRrUsesReflectionPrimaryGuide(reflectionOnly, 0.0)) {
        mask |= PRIME_TEST_FAILURE_STATE;
    }
    if (primeSelectReconstructionPrimaryGuides(
                    reflectionOnly, true, false, false).primaryDistance != -1.0) {
        mask |= PRIME_TEST_FAILURE_STATE;
    }
    PrimeResolvedSample capturedReflection =
            primeResolveOrdinarySample(primeEmptyOrdinarySample());
    capturedReflection.reflectionGuides.primaryPosition = float3(2.0, 3.0, 4.0);
    float4 unavailableReflection =
            primeCapturedReflectionPreviousVirtualPosition(capturedReflection, false);
    float4 finiteReflection =
            primeCapturedReflectionPreviousVirtualPosition(capturedReflection, true);
    capturedReflection.reflectionGuides.primaryHasMotion = true;
    capturedReflection.reflectionGuides.primaryPreviousPosition = float3(5.0, 6.0, 7.0);
    float4 movingReflection =
            primeCapturedReflectionPreviousVirtualPosition(capturedReflection, true);
    capturedReflection.reflectionDirectionalGuide = true;
    capturedReflection.reflectionGuides.primaryHasMotion = false;
    capturedReflection.reflectionGuides.primaryPosition = float3(0.0, 0.0, 2.0);
    float4 directionalReflection =
            primeCapturedReflectionPreviousVirtualPosition(capturedReflection, false);
    capturedReflection.reflectionGuides.primaryPosition = float3(0.0);
    float4 invalidDirection =
            primeCapturedReflectionPreviousVirtualPosition(capturedReflection, false);
    if (!all(unavailableReflection == float4(0.0, 0.0, 0.0, -1.0))
            || !all(finiteReflection == float4(2.0, 3.0, 4.0, 0.0))
            || !all(movingReflection == float4(5.0, 6.0, 7.0, 0.0))
            || !all(directionalReflection == float4(0.0, 0.0, 1.0, 1.0))
            || !all(invalidDirection == float4(0.0, 0.0, 0.0, -1.0))) {
        mask |= PRIME_TEST_FAILURE_STATE;
    }
    float4 result0 = float4(0.0);
    float4 result1 = float4(0.0);
    float4 result2 = float4(0.0);
    float4 result3 = float4(0.0);

    if (kind == 0u) {
        float3 radiance = primeNrdSanitizeRadiance(first.xyz);
        float3 albedo = primeNrdSanitizeAlbedo(second.xyz);
        float3 motion = primeNrdSanitizeMotion(third.xyz);
        float hitDistance = primeNrdSanitizeHitDistance(first.w);
        float primaryDistance =
                primeNrdSanitizePrimaryDistance(second.w, control.y != 0u);
        primeNrdCheckFinite(mask, radiance);
        primeNrdCheckUnit(mask, albedo);
        primeNrdCheckFinite(mask, motion);
        primeNrdCheckFinite(mask, hitDistance);
        primeNrdCheckFinite(mask, primaryDistance);
        if (any(lessThan(radiance, float3(0.0)))
                || any(greaterThan(radiance, float3(PRIME_NRD_FP16_MAX)))
                || any(lessThan(motion, float3(-PRIME_NRD_FP16_MAX)))
                || any(greaterThan(motion, float3(PRIME_NRD_FP16_MAX)))
                || hitDistance < 0.0
                || hitDistance > PRIME_NRD_FP16_MAX
                || primaryDistance < -1.0
                || primaryDistance > PRIME_NRD_MAX_SURFACE_DISTANCE) {
            mask |= PRIME_TEST_FAILURE_RANGE;
        }
        result0 = float4(radiance, hitDistance);
        result1 = float4(albedo, primaryDistance);
        result2 = float4(motion, 0.0);
    } else if (kind == 1u) {
        float3 encoded = primeNrdLinearToYCoCg(first.xyz);
        float3 decoded = primeNrdYCoCgToLinear(encoded);
        primeNrdCheckFinite(mask, encoded);
        primeNrdCheckFinite(mask, decoded);
        float transformTolerance =
                2.0e-6 + 1.0e-6 * max(first.x, max(first.y, first.z));
        if (any(greaterThan(abs(first.xyz - decoded), float3(transformTolerance)))) {
            mask |= PRIME_TEST_FAILURE_ROUND_TRIP;
        }
        if (!primeTestClose(encoded.x, primeNrdY(first.xyz), 0.0, 0.0)) {
            mask |= PRIME_TEST_FAILURE_IDENTITY;
        }
        result0 = float4(encoded, 0.0);
        result1 = float4(decoded, 0.0);
    } else if (kind == 2u) {
        float4 stored = primeNrdDirectNormalRoughness(first.xyz, first.w);
        float3 normal;
        float roughness;
        primeNrdReadNormalRoughness(stored, normal, roughness);
        primeNrdCheckFinite(mask, stored);
        primeNrdCheckUnit(mask, stored.w);
        primeNrdCheckFinite(mask, normal);
        primeNrdCheckUnit(mask, roughness);
        float lengthSquared = dot(normal, normal);
        if (!primeTestClose(lengthSquared, 1.0, 2.0e-6, 2.0e-6)) {
            mask |= PRIME_TEST_FAILURE_UNIT_LENGTH;
        }
        float3 expectedNormal = primeNrdSafeNormalize(first.xyz, float3(0.0, 0.0, 1.0));
        float expectedRoughness = primeNrdSanitizeUnit(
                first.w, PRIME_DEFAULT_REFERENCE_LINEAR_ROUGHNESS);
        if (!primeTestClose(stored.xyz, expectedNormal, 0.0, 0.0)
                || !primeTestClose(stored.w, expectedRoughness, 0.0, 0.0)) {
            mask |= PRIME_TEST_FAILURE_IDENTITY;
        }
        if (!primeTestClose(normal, expectedNormal, 2.0e-6, 2.0e-6)) {
            mask |= PRIME_TEST_FAILURE_ROUND_TRIP;
        }
        result0 = stored;
        result1 = float4(normal, roughness);
    } else if (kind == 3u) {
        float normalized =
                primeNrdNormalizedHitDistance(first.x, first.y, first.z);
        float farther = primeNrdNormalizedHitDistance(
                max(first.x, 0.0) + abs(second.x), first.y, first.z);
        float4 packed = primeNrdPackRadianceAndHitDistance(second.yzw, normalized);
        float safeHitDistance = primeNrdSanitizeHitDistance(first.x);
        float safeViewZ = primeNrdIsFinite(first.y)
                ? abs(first.y) : PRIME_NRD_FP16_MAX;
        float safeRoughness = primeNrdSanitizeUnit(
                first.z, PRIME_DEFAULT_REFERENCE_LINEAR_ROUGHNESS);
        float trustedNormalized = primeNrdNormalizedSanitizedHitDistance(
                safeHitDistance, safeViewZ, safeRoughness);
        float4 trustedPacked = primeNrdPackSanitizedRadianceAndHitDistance(
                primeNrdSanitizeRadiance(second.yzw), normalized);
        primeNrdCheckUnit(mask, normalized);
        primeNrdCheckUnit(mask, farther);
        primeNrdCheckFinite(mask, packed);
        if (!primeTestClose(normalized, trustedNormalized, 0.0, 0.0)
                || any(notEqual(packed, trustedPacked))
                || (primeNrdIsFinite(first.x) && first.x >= 0.0
                        && farther < normalized)) {
            mask |= PRIME_TEST_FAILURE_IDENTITY;
        }
        result0 = float4(normalized, farther, 0.0, 0.0);
        result1 = packed;
    } else if (kind == 4u) {
        float3 safeRadiance = primeNrdSanitizeRadiance(first.xyz);
        float3 safeGuide = primeNrdSanitizeAlbedo(second.xyz);
        float3 demodulated = primeNrdDemodulate(first.xyz, second.xyz);
        float3 trustedDemodulated =
                primeNrdDemodulateSanitized(safeRadiance, safeGuide);
        float3 remodulated = demodulated * safeGuide;
        primeNrdCheckFinite(mask, demodulated);
        primeNrdCheckFinite(mask, remodulated);
        float3 expected = float3(
                safeGuide.x > 0.0 ? safeRadiance.x : 0.0,
                safeGuide.y > 0.0 ? safeRadiance.y : 0.0,
                safeGuide.z > 0.0 ? safeRadiance.z : 0.0);
        bvec3 unclamped = lessThanEqual(
                safeRadiance, float3(PRIME_NRD_FP16_MAX) * safeGuide);
        if (any(lessThan(demodulated, float3(0.0)))
                || any(greaterThan(demodulated, float3(PRIME_NRD_FP16_MAX)))
                || !primeTestClose(demodulated, trustedDemodulated, 0.0, 0.0)
                || any(greaterThan(remodulated, expected * (1.0 + 2.0e-6) + float3(2.0e-6)))
                || (unclamped.x && !primeTestClose(remodulated.x, expected.x, 2.0e-5, 2.0e-5))
                || (unclamped.y && !primeTestClose(remodulated.y, expected.y, 2.0e-5, 2.0e-5))
                || (unclamped.z && !primeTestClose(remodulated.z, expected.z, 2.0e-5, 2.0e-5))) {
            mask |= PRIME_TEST_FAILURE_IDENTITY;
        }
        result0 = float4(demodulated, 0.0);
        result1 = float4(remodulated, 0.0);
    } else if (kind == 5u) {
        float3 a = first.xyz;
        float3 b = second.xyz;
        float3 c = third.xyz;
        float limit = max(first.w, 0.0);
        float scale = primeNrdClampRadianceTriple(a, b, c, limit);
        float3 pairA = first.xyz;
        float3 pairB = second.xyz;
        float3 trustedPairA = float3(
                primeNrdSanitizeNonnegative(pairA.x),
                primeNrdSanitizeNonnegative(pairA.y),
                primeNrdSanitizeNonnegative(pairA.z));
        float3 trustedPairB = float3(
                primeNrdSanitizeNonnegative(pairB.x),
                primeNrdSanitizeNonnegative(pairB.y),
                primeNrdSanitizeNonnegative(pairB.z));
        float pairScale = primeNrdClampRadiancePair(pairA, pairB, limit);
        float trustedPairScale = primeNrdClampSanitizedRadiancePair(
                trustedPairA, trustedPairB, limit);
        primeNrdCheckUnit(mask, scale);
        primeNrdCheckFinite(mask, a);
        primeNrdCheckFinite(mask, b);
        primeNrdCheckFinite(mask, c);
        float3 sum = a + b + c;
        if (any(lessThan(a, float3(0.0)))
                || any(lessThan(b, float3(0.0)))
                || any(lessThan(c, float3(0.0)))
                || !primeTestClose(pairScale, trustedPairScale, 0.0, 0.0)
                || any(notEqual(pairA, trustedPairA))
                || any(notEqual(pairB, trustedPairB))
                || primeNrdRadiancePeak(sum) > limit * (1.0 + 2.0e-6) + 1.0e-6) {
            mask |= PRIME_TEST_FAILURE_RANGE;
        }
        result0 = float4(a, scale);
        result1 = float4(b, limit);
        result2 = float4(c, primeNrdRadiancePeak(sum));
        result3 = float4(pairA + pairB, pairScale);
    } else if (kind == 6u) {
        bool preserveVisibleInterface = control.w != 0u;
        uint primaryMaterialFlags = primeNrdPrimaryMaterialFlags(
                control.y,
                control.z,
                preserveVisibleInterface);
        uint expectedFlags = preserveVisibleInterface
                && primeMaterialIsTransmissive(control.z)
                ? control.z
                : control.y;
        float id = primeNrdMaterialId(primaryMaterialFlags);
        primeNrdCheckUnit(mask, id);
        bool exact = id == 0.0 || id == 1.0 / 3.0 || id == 2.0 / 3.0 || id == 1.0;
        if (!exact || primaryMaterialFlags != expectedFlags) {
            mask |= PRIME_TEST_FAILURE_EVENT;
        }
        result0 = float4(id, float(primaryMaterialFlags), 0.0, 0.0);
    } else if (kind == 7u) {
        uint classification = primeClassifyOptionalDirection(first.xyz);
        uint expected = control.y == 0u || control.y == 1u
                ? 0u
                : (control.y == 2u
                        ? PRIME_NUMERICAL_INVALID_DIRECTION
                        : PRIME_NUMERICAL_NAN);
        if (classification != expected) {
            mask |= PRIME_TEST_FAILURE_IDENTITY;
        }
        result0 = float4(float(classification), first.xyz);
    } else if (kind == 8u) {
        float2 transported01 = unpackHalf2x16(packHalf2x16(first.xy));
        float transported2 =
                unpackHalf2x16(packHalf2x16(float2(first.z, 0.0))).x;
        float3 transported = float3(transported01, transported2);
        float3 restored = primeRestoreFp16Direction(transported);
        uint classification = primeClassifyOptionalDirection(restored);
        if (control.y == 0u) {
            if (classification != 0u
                    || dot(restored, first.xyz) < 0.99999) {
                mask |= PRIME_TEST_FAILURE_IDENTITY;
            }
        } else if (control.y == 1u) {
            if (classification != 0u
                    || any(notEqual(restored, float3(0.0)))) {
                mask |= PRIME_TEST_FAILURE_IDENTITY;
            }
        } else if (control.y == 2u) {
            if (classification != PRIME_NUMERICAL_INVALID_DIRECTION
                    || any(notEqual(restored, transported))) {
                mask |= PRIME_TEST_FAILURE_IDENTITY;
            }
        } else if ((classification & PRIME_NUMERICAL_NAN) == 0u) {
            mask |= PRIME_TEST_FAILURE_IDENTITY;
        }
        result0 = float4(restored, float(classification));
        result1 = float4(transported, 0.0);
    } else if (kind == 9u) {
        PrimeNrdSg diffuseSg = primeNrdUnpackSg(
                float4(1.0, 0.0, 0.0, 0.0),
                normalize(float3(0.5, 0.25, 1.0)));
        PrimeNrdSg specularSg = primeNrdUnpackSg(
                float4(0.75, 0.0, 0.0, 0.0),
                normalize(float3(-0.4, 0.3, 1.0)));
        float3 view = normalize(float3(0.2, -0.1, 1.0));
        float3 normal = float3(0.0, 0.0, 1.0);
        float3 eastNormal = normalize(float3(0.55, 0.0, 1.0));
        float3 westNormal = normalize(float3(-0.35, 0.0, 1.0));
        float3 northNormal = normalize(float3(0.0, 0.45, 1.0));
        float3 southNormal = normalize(float3(0.0, -0.25, 1.0));
        float4 neighborViewZ = float4(10.0);
        float2 scale = primeNrdSgReJitter(
                diffuseSg,
                specularSg,
                view,
                0.35,
                10.0,
                neighborViewZ,
                normal,
                eastNormal,
                westNormal,
                northNormal,
                southNormal);
        float2 cornerScale = primeNrdSgReJitter(
                diffuseSg,
                specularSg,
                view,
                0.35,
                10.0,
                neighborViewZ,
                normal,
                float3(1.0, 0.0, 0.0),
                normal,
                normal,
                normal);
        PrimeNrdResolvedSg resolved = primeNrdResolveFilteredSg(
                diffuseSg,
                specularSg,
                view,
                0.35,
                10.0,
                neighborViewZ,
                normal,
                eastNormal,
                westNormal,
                northNormal,
                southNormal);
        if (!primeTestClose(
                    resolved.diffuse,
                    primeNrdResolveSgDiffuse(
                            diffuseSg, normal, view, 0.35) * scale.x,
                    1.0e-6,
                    1.0e-6)
                || !primeTestClose(
                    resolved.specular,
                    primeNrdResolveSgSpecular(
                            specularSg, normal, view, 0.35) * scale.y,
                    1.0e-6,
                    1.0e-6)
                || all(lessThanEqual(abs(scale - float2(1.0)), float2(1.0e-4)))) {
            mask |= PRIME_TEST_FAILURE_IDENTITY;
        }
        if (any(notEqual(cornerScale, float2(1.0)))) {
            mask |= PRIME_TEST_FAILURE_EVENT;
        }
        result0 = float4(resolved.diffuse, scale.x);
        result1 = float4(resolved.specular, scale.y);
        result2 = float4(cornerScale, 0.0, 0.0);
    } else {
        mask |= PRIME_TEST_FAILURE_EVENT;
    }

    uint outputWords = primeTestOutputWords();
    uint outputOffset = primeTestOutputOffset(caseIndex, outputWords);
    primeTestOutput[outputOffset] = uint4(mask, kind, caseIndex, 0u);
    if (outputWords >= PRIME_NRD_WITNESS_WORDS) {
        primeTestOutput[outputOffset + 1u] = control;
        primeTestOutput[outputOffset + 2u] = asuint(first);
        primeTestOutput[outputOffset + 3u] = asuint(second);
        primeTestOutput[outputOffset + 4u] = asuint(third);
        primeTestOutput[outputOffset + 5u] = asuint(result0);
        primeTestOutput[outputOffset + 6u] = asuint(result1);
        primeTestOutput[outputOffset + 7u] = asuint(result2);
        primeTestOutput[outputOffset + 8u] = asuint(result3);
    }
}
