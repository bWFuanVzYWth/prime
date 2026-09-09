// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

#include "core/prime_test_abi.slang"
import "service/bsdf/support/transmission_energy.slang";
import "bsdf/compact/foliage/contract.slang";
import "bsdf/compact/foliage/lobes.slang";
import "bsdf/compact/foliage/state.slang";
import "bsdf/compact/foliage/evaluate.slang";
import "bsdf/compact/foliage/sample.slang";
import "bsdf/compact/foliage/energy.slang";
import "bsdf/compact/contract/material.slang";

import "bsdf/compact/contract/flags.slang";
import "bsdf/compact/contract/volume.slang";
import "bsdf/compact/contract/sample.slang";
import "bsdf/compact/model/material.slang";
#include "core/compact_bsdf_test.slang"

static const uint PRIME_COMPACT_FOLIAGE_INPUT_WORDS = 4u;
static const float PRIME_TEST_FOLIAGE_TRANSMISSION_WEIGHT = 0.15;

struct PrimeCompactFoliageTestResult {
    uint mask;
    uint kind;
    PrimeCompactFoliageEvaluation evaluation;
    PrimeCompactFoliageEvaluation sampledEvaluation;
    PrimeCompactFoliageComponents components;
    PrimeCompactFoliageEnergy energy;
    PrimeOpenPbrSampleResult sample;
};

PrimeCompactFoliageTestResult primeCompactFoliageRun(uint4 encoded[4]) {
    PrimeCompactFoliageTestResult result;
    result.mask = 0u;
    result.kind = encoded[0].x;
    bool conductor = result.kind == 2u;
    float roughness = primeCompactTestParam(encoded, 0u);
    float ior = primeCompactTestParam(encoded, 1u);
    float subsurfaceWeight = result.kind == 1u
            ? primeCompactTestParam(encoded, 2u) : 0.0;
    float3 wi = primeCompactTestDirection(
            abs(primeCompactTestParam(encoded, 3u)),
            primeCompactTestParam(encoded, 4u));
    float3 wo = primeCompactTestDirection(
            primeCompactTestParam(encoded, 5u),
            primeCompactTestParam(encoded, 6u));
    float3 randomValue = float3(
            primeCompactTestParam(encoded, 7u),
            primeCompactTestParam(encoded, 8u),
            primeCompactTestParam(encoded, 9u));
    float3 color = float3(
            primeCompactTestParam(encoded, 10u),
            primeCompactTestParam(encoded, 11u),
            primeCompactTestParam(encoded, 12u));
    float inverseOutsideIor = primeCompactTestParam(encoded, 13u);
    float rayT = primeCompactTestParam(encoded, 14u);

    PrimeOpenPbrMaterial material = primeCompactMaterialFromMetallic(
            color, roughness, conductor ? 1.0 : 0.0, float3(0.0, 0.0, 1.0));
    if (!conductor) {
        material.specular.ior = ior;
        material.weight.subsurface = subsurfaceWeight;
        material.subsurface.color = color;
    }
    material.weight.transmission = PRIME_TEST_FOLIAGE_TRANSMISSION_WEIGHT;
    material.geometry.thinWalled = 1u;
    material.geometry.thickness = 0.0625;
    material.transmission.color = clamp(color, float3(0.02), float3(1.0));
    material.transmission.depth = 0.0;
    material.transmission.scatter = float3(0.0);
    material.transmission.scatterAnisotropy = 0.0;
    material.transmission.dispersionScale = 0.0;
    if (!primeCompactFoliageSupports(material)) {
        result.mask |= PRIME_TEST_FAILURE_STATE;
    }
    PrimeOpenPbrMaterial unsupported = material;
    unsupported.weight.coat = 0.5;
    if (primeCompactFoliageSupports(unsupported)) {
        result.mask |= PRIME_TEST_FAILURE_STATE;
    }

    PrimeCompactFoliageState state = primeCompactFoliageStateInit(
            primeTransmissionGgxEnergy(), material, wi, inverseOutsideIor, rayT);
    result.evaluation = primeCompactFoliageEvaluate(wi, wo, state);
    result.components = primeCompactFoliageEvaluateComponents(wi, wo, state);
    result.energy = primeCompactFoliageEnergy(
            primeTransmissionGgxEnergy(), wi, state);
    result.sample = primeCompactFoliageSample(
            wi, randomValue, state, primeCompactTestEmptyStack());
    result.sampledEvaluation.throughput = primeOpenPbrZeroThroughput();
    result.sampledEvaluation.pdf = 0.0;

    primeCompactTestClassify(result.mask, state.base.microfacet.alpha);
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
            || !primeCompactTestNonnegative(result.energy.specular)) {
        result.mask |= PRIME_TEST_FAILURE_RANGE;
    }
    if (!primeTestClose(
                result.evaluation.throughput.value,
                result.components.diffuse + result.components.specular,
                6.0e-6,
                4.0e-5)
            || !primeTestClose(
                result.evaluation.pdf, result.components.pdf, 6.0e-6, 4.0e-5)) {
        result.mask |= PRIME_TEST_FAILURE_VALUE;
    }

    uint flags = result.sample.bsdfSample.throughput.flags;
    if (flags != PRIME_OPENPBR_FLAG_NONE) {
        primeCompactTestClassify(
                result.mask, result.sample.bsdfSample.throughput.value);
        primeCompactTestClassify(result.mask, result.sample.bsdfSample.pdf);
        primeCompactTestClassify(result.mask, result.sample.bsdfSample.eta);
        primeCompactTestClassify(result.mask, result.sample.bsdfSample.wo);
        if (!primeCompactTestNonnegative(result.sample.bsdfSample.throughput.value)
                || !primeCompactTestNonnegative(result.sample.bsdfSample.pdf)
                || result.sample.bsdfSample.eta <= 0.0
                || !primeTestClose(
                        length(result.sample.bsdfSample.wo), 1.0, 3.0e-5, 3.0e-5)) {
            result.mask |= PRIME_TEST_FAILURE_STATE;
        }
        bool reflected = wi.z * result.sample.bsdfSample.wo.z > 0.0;
        if (reflected != primeOpenPbrIsReflective(flags)
                || reflected == primeOpenPbrIsTransmissive(flags)) {
            result.mask |= PRIME_TEST_FAILURE_EVENT;
        }
    }
    if (result.sample.volumeStack.count != 0u
            || !primeTestClose(result.sample.rayT, rayT, 0.0, 0.0)) {
        result.mask |= PRIME_TEST_FAILURE_STATE;
    }
    return result;
}

