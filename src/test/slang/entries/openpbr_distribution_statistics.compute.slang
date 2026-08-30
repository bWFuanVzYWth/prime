#include "core/prime_test_abi.slang"
import "service/bsdf/support/transmission_energy.slang";
import "bsdf/compact/contract/material.slang";
import "bsdf/compact/opaque/state.slang";
import "bsdf/compact/opaque/lobes.slang";
import "bsdf/compact/opaque/traits.slang";
import "bsdf/compact/opaque/evaluate.slang";
import "bsdf/compact/opaque/sample.slang";
import "bsdf/compact/opaque/energy.slang";
import "bsdf/compact/dielectric/contract.slang";
import "bsdf/compact/dielectric/refract.slang";
import "bsdf/compact/dielectric/state.slang";
import "bsdf/compact/dielectric/evaluate.slang";
import "bsdf/compact/dielectric/sample.slang";
import "bsdf/compact/dielectric/energy.slang";

import "bsdf/compact/contract/flags.slang";
import "bsdf/compact/contract/volume.slang";
import "bsdf/compact/contract/sample.slang";
import "bsdf/compact/math/spectrum.slang";
import "bsdf/compact/model/material.slang";

static const uint PRIME_STAT_SAMPLE = 0u;
static const uint PRIME_STAT_INTEGRATE = 1u;
static const uint PRIME_STAT_LAMBERT = 0u;
static const uint PRIME_STAT_CONDUCTOR = 1u;
static const uint PRIME_STAT_TRANSMISSION = 2u;
static const uint PRIME_STAT_GRID_Z = 256u;
static const uint PRIME_STAT_GRID_PHI = 512u;
static const uint PRIME_STAT_OUTPUT_WORDS = 3u;

struct PrimeStatEvaluation {
    PrimeOpenPbrThroughput throughput;
    float pdf;
};

uint primeStatHash(uint value) {
    value ^= value >> 16u;
    value *= 0x7feb352du;
    value ^= value >> 15u;
    value *= 0x846ca68bu;
    value ^= value >> 16u;
    return value;
}

float primeStatUnit(uint value) {
    return float(primeStatHash(value) >> 8u) * (1.0 / 16777216.0);
}

float3 primeStatRandom(uint index, uint seed) {
    return float3(
            primeStatUnit(index ^ seed),
            primeStatUnit(index ^ seed ^ 0x9e3779b9u),
            primeStatUnit(index ^ seed ^ 0x3c6ef372u));
}

PrimeOpenPbrVolumeStack primeStatEmptyStack() {
    PrimeOpenPbrVolumeStack stack;
    PrimeOpenPbrVolume volume;
    volume.extinction = float3(0.0);
    volume.albedo = float3(0.0);
    volume.anisotropy = 0.0;
    volume.ior = 1.0;
    volume.mediumId = 0u;
    volume.iorEncoding = 0u;
    stack.values[0] = volume;
    stack.values[1] = volume;
    stack.count = 0u;
    return stack;
}

PrimeOpenPbrMaterial primeStatMaterial(
        uint suite,
        float roughness,
        float anisotropy,
        float ior,
        float3 color) {
    PrimeOpenPbrMaterial material = primeCompactMaterialFromMetallic(
            color,
            roughness,
            suite == PRIME_STAT_CONDUCTOR ? 1.0 : 0.0,
            float3(0.0, 0.0, 1.0));
    material.specular.roughnessAnisotropy = anisotropy;
    material.specular.ior = ior;
    if (suite == PRIME_STAT_LAMBERT) {
        material.weight.specular = 0.0;
    } else if (suite == PRIME_STAT_TRANSMISSION) {
        material.weight.transmission = 1.0;
        material.transmission.color = color;
        material.transmission.depth = 0.0;
        material.transmission.scatter = float3(0.0);
        material.transmission.scatterAnisotropy = 0.0;
    }
    return material;
}

PrimeOpenPbrSampleResult primeStatSample(
        uint suite,
        float3 wi,
        float3 randomValue,
        PrimeOpenPbrMaterial material) {
    if (suite == PRIME_STAT_TRANSMISSION) {
        PrimeCompactTransmissionState state = primeCompactTransmissionStateInit(
                primeTransmissionGgxEnergy(), material, wi, 1.0, 0.0);
        state = primeCompactTransmissionInterfaceState(
                primeTransmissionGgxEnergy(), wi, state);
        return primeCompactTransmissionSample(
                wi, randomValue, state, PRIME_OPENPBR_FLAG_ALL, primeStatEmptyStack());
    }
    PrimeCompactOpaqueState state = primeCompactOpaqueStateInit(
            primeTransmissionGgxEnergy(), material, wi, 1.0, 0.0);
    return primeCompactOpaqueSample(wi, randomValue, state, primeStatEmptyStack());
}

