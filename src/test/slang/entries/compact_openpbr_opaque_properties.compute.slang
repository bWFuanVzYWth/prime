#include "core/prime_test_abi.slang"
import "service/bsdf/support/transmission_energy.slang";
import "bsdf/compact/contract/material.slang";
import "bsdf/compact/opaque/state.slang";
import "bsdf/compact/opaque/lobes.slang";
import "bsdf/compact/opaque/traits.slang";
import "bsdf/compact/opaque/evaluate.slang";
import "bsdf/compact/opaque/sample.slang";
import "bsdf/compact/opaque/energy.slang";

import "bsdf/compact/contract/flags.slang";
import "bsdf/compact/contract/volume.slang";
import "bsdf/compact/contract/sample.slang";
import "bsdf/compact/model/material.slang";

static const uint PRIME_COMPACT_INPUT_WORDS = 4u;

struct PrimeCompactTestResult {
    uint mask;
    uint kind;
    PrimeCompactOpaqueEvaluation evaluation;
    PrimeCompactOpaqueEvaluation sampledEvaluation;
    PrimeCompactOpaqueComponents components;
    PrimeCompactOpaqueEnergy energy;
    PrimeOpenPbrSampleResult sample;
};

float primeCompactParam(uint4 encoded[4], uint index) {
    uint flatIndex = index + 1u;
    return asfloat(encoded[flatIndex / 4u][flatIndex % 4u]);
}

float3 primeCompactDirection(float cosineTheta, float phi) {
    float radius = sqrt(max(0.0, 1.0 - cosineTheta * cosineTheta));
    return float3(radius * cos(phi), radius * sin(phi), cosineTheta);
}

PrimeOpenPbrVolumeStack primeCompactEmptyStack() {
    PrimeOpenPbrVolumeStack stack;
    PrimeOpenPbrVolume air;
    air.extinction = float3(0.0);
    air.albedo = float3(0.0);
    air.anisotropy = 0.0;
    air.ior = 1.0;
    air.mediumId = 0u;
    air.iorEncoding = 0u;
    stack.values[0] = air;
    stack.values[1] = air;
    stack.count = 0u;
    return stack;
}

void primeCompactClassify(inout uint mask, float value) {
    mask |= primeTestClassify(value);
}

void primeCompactClassify(inout uint mask, float3 value) {
    mask |= primeTestClassify(value);
}

bool primeCompactNonnegative(float value) {
    return value >= 0.0 && primeTestClassify(value) == 0u;
}

bool primeCompactNonnegative(float3 value) {
    return all(value >= float3(0.0)) && primeTestClassify(value) == 0u;
}

