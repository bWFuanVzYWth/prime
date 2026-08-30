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

float primeMixedParam(uint4 encoded[4], uint index) {
    uint flatIndex = index + 1u;
    return asfloat(encoded[flatIndex / 4u][flatIndex % 4u]);
}

float3 primeMixedDirection(float cosineTheta, float phi) {
    float radius = sqrt(max(0.0, 1.0 - cosineTheta * cosineTheta));
    return float3(radius * cos(phi), radius * sin(phi), cosineTheta);
}

PrimeOpenPbrVolumeStack primeMixedEmptyStack() {
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

void primeMixedClassify(inout uint mask, float value) {
    mask |= primeTestClassify(value);
}

void primeMixedClassify(inout uint mask, float3 value) {
    mask |= primeTestClassify(value);
}

bool primeMixedNonnegative(float value) {
    return value >= 0.0 && primeTestClassify(value) == 0u;
}

bool primeMixedNonnegative(float3 value) {
    return all(value >= float3(0.0)) && primeTestClassify(value) == 0u;
}

PrimeMixedResult primeMixedRun(uint4 encoded[4]) {
    PrimeMixedResult result;
    result.mask = 0u;
    result.thinWalled = encoded[0].x;
    float roughness = primeMixedParam(encoded, 0u);
    float ior = primeMixedParam(encoded, 1u);
    float3 wi = primeMixedDirection(
            primeMixedParam(encoded, 2u), primeMixedParam(encoded, 3u));
    float3 wo = primeMixedDirection(
            primeMixedParam(encoded, 4u), primeMixedParam(encoded, 5u));
    float3 randomValue = float3(
            primeMixedParam(encoded, 6u),
            primeMixedParam(encoded, 7u),
            primeMixedParam(encoded, 8u));
    float3 color = float3(
            primeMixedParam(encoded, 9u),
            primeMixedParam(encoded, 10u),
            primeMixedParam(encoded, 11u));
    float subsurfaceWeight = primeMixedParam(encoded, 12u);
    float anisotropy = primeMixedParam(encoded, 13u);
    float inverseOutsideIor = primeMixedParam(encoded, 14u);

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
            wi, randomValue, state, primeMixedEmptyStack());
    result.sampledEvaluation.throughput = primeOpenPbrZeroThroughput();
    result.sampledEvaluation.pdf = 0.0;

    primeMixedClassify(result.mask, result.evaluation.throughput.value);
    primeMixedClassify(result.mask, result.evaluation.pdf);
    primeMixedClassify(result.mask, result.components.diffuse);
    primeMixedClassify(result.mask, result.components.specular);
    primeMixedClassify(result.mask, result.components.pdf);
    primeMixedClassify(result.mask, result.energy.diffuse);
    primeMixedClassify(result.mask, result.energy.specular);
    if (!primeMixedNonnegative(result.evaluation.throughput.value)
            || !primeMixedNonnegative(result.evaluation.pdf)
            || !primeMixedNonnegative(result.components.diffuse)
            || !primeMixedNonnegative(result.components.specular)
            || !primeMixedNonnegative(result.components.pdf)
            || !primeMixedNonnegative(result.energy.diffuse)
            || !primeMixedNonnegative(result.energy.specular)
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
        primeMixedClassify(result.mask, result.sample.bsdfSample.throughput.value);
        primeMixedClassify(result.mask, result.sample.bsdfSample.pdf);
        primeMixedClassify(result.mask, result.sample.bsdfSample.eta);
        primeMixedClassify(result.mask, result.sample.bsdfSample.wo);
        if (!primeMixedNonnegative(result.sample.bsdfSample.throughput.value)
                || !primeMixedNonnegative(result.sample.bsdfSample.pdf)
                || !primeMixedNonnegative(result.sample.bsdfSample.eta)
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
        primeMixedClassify(result.mask, result.sampledEvaluation.throughput.value);
        primeMixedClassify(result.mask, result.sampledEvaluation.pdf);
        if (!primeMixedNonnegative(result.sampledEvaluation.throughput.value)
                || !primeMixedNonnegative(result.sampledEvaluation.pdf)) {
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
            result.sample.volumeStack.count,
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