PrimeStatEvaluation primeStatEvaluate(
        uint suite,
        float3 wi,
        float3 wo,
        PrimeOpenPbrMaterial material) {
    PrimeStatEvaluation result;
    if (suite == PRIME_STAT_TRANSMISSION) {
        PrimeCompactTransmissionState state = primeCompactTransmissionStateInit(
                primeTransmissionGgxEnergy(), material, wi, 1.0, 0.0);
        state = primeCompactTransmissionInterfaceState(
                primeTransmissionGgxEnergy(), wi, state);
        PrimeCompactTransmissionEvaluation evaluated =
                primeCompactTransmissionEvaluate(
                        wi, wo, state, PRIME_OPENPBR_FLAG_ALL);
        result.throughput = evaluated.throughput;
        result.pdf = evaluated.pdf;
        return result;
    }
    PrimeCompactOpaqueState state = primeCompactOpaqueStateInit(
            primeTransmissionGgxEnergy(), material, wi, 1.0, 0.0);
    PrimeCompactOpaqueEvaluation evaluated = primeCompactOpaqueEvaluate(wi, wo, state);
    result.throughput = evaluated.throughput;
    result.pdf = evaluated.pdf;
    return result;
}

float3 primeStatEnergy(
        uint suite,
        float3 wi,
        PrimeOpenPbrMaterial material) {
    if (suite == PRIME_STAT_TRANSMISSION) {
        PrimeCompactTransmissionState state = primeCompactTransmissionStateInit(
                primeTransmissionGgxEnergy(), material, wi, 1.0, 0.0);
        state = primeCompactTransmissionInterfaceState(
                primeTransmissionGgxEnergy(), wi, state);
        return primeCompactTransmissionEnergy(
                primeTransmissionGgxEnergy(), wi, state, PRIME_OPENPBR_FLAG_ALL);
    }
    PrimeCompactOpaqueState state = primeCompactOpaqueStateInit(
            primeTransmissionGgxEnergy(), material, wi, 1.0, 0.0);
    PrimeCompactOpaqueEnergy energy = primeCompactOpaqueEnergy(wi, state);
    return energy.diffuse + energy.specular;
}

[numthreads(64, 1, 1)]
void main(uint3 dispatchThreadId : SV_DispatchThreadID) {
    uint caseIndex;
    if (!primeTestBegin(dispatchThreadId, caseIndex)) { return; }
    uint4 control = primeTestInput[1];
    float4 first = asfloat(primeTestInput[2]);
    float4 second = asfloat(primeTestInput[3]);
    uint mode = control.x;
    uint suite = control.y;
    uint seed = control.z;
    float roughness = first.x;
    float anisotropy = first.y;
    float viewCosine = first.z;
    float ior = first.w;
    float3 color = second.xyz;
    float viewSine = sqrt(max(0.0, 1.0 - viewCosine * viewCosine));
    float3 wi = float3(viewSine, 0.0, viewCosine);
    PrimeOpenPbrMaterial material = primeStatMaterial(
            suite, roughness, anisotropy, ior, color);
    uint outputOffset = caseIndex * PRIME_STAT_OUTPUT_WORDS;

    if (mode == PRIME_STAT_SAMPLE) {
        PrimeOpenPbrSampleResult sampled = primeStatSample(
                suite, wi, primeStatRandom(caseIndex, seed), material);
        primeTestOutput[outputOffset] = asuint(float4(
                sampled.bsdfSample.wo, sampled.bsdfSample.pdf));
        primeTestOutput[outputOffset + 1u] = uint4(
                asuint(sampled.bsdfSample.throughput.value.x),
                asuint(sampled.bsdfSample.throughput.value.y),
                asuint(sampled.bsdfSample.throughput.value.z),
                sampled.bsdfSample.throughput.flags);
        PrimeStatEvaluation evaluated = primeStatEvaluate(
                suite, wi, sampled.bsdfSample.wo, material);
        primeTestOutput[outputOffset + 2u] = asuint(float4(
                evaluated.throughput.value, evaluated.pdf));
        return;
    }

    uint zIndex = caseIndex / PRIME_STAT_GRID_PHI;
    uint phiIndex = caseIndex % PRIME_STAT_GRID_PHI;
    float zUnit = (float(zIndex) + 0.5) / float(PRIME_STAT_GRID_Z);
    float z = suite == PRIME_STAT_TRANSMISSION ? zUnit * 2.0 - 1.0 : zUnit;
    float phi = (float(phiIndex) + 0.5)
            * (2.0 * PRIME_OPENPBR_PI / float(PRIME_STAT_GRID_PHI));
    float radius = sqrt(max(0.0, 1.0 - z * z));
    float3 wo = float3(radius * cos(phi), radius * sin(phi), z);
    PrimeStatEvaluation evaluated = primeStatEvaluate(suite, wi, wo, material);
    primeTestOutput[outputOffset] = asuint(float4(
            evaluated.throughput.value, evaluated.pdf));
    primeTestOutput[outputOffset + 1u] = asuint(float4(
            primeStatEnergy(suite, wi, material), 0.0));
    primeTestOutput[outputOffset + 2u] = uint4(0u);
}