PrimeCompactTestResult primeCompactRun(uint4 encoded[4]) {
    PrimeCompactTestResult result;
    result.mask = 0u;
    result.kind = encoded[0].x;
    float roughness = primeCompactParam(encoded, 0u);
    float ior = primeCompactParam(encoded, 1u);
    float3 wi = primeCompactDirection(
            primeCompactParam(encoded, 2u), primeCompactParam(encoded, 3u));
    float3 wo = primeCompactDirection(
            primeCompactParam(encoded, 4u), primeCompactParam(encoded, 5u));
    float3 randomValue = float3(
            primeCompactParam(encoded, 6u),
            primeCompactParam(encoded, 7u),
            primeCompactParam(encoded, 8u));
    float3 color = float3(
            primeCompactParam(encoded, 9u),
            primeCompactParam(encoded, 10u),
            primeCompactParam(encoded, 11u));
    float anisotropy = primeCompactParam(encoded, 12u);
    float inverseOutsideIor = primeCompactParam(encoded, 13u);
    float rayT = primeCompactParam(encoded, 14u);

    PrimeOpenPbrMaterial material = primeCompactMaterialFromMetallic(
            color,
            roughness,
            result.kind == PRIME_COMPACT_OPAQUE_CONDUCTOR ? 1.0 : 0.0,
            float3(0.0, 0.0, 1.0));
    material.specular.ior = ior;
    if (result.kind >= PRIME_COMPACT_OPAQUE_SUBSURFACE) {
        material.weight.subsurface = 1.0;
        material.subsurface.color = color;
        material.subsurface.scatterAnisotropy = anisotropy;
        material.geometry.thinWalled = 1u;
    }
    if (!primeCompactOpaqueSupports(material)) {
        result.mask |= PRIME_TEST_FAILURE_STATE;
    }
    PrimeOpenPbrMaterial unsupported = material;
    unsupported.weight.coat = 0.5;
    if (primeCompactOpaqueSupports(unsupported)) {
        result.mask |= PRIME_TEST_FAILURE_STATE;
    }
    if (material.weight.subsurface > 0.0) {
        unsupported = material;
        unsupported.geometry.thinWalled = 0u;
        if (primeCompactOpaqueSupports(unsupported)) {
            result.mask |= PRIME_TEST_FAILURE_STATE;
        }
    }

    PrimeCompactOpaqueState state = primeCompactOpaqueStateInit(
            primeTransmissionGgxEnergy(), material, wi, inverseOutsideIor, rayT);
    result.evaluation = primeCompactOpaqueEvaluate(wi, wo, state);
    result.components = primeCompactOpaqueEvaluateComponents(wi, wo, state);
    result.energy = primeCompactOpaqueEnergy(wi, state);
    result.sample = primeCompactOpaqueSample(
            wi, randomValue, state, primeCompactEmptyStack());
    result.sampledEvaluation.throughput = primeOpenPbrZeroThroughput();
    result.sampledEvaluation.pdf = 0.0;

    primeCompactClassify(result.mask, result.evaluation.throughput.value);
    primeCompactClassify(result.mask, result.evaluation.pdf);
    primeCompactClassify(result.mask, result.components.diffuse);
    primeCompactClassify(result.mask, result.components.specular);
    primeCompactClassify(result.mask, result.components.pdf);
    primeCompactClassify(result.mask, result.energy.diffuse);
    primeCompactClassify(result.mask, result.energy.specular);
    if (!primeCompactNonnegative(result.evaluation.throughput.value)
            || !primeCompactNonnegative(result.evaluation.pdf)
            || !primeCompactNonnegative(result.components.diffuse)
            || !primeCompactNonnegative(result.components.specular)
            || !primeCompactNonnegative(result.components.pdf)
            || !primeCompactNonnegative(result.energy.diffuse)
            || !primeCompactNonnegative(result.energy.specular)) {
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
        primeCompactClassify(result.mask, result.sample.bsdfSample.throughput.value);
        primeCompactClassify(result.mask, result.sample.bsdfSample.pdf);
        primeCompactClassify(result.mask, result.sample.bsdfSample.eta);
        primeCompactClassify(result.mask, result.sample.bsdfSample.wo);
        if (!primeCompactNonnegative(result.sample.bsdfSample.throughput.value)
                || !primeCompactNonnegative(result.sample.bsdfSample.pdf)
                || result.sample.bsdfSample.eta <= 0.0
                || !primeTestClose(
                        length(result.sample.bsdfSample.wo), 1.0, 2.0e-5, 2.0e-5)) {
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

void primeCompactWrite(uint caseIndex, PrimeCompactTestResult result) {
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
    primeTestOutput[outputOffset + 8u] = uint4(
            result.sample.bsdfSample.throughput.flags,
            result.sample.volumeStack.count,
            asuint(result.sample.rayT),
            0u);
    primeTestOutput[outputOffset + 9u] = asuint(float4(
            result.sampledEvaluation.throughput.value,
            result.sampledEvaluation.pdf));
    primeTestOutput[outputOffset + 10u] = uint4(
            result.evaluation.throughput.flags,
            result.sampledEvaluation.throughput.flags,
            0u,
            0u);
    primeTestOutput[outputOffset + 11u] = uint4(0u);
}

[numthreads(64, 1, 1)]
void main(uint3 dispatchThreadId : SV_DispatchThreadID) {
    uint caseIndex;
    if (!primeTestBegin(dispatchThreadId, caseIndex)) { return; }
    uint inputOffset = primeTestInputOffset(caseIndex, PRIME_COMPACT_INPUT_WORDS);
    uint4 encoded[4];
    for (uint word = 0u; word < PRIME_COMPACT_INPUT_WORDS; word++) {
        encoded[word] = primeTestInput[inputOffset + word];
    }
    primeCompactWrite(caseIndex, primeCompactRun(encoded));
}
