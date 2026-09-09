// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.


#include "core/prime_test_abi.slang"
import "bsdf/compact/math/fresnel.slang";
import "bsdf/compact/math/microfacet_distribution.slang";
import "bsdf/compact/math/microfacet_energy.slang";
import "bsdf/compact/math/microfacet_measure.slang";

import "bsdf/compact/contract/flags.slang";
import "bsdf/compact/contract/material.slang";
import "bsdf/compact/contract/sample.slang";
import "bsdf/compact/math/spectrum.slang";
import "bsdf/compact/math/frame.slang";
bool3 greaterThanEqual(float3 first, float3 second) { return first >= second; }
bool3 lessThanEqual(float3 first, float3 second) { return first <= second; }
bool2 lessThan(float2 first, float2 second) { return first < second; }
bool3 lessThan(float3 first, float3 second) { return first < second; }

static const uint PRIME_CORE_INPUT_WORDS = 4u;
static const uint PRIME_CORE_WITNESS_WORDS = 8u;

static const uint PRIME_CORE_ONB = 0u;
static const uint PRIME_CORE_COMMON = 1u;
static const uint PRIME_CORE_FRESNEL = 2u;
static const uint PRIME_CORE_REFLECTION = 3u;

struct PrimeCoreResult {
    uint mask;
    uint4 witness[3];
};

void primeCoreClear(out PrimeCoreResult result) {
    result.mask = 0u;
    for (uint index = 0u; index < 3u; index++) {
        result.witness[index] = uint4(0u);
    }
}

float primeCoreParam(uint4 encoded[4], uint index) {
    uint flatIndex = index + 1u;
    return asfloat(encoded[flatIndex / 4u][flatIndex % 4u]);
}

void primeCoreClassify(inout uint mask, float value) {
    mask |= primeTestClassify(value);
}

void primeCoreClassify(inout uint mask, float2 value) {
    mask |= primeTestClassify(value);
}

void primeCoreClassify(inout uint mask, float3 value) {
    mask |= primeTestClassify(value);
}

void primeCoreClassify(inout uint mask, float4 value) {
    mask |= primeTestClassify(value);
}

bool primeCoreInUnitRange(float value) {
    return value >= -1.0e-6 && value <= 1.0 + 1.0e-6;
}

bool primeCoreInUnitRange(float3 value) {
    return all(greaterThanEqual(value, float3(-1.0e-6)))
            && all(lessThanEqual(value, float3(1.0 + 1.0e-6)));
}

void primeCoreOnb(uint4 encoded[4], out PrimeCoreResult result) {
    primeCoreClear(result);
    float3 normal = float3(
            primeCoreParam(encoded, 0u),
            primeCoreParam(encoded, 1u),
            primeCoreParam(encoded, 2u));
    float3 local = float3(
            primeCoreParam(encoded, 3u),
            primeCoreParam(encoded, 4u),
            primeCoreParam(encoded, 5u));
    PrimeOpenPbrOnb onb = primeOpenPbrOnbFromNormal(normal);
    float3 world = primeOpenPbrOnbToWorld(onb, local);
    float3 roundTrip = primeOpenPbrOnbToLocal(onb, world);
    float3 lengths = float3(
            length(onb.tangent),
            length(onb.bitangent),
            length(onb.normal));
    float3 orthogonality = float3(
            dot(onb.tangent, onb.bitangent),
            dot(onb.tangent, onb.normal),
            dot(onb.bitangent, onb.normal));
    float handedness = dot(cross(onb.tangent, onb.bitangent), onb.normal);
    float roundTripError = max(
            abs(roundTrip.x - local.x),
            max(abs(roundTrip.y - local.y), abs(roundTrip.z - local.z)));

    primeCoreClassify(result.mask, onb.tangent);
    primeCoreClassify(result.mask, onb.bitangent);
    primeCoreClassify(result.mask, onb.normal);
    primeCoreClassify(result.mask, world);
    primeCoreClassify(result.mask, roundTrip);
    primeCoreClassify(result.mask, lengths);
    primeCoreClassify(result.mask, orthogonality);
    primeCoreClassify(result.mask, handedness);
    primeCoreClassify(result.mask, roundTripError);
    if (!primeTestClose(lengths, float3(1.0), 4.0e-5, 4.0e-5)
            || !primeTestClose(length(world), length(local), 4.0e-5, 4.0e-5)) {
        result.mask |= PRIME_TEST_FAILURE_UNIT_LENGTH;
    }
    if (!primeTestClose(orthogonality, float3(0.0), 4.0e-5, 4.0e-5)
            || !primeTestClose(handedness, 1.0, 4.0e-5, 4.0e-5)) {
        result.mask |= PRIME_TEST_FAILURE_ORTHOGONAL;
    }
    if (roundTripError > 6.0e-5) {
        result.mask |= PRIME_TEST_FAILURE_ROUND_TRIP;
    }

    result.witness[0] = asuint(float4(lengths, handedness));
    result.witness[1] = asuint(float4(orthogonality, roundTripError));
    result.witness[2] = asuint(float4(world, length(world)));
}

