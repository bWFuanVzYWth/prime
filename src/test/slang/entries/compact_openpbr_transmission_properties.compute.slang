#include "core/prime_test_abi.slang"
import "service/bsdf/support/transmission_energy.slang";
import "bsdf/compact/contract/material.slang";
import "bsdf/compact/dielectric/contract.slang";
import "bsdf/compact/dielectric/refract.slang";
import "bsdf/compact/dielectric/state.slang";
import "bsdf/compact/dielectric/evaluate.slang";
import "bsdf/compact/dielectric/sample.slang";
import "bsdf/compact/dielectric/energy.slang";

import "bsdf/compact/contract/flags.slang";
import "bsdf/compact/contract/volume.slang";
import "bsdf/compact/contract/sample.slang";
import "bsdf/compact/model/material.slang";
import "bsdf/compact/model/volume.slang";

static const uint PRIME_COMPACT_TRANSMISSION_INPUT_WORDS = 4u;

struct PrimeCompactTransmissionTestResult {
    uint mask;
    uint kind;
    uint samplingFlags;
    PrimeCompactTransmissionEvaluation evaluation;
    PrimeCompactTransmissionEvaluation sampledEvaluation;
    PrimeOpenPbrSampleResult sample;
    float3 energy;
};

float primeCompactTransmissionParam(uint4 encoded[4], uint index) {
    uint flatIndex = index + 1u;
    return asfloat(encoded[flatIndex / 4u][flatIndex % 4u]);
}

float3 primeCompactTransmissionDirection(float cosineTheta, float phi) {
    float radius = sqrt(max(0.0, 1.0 - cosineTheta * cosineTheta));
    return float3(radius * cos(phi), radius * sin(phi), cosineTheta);
}

PrimeOpenPbrVolumeStack primeCompactTransmissionStack(
        bool exiting,
        PrimeOpenPbrMaterial material) {
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
    if (exiting) {
        PrimeOpenPbrVolume volume = primeOpenPbrVolumeFromTransmission(
                material.transmission);
        volume.ior = material.specular.ior;
        stack.values[0] = volume;
        stack.count = 1u;
    }
    return stack;
}

void primeCompactTransmissionClassify(inout uint mask, float value) {
    mask |= primeTestClassify(value);
}

void primeCompactTransmissionClassify(inout uint mask, float2 value) {
    mask |= primeTestClassify(value);
}

void primeCompactTransmissionClassify(inout uint mask, float3 value) {
    mask |= primeTestClassify(value);
}

bool primeCompactTransmissionNonnegative(float value) {
    return value >= 0.0 && primeTestClassify(value) == 0u;
}

bool primeCompactTransmissionNonnegative(float3 value) {
    return all(value >= float3(0.0)) && primeTestClassify(value) == 0u;
}

