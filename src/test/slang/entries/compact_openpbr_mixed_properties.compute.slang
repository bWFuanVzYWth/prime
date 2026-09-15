// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

#include "core/prime_test_abi.slang"
import "service/bsdf/support/transmission_energy.slang";
import "bsdf/compact/opaque/state.slang";
import "bsdf/compact/opaque/lobes.slang";
import "bsdf/compact/opaque/traits.slang";
import "bsdf/compact/opaque/evaluate.slang";
import "bsdf/compact/opaque/sample.slang";
import "bsdf/compact/opaque/energy.slang";
import "bsdf/compact/contract/material.slang";

import "bsdf/compact/contract/flags.slang";
import "bsdf/compact/contract/volume.slang";
import "bsdf/compact/contract/sample.slang";
import "bsdf/compact/model/material.slang";
#include "core/compact_bsdf_test.slang"

static const uint PRIME_MIXED_INPUT_WORDS = 4u;
static const uint PRIME_MIXED_WITNESS_WORDS = 10u;

struct PrimeMixedResult {
    uint mask;
    uint thinWalled;
    PrimeCompactOpaqueEvaluation evaluation;
    PrimeCompactOpaqueEvaluation sampledEvaluation;
    PrimeCompactOpaqueComponents components;
    PrimeCompactOpaqueEnergy energy;
    PrimeOpenPbrSampleResult sample;
};