void primeCoreCommon(uint4 encoded[4], out PrimeCoreResult result) {
    primeCoreClear(result);
    float2 sampleValue = float2(
            primeCoreParam(encoded, 0u),
            primeCoreParam(encoded, 1u));
    float f0 = primeCoreParam(encoded, 2u);
    float roughness = primeCoreParam(encoded, 3u);
    float anisotropy = primeCoreParam(encoded, 4u);

    float3 uniformHemisphere = primeOpenPbrUniformSampleHemisphere(sampleValue);
    float3 cosineHemisphere = primeOpenPbrCosineSampleHemisphere(sampleValue);
    float3 uniformSphere = float3(0.0, 0.0, 1.0);
    float ior = primeOpenPbrF0ToIor(f0);
    float roundTripF0 = primeOpenPbrIorToF0(ior);
    float2 alpha = primeOpenPbrSpecularNdfRoughnesses(roughness, anisotropy);
    float3 lengths = float3(
            length(uniformHemisphere),
            length(cosineHemisphere),
            length(uniformSphere));

    primeCoreClassify(result.mask, uniformHemisphere);
    primeCoreClassify(result.mask, cosineHemisphere);
    primeCoreClassify(result.mask, uniformSphere);
    primeCoreClassify(result.mask, ior);
    primeCoreClassify(result.mask, roundTripF0);
    primeCoreClassify(result.mask, alpha);
    primeCoreClassify(result.mask, lengths);
    if (!primeTestClose(lengths, float3(1.0), 3.0e-5, 3.0e-5)) {
        result.mask |= PRIME_TEST_FAILURE_UNIT_LENGTH;
    }
    if (uniformHemisphere.z < -1.0e-7 || cosineHemisphere.z < -1.0e-7) {
        result.mask |= PRIME_TEST_FAILURE_HEMISPHERE;
    }
    if (!primeCoreInUnitRange(roundTripF0)
            || any(lessThan(alpha, float2(0.0)))) {
        result.mask |= PRIME_TEST_FAILURE_RANGE;
    }
    if (!primeTestClose(roundTripF0, f0, 3.0e-6, 3.0e-5)) {
        result.mask |= PRIME_TEST_FAILURE_ROUND_TRIP;
    }
    if (anisotropy == 0.0 && !primeTestClose(alpha.x, alpha.y, 0.0, 0.0)) {
        result.mask |= PRIME_TEST_FAILURE_SYMMETRY;
    }
    if (anisotropy == 0.0) {
        float expectedAlpha = roughness * roughness;
        if (!primeTestClose(alpha.x, expectedAlpha, 1.0e-7, 1.0e-6)) {
            result.mask |= PRIME_TEST_FAILURE_IDENTITY;
        }
    }

    result.witness[0] = asuint(float4(lengths, uniformHemisphere.z));
    result.witness[1] = asuint(float4(ior, roundTripF0, alpha));
    result.witness[2] = asuint(float4(
            cosineHemisphere.z, uniformSphere.z, roughness, anisotropy));
}