PrimeCompactTransmissionTestResult primeCompactTransmissionRun(uint4 encoded[4]) {
    PrimeCompactTransmissionTestResult result;
    result.mask = 0u;
    result.kind = encoded[0].x;
    uint topology = result.kind % 3u;
    uint samplingMode = result.kind / 3u;
    bool thinWalled = topology == 2u;
    bool exiting = topology == 1u;
    result.samplingFlags = samplingMode == 0u
            ? PRIME_OPENPBR_FLAG_ALL
            : samplingMode == 1u
                    ? PRIME_OPENPBR_FLAG_REFLECTION
                    : PRIME_OPENPBR_FLAG_TRANSMISSION;
    float roughness = primeCompactTransmissionParam(encoded, 0u);
    float ior = primeCompactTransmissionParam(encoded, 1u);
    float wiCosine = abs(primeCompactTransmissionParam(encoded, 2u));
    if (exiting) { wiCosine = -wiCosine; }
    float3 wi = primeCompactTransmissionDirection(
            wiCosine, primeCompactTransmissionParam(encoded, 3u));
    float3 wo = primeCompactTransmissionDirection(
            primeCompactTransmissionParam(encoded, 4u),
            primeCompactTransmissionParam(encoded, 5u));
    float3 randomValue = float3(
            primeCompactTransmissionParam(encoded, 6u),
            primeCompactTransmissionParam(encoded, 7u),
            primeCompactTransmissionParam(encoded, 8u));
    float3 color = float3(
            primeCompactTransmissionParam(encoded, 9u),
            primeCompactTransmissionParam(encoded, 10u),
            primeCompactTransmissionParam(encoded, 11u));
    float inverseOutsideIor = primeCompactTransmissionParam(encoded, 12u);
    float rayT = primeCompactTransmissionParam(encoded, 13u);
    bool thinPhysicalExit = thinWalled
            && primeCompactTransmissionParam(encoded, 14u) >= 0.5;

    PrimeOpenPbrMaterial material = primeCompactMaterialFromMetallic(
            float3(1.0), roughness, 0.0, float3(0.0, 0.0, 1.0));
    material.weight.transmission = 1.0;
    material.geometry.thinWalled = thinWalled ? 1u : 0u;
    material.geometry.thickness = thinWalled ? 0.0625 : 1.0;
    material.specular.ior = ior;
    material.transmission.color = color;
    material.transmission.depth = thinWalled ? 0.0 : 1.0;
    material.transmission.scatter = float3(0.0);
    material.transmission.scatterAnisotropy = 0.0;
    material.transmission.dispersionScale = 0.0;
    if (!primeCompactTransmissionSupports(material)) {
        result.mask |= PRIME_TEST_FAILURE_STATE;
    }
    PrimeOpenPbrMaterial unsupported = material;
    unsupported.transmission.dispersionScale = 0.5;
    if (primeCompactTransmissionSupports(unsupported)) {
        result.mask |= PRIME_TEST_FAILURE_STATE;
    }

    PrimeOpenPbrVolumeStack stack = primeCompactTransmissionStack(exiting, material);
    uint originalStackCount = stack.count;
    PrimeCompactTransmissionState state = primeCompactTransmissionStateInit(
            primeTransmissionGgxEnergy(), material, wi, inverseOutsideIor, rayT);
    state = primeCompactTransmissionInterfaceState(
            primeTransmissionGgxEnergy(), wi, state);
    if (thinPhysicalExit) { state.entering = 0u; }

    result.evaluation = primeCompactTransmissionEvaluate(
            wi, wo, state, result.samplingFlags);
    result.sample = primeCompactTransmissionSample(
            wi, randomValue, state, result.samplingFlags, stack);
    result.energy = primeCompactTransmissionEnergy(
            primeTransmissionGgxEnergy(), wi, state, result.samplingFlags);
    result.sampledEvaluation.throughput = primeOpenPbrZeroThroughput();
    result.sampledEvaluation.pdf = 0.0;

    primeCompactTransmissionClassify(result.mask, state.specularMicrofacet.alpha);
    primeCompactTransmissionClassify(result.mask, state.transmissionMicrofacet.alpha);
    primeCompactTransmissionClassify(result.mask, state.specularFresnel.ior);
    primeCompactTransmissionClassify(result.mask, state.specularFresnel.energyIor);
    primeCompactTransmissionClassify(result.mask, state.transmissionMultipleScattering);
    primeCompactTransmissionClassify(result.mask, result.evaluation.throughput.value);
    primeCompactTransmissionClassify(result.mask, result.evaluation.pdf);
    primeCompactTransmissionClassify(result.mask, result.energy);
    if (!primeCompactTransmissionNonnegative(result.evaluation.throughput.value)
            || !primeCompactTransmissionNonnegative(result.evaluation.pdf)
            || !primeCompactTransmissionNonnegative(result.energy)
            || any(result.energy > float3(1.0001))) {
        result.mask |= PRIME_TEST_FAILURE_RANGE;
    }
    if ((result.evaluation.throughput.flags & ~result.samplingFlags) != 0u) {
        result.mask |= PRIME_TEST_FAILURE_EVENT;
    }

    uint flags = result.sample.bsdfSample.throughput.flags;
    bool accepted = flags != PRIME_OPENPBR_FLAG_NONE
            && primeTestClassify(result.sample.bsdfSample.throughput.value) == 0u
            && primeTestClassify(result.sample.bsdfSample.pdf) == 0u
            && primeTestClassify(result.sample.bsdfSample.eta) == 0u
            && primeTestClassify(result.sample.bsdfSample.wo) == 0u
            && all(result.sample.bsdfSample.throughput.value >= float3(0.0))
            && result.sample.bsdfSample.pdf > 0.0
            && result.sample.bsdfSample.eta > 0.0
            && primeTestClose(
                    length(result.sample.bsdfSample.wo), 1.0, 3.0e-5, 3.0e-5);
    // Exact reference proposals outside this predicate are rejected by
    // primeOpenPbrHasSample before their payload or medium state is consumed.
    if (accepted) {
        if ((flags & ~result.samplingFlags) != 0u) {
            result.mask |= PRIME_TEST_FAILURE_EVENT;
        }
        bool reflected = wi.z * result.sample.bsdfSample.wo.z > 0.0;
        if (reflected != primeOpenPbrIsReflective(flags)
                || reflected == primeOpenPbrIsTransmissive(flags)) {
            result.mask |= PRIME_TEST_FAILURE_EVENT;
        }
    }

    bool transmitted = accepted && primeOpenPbrIsTransmissive(flags);
    uint expectedStackCount = originalStackCount;
    float expectedRayT = rayT;
    if (!thinWalled && transmitted) {
        expectedStackCount = exiting ? 0u : 1u;
        if (exiting) { expectedRayT = 0.0; }
    }
    if ((accepted
            && (result.sample.volumeStack.count != expectedStackCount
            || !primeTestClose(result.sample.rayT, expectedRayT, 0.0, 0.0)))
            || result.sample.volumeStack.count > 2u
            || primeTestClassify(result.sample.rayT) != 0u) {
        result.mask |= PRIME_TEST_FAILURE_STATE;
    }
    return result;
}