PrimeMixedResult primeMixedRun(uint4 encoded[4]) {
    PrimeMixedResult result;
    result.mask = 0u;
    result.thinWalled = encoded[0].x;
    float roughness = primeCompactTestParam(encoded, 0u);
    float ior = primeCompactTestParam(encoded, 1u);
    float3 wi = primeCompactTestDirection(
            primeCompactTestParam(encoded, 2u), primeCompactTestParam(encoded, 3u));
    float3 wo = primeCompactTestDirection(
            primeCompactTestParam(encoded, 4u), primeCompactTestParam(encoded, 5u));
    float3 randomValue = float3(
            primeCompactTestParam(encoded, 6u),
            primeCompactTestParam(encoded, 7u),
            primeCompactTestParam(encoded, 8u));
    float3 color = float3(
            primeCompactTestParam(encoded, 9u),
            primeCompactTestParam(encoded, 10u),
            primeCompactTestParam(encoded, 11u));
    float subsurfaceWeight = primeCompactTestParam(encoded, 12u);
    float anisotropy = primeCompactTestParam(encoded, 13u);
    float inverseOutsideIor = primeCompactTestParam(encoded, 14u);

    PrimeOpenPbrMaterial material = primeCompactMaterialFromMetallic(
            color, roughness, 0.0, float3(0.0, 0.0, 1.0));
    material.specular.ior = ior;
    material.weight.subsurface = subsurfaceWeight;
    material.subsurface.color = color;
    material.subsurface.scatterAnisotropy = anisotropy;
    material.geometry.thinWalled = result.thinWalled;
    if (!primeCompactOpaqueSupports(material)) {
        result.mask |= PRIME_TEST_FAILURE_STATE;
    }
    PrimeOpenPbrMaterial unsupported = material;
    unsupported.weight.diffuseRoughness = 0.5;
    if (primeCompactOpaqueSupports(unsupported)) {
        result.mask |= PRIME_TEST_FAILURE_STATE;
    }

    PrimeCompactOpaqueState state = primeCompactOpaqueStateInit(
            primeTransmissionGgxEnergy(), material, wi, inverseOutsideIor, 1.0);
    result.evaluation = primeCompactOpaqueEvaluate(wi, wo, state);
    result.components = primeCompactOpaqueEvaluateComponents(wi, wo, state);
    result.energy = primeCompactOpaqueEnergy(wi, state);

    PrimeOpenPbrMaterial diffuseMaterial = material;
    diffuseMaterial.weight.subsurface = 0.0;
    PrimeOpenPbrMaterial subsurfaceMaterial = material;
    subsurfaceMaterial.weight.subsurface = 1.0;
    PrimeCompactOpaqueState diffuseState = primeCompactOpaqueStateInit(
            primeTransmissionGgxEnergy(),
            diffuseMaterial,
            wi,
            inverseOutsideIor,
            1.0);
    PrimeCompactOpaqueState subsurfaceState = primeCompactOpaqueStateInit(
            primeTransmissionGgxEnergy(),
            subsurfaceMaterial,
            wi,
            inverseOutsideIor,
            1.0);
    PrimeCompactOpaqueEvaluation diffuseEvaluation = primeCompactOpaqueEvaluate(
            wi, wo, diffuseState);
    PrimeCompactOpaqueEvaluation subsurfaceEvaluation = primeCompactOpaqueEvaluate(
            wi, wo, subsurfaceState);
    PrimeCompactOpaqueComponents diffuseComponents =
            primeCompactOpaqueEvaluateComponents(wi, wo, diffuseState);
    PrimeCompactOpaqueComponents subsurfaceComponents =
            primeCompactOpaqueEvaluateComponents(wi, wo, subsurfaceState);
    PrimeCompactOpaqueEnergy diffuseEnergy = primeCompactOpaqueEnergy(wi, diffuseState);
    PrimeCompactOpaqueEnergy subsurfaceEnergy = primeCompactOpaqueEnergy(
            wi, subsurfaceState);
    if (!primeTestClose(
                result.evaluation.throughput.value,
                lerp(
                        diffuseEvaluation.throughput.value,
                        subsurfaceEvaluation.throughput.value,
                        subsurfaceWeight),
                4.0e-6,
                2.0e-5)
            || !primeTestClose(
                result.components.diffuse,
                lerp(
                        diffuseComponents.diffuse,
                        subsurfaceComponents.diffuse,
                        subsurfaceWeight),
                4.0e-6,
                2.0e-5)
            || !primeTestClose(
                result.components.specular,
                diffuseComponents.specular,
                4.0e-6,
                2.0e-5)
            || !primeTestClose(
                result.components.specular,
                subsurfaceComponents.specular,
                4.0e-6,
                2.0e-5)
            || !primeTestClose(
                result.energy.diffuse,
                lerp(diffuseEnergy.diffuse, subsurfaceEnergy.diffuse, subsurfaceWeight),
                4.0e-6,
                2.0e-5)
            || !primeTestClose(
                result.energy.specular,
                diffuseEnergy.specular,
                4.0e-6,
                2.0e-5)
            || !primeTestClose(
                result.energy.specular,
                subsurfaceEnergy.specular,
                4.0e-6,
                2.0e-5)) {
        result.mask |= PRIME_TEST_FAILURE_VALUE;
    }
    result.sample = primeCompactOpaqueSample(
            wi, randomValue, state, primeCompactTestVacuum());
    result.sampledEvaluation.throughput = primeOpenPbrZeroThroughput();
    result.sampledEvaluation.pdf = 0.0;

    primeCompactTestClassify(result.mask, result.evaluation.throughput.value);
    primeCompactTestClassify(result.mask, result.evaluation.pdf);
    primeCompactTestClassify(result.mask, result.components.diffuse);
    primeCompactTestClassify(result.mask, result.components.specular);
    primeCompactTestClassify(result.mask, result.components.pdf);
    primeCompactTestClassify(result.mask, result.energy.diffuse);
    primeCompactTestClassify(result.mask, result.energy.specular);
    if (!primeCompactTestNonnegative(result.evaluation.throughput.value)
            || !primeCompactTestNonnegative(result.evaluation.pdf)
            || !primeCompactTestNonnegative(result.components.diffuse)
            || !primeCompactTestNonnegative(result.components.specular)
            || !primeCompactTestNonnegative(result.components.pdf)
            || !primeCompactTestNonnegative(result.energy.diffuse)
            || !primeCompactTestNonnegative(result.energy.specular)
            || any(result.energy.diffuse + result.energy.specular > float3(1.0001))) {
        result.mask |= PRIME_TEST_FAILURE_RANGE;
    }
    if (!primeTestClose(
                result.evaluation.throughput.value,
                result.components.diffuse + result.components.specular,
                4.0e-6,
                2.0e-5)
            || !primeTestClose(
                result.evaluation.pdf, result.components.pdf, 4.0e-6, 2.0e-5)) {
        result.mask |= PRIME_TEST_FAILURE_VALUE;
    }

    uint flags = result.sample.bsdfSample.throughput.flags;
    if (flags != PRIME_OPENPBR_FLAG_NONE) {
        primeCompactTestClassify(result.mask, result.sample.bsdfSample.throughput.value);
        primeCompactTestClassify(result.mask, result.sample.bsdfSample.pdf);
        primeCompactTestClassify(result.mask, result.sample.bsdfSample.eta);
        primeCompactTestClassify(result.mask, result.sample.bsdfSample.wo);
        if (!primeCompactTestNonnegative(result.sample.bsdfSample.throughput.value)
                || !primeCompactTestNonnegative(result.sample.bsdfSample.pdf)
                || !primeCompactTestNonnegative(result.sample.bsdfSample.eta)
                || !primeTestClose(
                        length(result.sample.bsdfSample.wo), 1.0, 2.0e-5, 2.0e-5)) {
            result.mask |= PRIME_TEST_FAILURE_STATE;
        }
        bool reflected = wi.z * result.sample.bsdfSample.wo.z > 0.0;
        if (reflected != primeOpenPbrIsReflective(flags)
                || reflected == primeOpenPbrIsTransmissive(flags)) {
            result.mask |= PRIME_TEST_FAILURE_EVENT;
        }
        result.sampledEvaluation = primeCompactOpaqueEvaluate(
                wi, result.sample.bsdfSample.wo, state);
        primeCompactTestClassify(result.mask, result.sampledEvaluation.throughput.value);
        primeCompactTestClassify(result.mask, result.sampledEvaluation.pdf);
        if (!primeCompactTestNonnegative(result.sampledEvaluation.throughput.value)
                || !primeCompactTestNonnegative(result.sampledEvaluation.pdf)) {
            result.mask |= PRIME_TEST_FAILURE_RANGE;
        }
    }
    return result;
}