void primeCoreFresnel(uint4 encoded[4], out PrimeCoreResult result) {
    primeCoreClear(result);
    float ior = primeCoreParam(encoded, 0u);
    float cosineTheta = primeCoreParam(encoded, 1u);
    float phi = primeCoreParam(encoded, 2u);
    float sineTheta = sqrt(max(0.0, 1.0 - cosineTheta * cosineTheta));
    float3 microIncident = float3(
            sineTheta * cos(phi),
            sineTheta * sin(phi),
            cosineTheta);

    PrimeOpenPbrFloatPair amplitudes = primeOpenPbrDielectricRsRp(ior, cosineTheta);
    PrimeOpenPbrFloatPair reflectance = primeOpenPbrDielectricReflectance(ior, cosineTheta);
    float unpolarized = primeOpenPbrDielectricUnpolarized(ior, cosineTheta);
    float energy = unpolarized;
    float reciprocal = primeOpenPbrDielectricUnpolarized(1.0 / ior, -cosineTheta);
    float normalReflectance = primeOpenPbrDielectricUnpolarized(ior, 1.0);
    float expectedNormalReflectance = primeOpenPbrIorToF0(ior);
    float3 incidentTowardInterface = -microIncident;
    PrimeOpenPbrRefractResult refracted = primeOpenPbrDielectricRefract(
            ior, incidentTowardInterface, float3(0.0, 0.0, 1.0));
    float incidentCosine = incidentTowardInterface.z;
    float inverseEta = incidentCosine < 0.0 ? 1.0 / ior : ior;
    float refractionDiscriminant = 1.0
            - (1.0 - incidentCosine * incidentCosine) * inverseEta * inverseEta;
    uint expectedValid = refractionDiscriminant > 0.0 ? 1u : 0u;
    float snellError = abs(
            length(refracted.wo.xy) - inverseEta * length(incidentTowardInterface.xy));

    primeCoreClassify(result.mask, float2(amplitudes.first, amplitudes.second));
    primeCoreClassify(result.mask, float2(reflectance.first, reflectance.second));
    primeCoreClassify(result.mask, unpolarized);
    primeCoreClassify(result.mask, energy);
    primeCoreClassify(result.mask, reciprocal);
    primeCoreClassify(result.mask, normalReflectance);
    primeCoreClassify(result.mask, expectedNormalReflectance);
    primeCoreClassify(result.mask, refracted.wo);
    primeCoreClassify(result.mask, refractionDiscriminant);
    primeCoreClassify(result.mask, snellError);
    if (!primeCoreInUnitRange(reflectance.first)
            || !primeCoreInUnitRange(reflectance.second)
            || !primeCoreInUnitRange(unpolarized)
            || !primeCoreInUnitRange(normalReflectance)
            || !primeCoreInUnitRange(expectedNormalReflectance)) {
        result.mask |= PRIME_TEST_FAILURE_RANGE;
    }
    // Reciprocity is discontinuously sensitive to rounded reciprocal IORs at the TIR boundary.
    if (abs(refractionDiscriminant) > 1.0e-5
            && !primeTestClose(unpolarized, reciprocal, 2.0e-6, 2.0e-5)) {
        result.mask |= PRIME_TEST_FAILURE_RECIPROCITY;
    }
    if (!primeTestClose(
            normalReflectance, expectedNormalReflectance, 2.0e-6, 2.0e-5)) {
        result.mask |= PRIME_TEST_FAILURE_IDENTITY;
    }
    if (refracted.valid != expectedValid || refracted.valid > 1u) {
        result.mask |= PRIME_TEST_FAILURE_STATE;
    }
    if (refracted.valid != 0u) {
        if (!primeTestClose(length(refracted.wo), 1.0, 4.0e-5, 4.0e-5)) {
            result.mask |= PRIME_TEST_FAILURE_UNIT_LENGTH;
        }
        if (snellError > 4.0e-5) {
            result.mask |= PRIME_TEST_FAILURE_IDENTITY;
        }
    } else if (!primeTestClose(unpolarized, 1.0, 3.0e-6, 3.0e-5)) {
        result.mask |= PRIME_TEST_FAILURE_STATE;
    }

    result.witness[0] = asuint(float4(
            reflectance.first, reflectance.second, unpolarized, energy));
    result.witness[1] = uint4(
            asuint(refracted.wo.x),
            asuint(refracted.wo.y),
            asuint(refracted.wo.z),
            refracted.valid);
    result.witness[2] = asuint(float4(
            reciprocal, normalReflectance, refractionDiscriminant, snellError));
}