void primeCompactTransmissionWrite(
        uint caseIndex,
        PrimeCompactTransmissionTestResult result) {
    uint outputOffset = primeTestOutputOffset(caseIndex, primeTestOutputWords());
    primeTestOutput[outputOffset] = uint4(result.mask, result.kind, caseIndex, 0u);
    if (!primeTestWitnessEnabled()) { return; }
    primeTestOutput[outputOffset + 1u] = asuint(float4(
            result.evaluation.throughput.value, result.evaluation.pdf));
    primeTestOutput[outputOffset + 2u] = asuint(float4(result.energy, 0.0));
    primeTestOutput[outputOffset + 3u] = asuint(float4(
            result.sample.bsdfSample.throughput.value,
            result.sample.bsdfSample.pdf));
    primeTestOutput[outputOffset + 4u] = asuint(float4(
            result.sample.bsdfSample.wo,
            result.sample.bsdfSample.eta));
    primeTestOutput[outputOffset + 5u] = asuint(float4(
            result.sampledEvaluation.throughput.value,
            result.sampledEvaluation.pdf));
    primeTestOutput[outputOffset + 6u] = uint4(
            result.evaluation.throughput.flags,
            result.sample.bsdfSample.throughput.flags,
            result.sampledEvaluation.throughput.flags,
            result.samplingFlags);
    primeTestOutput[outputOffset + 7u] = uint4(
            result.sample.volumeStack.count,
            asuint(result.sample.rayT),
            0u,
            0u);
    for (uint index = 8u; index < 12u; index++) {
        primeTestOutput[outputOffset + index] = uint4(0u);
    }
}

[numthreads(64, 1, 1)]
void main(uint3 dispatchThreadId : SV_DispatchThreadID) {
    uint caseIndex;
    if (!primeTestBegin(dispatchThreadId, caseIndex)) { return; }
    uint inputOffset = primeTestInputOffset(
            caseIndex, PRIME_COMPACT_TRANSMISSION_INPUT_WORDS);
    uint4 encoded[4];
    for (uint word = 0u; word < PRIME_COMPACT_TRANSMISSION_INPUT_WORDS; word++) {
        encoded[word] = primeTestInput[inputOffset + word];
    }
    primeCompactTransmissionWrite(
            caseIndex, primeCompactTransmissionRun(encoded));
}