void primeMixedWrite(uint caseIndex, PrimeMixedResult result) {
    uint outputOffset = primeTestOutputOffset(caseIndex, primeTestOutputWords());
    primeTestOutput[outputOffset] = uint4(result.mask, result.thinWalled, caseIndex, 0u);
    if (!primeTestWitnessEnabled()) { return; }
    primeTestOutput[outputOffset + 1u] = asuint(float4(
            result.evaluation.throughput.value, result.evaluation.pdf));
    primeTestOutput[outputOffset + 2u] = asuint(float4(
            result.components.diffuse, result.components.pdf));
    primeTestOutput[outputOffset + 3u] = asuint(float4(
            result.components.specular, 0.0));
    primeTestOutput[outputOffset + 4u] = asuint(float4(result.energy.diffuse, 0.0));
    primeTestOutput[outputOffset + 5u] = asuint(float4(result.energy.specular, 0.0));
    primeTestOutput[outputOffset + 6u] = asuint(float4(
            result.sample.bsdfSample.throughput.value,
            result.sample.bsdfSample.pdf));
    primeTestOutput[outputOffset + 7u] = uint4(
            asuint(result.sample.bsdfSample.wo),
            result.sample.bsdfSample.throughput.flags);
    primeTestOutput[outputOffset + 8u] = asuint(float4(
            result.sampledEvaluation.throughput.value,
            result.sampledEvaluation.pdf));
    primeTestOutput[outputOffset + 9u] = uint4(
            result.sample.medium.mediumId,
            asuint(result.sample.rayT),
            asuint(result.sample.bsdfSample.eta),
            0u);
}

[numthreads(64, 1, 1)]
void main(uint3 dispatchThreadId : SV_DispatchThreadID) {
    uint caseIndex;
    if (!primeTestBegin(dispatchThreadId, caseIndex)) { return; }
    uint inputOffset = primeTestInputOffset(caseIndex, PRIME_MIXED_INPUT_WORDS);
    uint4 encoded[4];
    for (uint word = 0u; word < PRIME_MIXED_INPUT_WORDS; word++) {
        encoded[word] = primeTestInput[inputOffset + word];
    }
    primeMixedWrite(caseIndex, primeMixedRun(encoded));
}