void primeCoreReflection(uint4 encoded[4], out PrimeCoreResult result) {
    primeCoreClear(result);
    PrimeOpenPbrMicrofacet microfacet;
    microfacet.alpha = float2(
            primeCoreParam(encoded, 0u),
            primeCoreParam(encoded, 1u));
    float3 wi = normalize(float3(
            primeCoreParam(encoded, 2u),
            primeCoreParam(encoded, 3u),
            primeCoreParam(encoded, 4u)));
    float2 randomValue = float2(
            primeCoreParam(encoded, 5u),
            primeCoreParam(encoded, 6u));

    bool effectivelySmooth = primeOpenPbrMicrofacetEffectivelySmooth(microfacet);
    float3 halfVector = float3(0.0, 0.0, 1.0);
    float distribution = 0.0;
    float microfacetPdf = 0.0;
    float symmetryDistribution = 0.0;
    if (!effectivelySmooth) {
        halfVector = primeOpenPbrMicrofacetSample(microfacet, wi, randomValue, true);
        distribution = primeOpenPbrMicrofacetD(microfacet, halfVector);
        microfacetPdf = primeOpenPbrMicrofacetPdf(
                microfacet, wi, halfVector, true);
        PrimeOpenPbrMicrofacet swapped;
        swapped.alpha = microfacet.alpha.yx;
        symmetryDistribution = primeOpenPbrMicrofacetD(
                swapped, halfVector.yxz);
    }
    PrimeOpenPbrReflectiveSampleBase sampled = primeOpenPbrReflectiveSampleBase(
            wi,
            float3(randomValue, 0.5),
            microfacet,
            PRIME_OPENPBR_FLAG_ALL,
            PRIME_OPENPBR_FLAG_REFLECTION);
    uint flags = sampled.bsdfSample.throughput.flags;
    PrimeOpenPbrReflectiveEvalBase evaluated;
    evaluated.flags = PRIME_OPENPBR_FLAG_NONE;
    evaluated.factor = 0.0;
    evaluated.microCosine = 0.0;
    float evaluatedPdf = 0.0;
    if (flags != PRIME_OPENPBR_FLAG_NONE) {
        evaluated = primeOpenPbrReflectiveEvalBase(
                wi,
                sampled.bsdfSample.wo,
                microfacet,
                PRIME_OPENPBR_FLAG_ALL,
                PRIME_OPENPBR_FLAG_REFLECTION);
        evaluatedPdf = primeOpenPbrReflectivePdf(
                wi,
                sampled.bsdfSample.wo,
                microfacet,
                PRIME_OPENPBR_FLAG_ALL,
                PRIME_OPENPBR_FLAG_REFLECTION);
    }
    float3 directionalAlbedo = primeOpenPbrMicrofacetDirectionalAlbedoReflection(
            microfacet, wi.z, float3(0.04), float3(1.0));

    primeCoreClassify(result.mask, halfVector);
    primeCoreClassify(result.mask, distribution);
    primeCoreClassify(result.mask, microfacetPdf);
    primeCoreClassify(result.mask, symmetryDistribution);
    primeCoreClassify(result.mask, sampled.bsdfSample.wo);
    primeCoreClassify(result.mask, sampled.bsdfSample.pdf);
    primeCoreClassify(result.mask, sampled.bsdfSample.throughput.value);
    primeCoreClassify(result.mask, evaluated.factor);
    primeCoreClassify(result.mask, evaluatedPdf);
    primeCoreClassify(result.mask, directionalAlbedo);
    if (distribution < 0.0
            || microfacetPdf < 0.0
            || sampled.bsdfSample.pdf < 0.0
            || any(lessThan(sampled.bsdfSample.throughput.value, float3(0.0)))
            || evaluated.factor < 0.0
            || evaluatedPdf < 0.0
            || !primeCoreInUnitRange(directionalAlbedo)) {
        result.mask |= PRIME_TEST_FAILURE_RANGE;
    }
    if (!effectivelySmooth) {
        if (!primeTestClose(length(halfVector), 1.0, 3.0e-5, 3.0e-5)) {
            result.mask |= PRIME_TEST_FAILURE_UNIT_LENGTH;
        }
        if (halfVector.z < -1.0e-7) {
            result.mask |= PRIME_TEST_FAILURE_HEMISPHERE;
        }
        if (!primeTestClose(
                distribution, symmetryDistribution, 2.0e-6, 3.0e-5)) {
            result.mask |= PRIME_TEST_FAILURE_SYMMETRY;
        }
    }

    if (flags != PRIME_OPENPBR_FLAG_NONE) {
        if (!primeTestClose(length(sampled.bsdfSample.wo), 1.0, 4.0e-5, 4.0e-5)) {
            result.mask |= PRIME_TEST_FAILURE_UNIT_LENGTH;
        }
        if (sampled.bsdfSample.wo.z <= 0.0) {
            result.mask |= PRIME_TEST_FAILURE_HEMISPHERE;
        }
        uint expectedFlags = effectivelySmooth
                ? PRIME_OPENPBR_FLAG_DELTA_REFLECTION
                : PRIME_OPENPBR_FLAG_SPECULAR_REFLECTION;
        if (flags != expectedFlags) {
            result.mask |= PRIME_TEST_FAILURE_EVENT;
        }
        if (effectivelySmooth) {
            float3 expectedOutgoing = float3(-wi.xy, wi.z);
            if (!primeTestClose(sampled.bsdfSample.wo, expectedOutgoing, 2.0e-6, 2.0e-5)
                    || sampled.bsdfSample.pdf != 1.0
                    || !primeTestClose(
                            sampled.bsdfSample.throughput.value,
                            float3(1.0),
                            0.0,
                            0.0)
                    || evaluated.flags != PRIME_OPENPBR_FLAG_NONE
                    || evaluatedPdf != 0.0) {
                result.mask |= PRIME_TEST_FAILURE_IDENTITY;
            }
        } else {
            if (evaluated.flags != PRIME_OPENPBR_FLAG_SPECULAR_REFLECTION) {
                result.mask |= PRIME_TEST_FAILURE_EVENT;
            }
            // Reconstructing h from nearly antipodal wi/wo is ill-conditioned.
            if (abs(sampled.microCosine) > 1.0e-2
                    && min(microfacet.alpha.x, microfacet.alpha.y) >= 5.0e-4) {
                float consistencyTolerance =
                        min(microfacet.alpha.x, microfacet.alpha.y) < 1.0e-3
                                || abs(wi.z) < 2.0e-2
                        ? 5.0e-3
                        : 3.0e-4;
                if (!primeTestClose(
                        sampled.bsdfSample.pdf,
                        evaluatedPdf,
                        2.0e-6,
                        consistencyTolerance)) {
                    result.mask |= PRIME_TEST_FAILURE_PDF;
                }
                if (!primeTestClose(
                        sampled.bsdfSample.throughput.value.x,
                        evaluated.factor,
                        2.0e-6,
                        consistencyTolerance)) {
                    result.mask |= PRIME_TEST_FAILURE_VALUE;
                }
            }
            PrimeOpenPbrReflectiveEvalBase reverse = primeOpenPbrReflectiveEvalBase(
                    sampled.bsdfSample.wo,
                    wi,
                    microfacet,
                    PRIME_OPENPBR_FLAG_ALL,
                    PRIME_OPENPBR_FLAG_REFLECTION);
            primeCoreClassify(result.mask, reverse.factor);
            if (reverse.flags != PRIME_OPENPBR_FLAG_SPECULAR_REFLECTION
                    || !primeTestClose(
                            evaluated.factor / abs(sampled.bsdfSample.wo.z),
                            reverse.factor / abs(wi.z),
                            3.0e-5,
                            4.0e-4)) {
                result.mask |= PRIME_TEST_FAILURE_RECIPROCITY;
            }
        }
    } else if (evaluated.flags != PRIME_OPENPBR_FLAG_NONE || evaluatedPdf != 0.0) {
        result.mask |= PRIME_TEST_FAILURE_EVENT;
    }

    result.witness[0] = asuint(float4(
            halfVector, distribution));
    result.witness[1] = uint4(
            asuint(sampled.bsdfSample.pdf),
            asuint(evaluatedPdf),
            flags,
            evaluated.flags);
    result.witness[2] = asuint(float4(
            sampled.bsdfSample.throughput.value.x,
            evaluated.factor,
            microfacetPdf,
            length(sampled.bsdfSample.wo)));
}