void primeCompactFoliageWrite(uint caseIndex, PrimeCompactFoliageTestResult result) {
    uint outputOffset = primeTestOutputOffset(caseIndex, primeTestOutputWords());
    primeTestOutput[outputOffset] = uint4(result.mask, result.kind, caseIndex, 0u);
    if (!primeTestWitnessEnabled()) { return; }
    primeTestOutput[outputOffset + 1u] = asuint(float4(
            result.evaluation.throughput.value, result.evaluation.pdf));
    primeTestOutput[outputOffset + 2u] = asuint(float4(
            result.components.diffuse, result.components.pdf));
    primeTestOutput[outputOffset + 3u] = asuint(float4(result.components.specular, 0.0));
    primeTestOutput[outputOffset + 4u] = asuint(float4(result.energy.diffuse, 0.0));
    primeTestOutput[outputOffset + 5u] = asuint(float4(result.energy.specular, 0.0));
    primeTestOutput[outputOffset + 6u] = asuint(float4(
            result.sample.bsdfSample.throughput.value, result.sample.bsdfSample.pdf));
    primeTestOutput[outputOffset + 7u] = asuint(float4(
            result.sample.bsdfSample.wo, result.sample.bsdfSample.eta));
    primeTestOutput[outputOffset + 8u] = asuint(float4(
            result.sampledEvaluation.throughput.value,
            result.sampledEvaluation.pdf));
    primeTestOutput[outputOffset + 9u] = uint4(
            result.evaluation.throughput.flags,
            result.sample.bsdfSample.throughput.flags,
            result.sampledEvaluation.throughput.flags,
            result.sample.volumeStack.count);
    primeTestOutput[outputOffset + 10u] = asuint(float4(result.sample.rayT, 0.0, 0.0, 0.0));
    primeTestOutput[outputOffset + 11u] = uint4(0u);
}

[numthreads(64, 1, 1)]
void main(uint3 dispatchThreadId : SV_DispatchThreadID) {
    uint caseIndex;
    if (!primeTestBegin(dispatchThreadId, caseIndex)) { return; }
    uint inputOffset = primeTestInputOffset(caseIndex, PRIME_COMPACT_FOLIAGE_INPUT_WORDS);
    uint4 encoded[4];
    for (uint word = 0u; word < PRIME_COMPACT_FOLIAGE_INPUT_WORDS; word++) {
        encoded[word] = primeTestInput[inputOffset + word];
    }
    primeCompactFoliageWrite(caseIndex, primeCompactFoliageRun(encoded));
}