void primeCoreCommit(
        uint caseIndex,
        uint kind,
        uint4 encoded[4],
        PrimeCoreResult result) {
    uint outputWords = primeTestOutputWords();
    uint outputOffset = primeTestOutputOffset(caseIndex, outputWords);
    primeTestOutput[outputOffset] = uint4(
            result.mask, kind, caseIndex, 0u);
    if (outputWords >= PRIME_CORE_WITNESS_WORDS) {
        for (uint index = 0u; index < PRIME_CORE_INPUT_WORDS; index++) {
            primeTestOutput[outputOffset + 1u + index] = encoded[index];
        }
        for (uint index = 0u; index < 3u; index++) {
            primeTestOutput[outputOffset + 5u + index] =
                    result.witness[index];
        }
    }
}

[numthreads(64, 1, 1)]
void main(uint3 dispatchThreadId : SV_DispatchThreadID)
{
    uint caseIndex;
    if (!primeTestBegin(dispatchThreadId, caseIndex)) {
        return;
    }
    uint inputOffset = primeTestInputOffset(caseIndex, PRIME_CORE_INPUT_WORDS);
    uint4 encoded[4];
    for (uint index = 0u; index < PRIME_CORE_INPUT_WORDS; index++) {
        encoded[index] = primeTestInput[inputOffset + index];
    }
    uint kind = encoded[0].x;
    PrimeCoreResult result;
    if (kind == PRIME_CORE_ONB) {
        primeCoreOnb(encoded, result);
    } else if (kind == PRIME_CORE_COMMON) {
        primeCoreCommon(encoded, result);
    } else if (kind == PRIME_CORE_FRESNEL) {
        primeCoreFresnel(encoded, result);
    } else if (kind == PRIME_CORE_REFLECTION) {
        primeCoreReflection(encoded, result);
    } else {
        primeCoreClear(result);
        result.mask = PRIME_TEST_FAILURE_STATE;
    }
    if (caseIndex >= primeTestInput[0].x) {
        result.mask |= PRIME_TEST_FAILURE_INDEX;
    }
    primeCoreCommit(caseIndex, kind, encoded, result);
}
