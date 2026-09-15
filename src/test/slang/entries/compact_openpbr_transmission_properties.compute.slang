// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

#include "core/prime_test_abi.slang"
import "bsdf/compact/contract/volume_sample.slang";
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
import "contract/medium.slang";
import "service/bsdf/support/medium_conversion.slang";
#include "core/compact_bsdf_test.slang"

static const uint PRIME_COMPACT_TRANSMISSION_INPUT_WORDS = 4u;

uint primeVolumeRepresentationFailures()
{
    PrimeOpenPbrTransmission transmission = {};
    transmission.color = exp(-float3(0.5, 1.0, 2.0));
    transmission.scatter = float3(0.25, 0.5, 1.0);
    transmission.scatterAnisotropy = 0.4;
    transmission.depth = 2.0;
    PrimeOpenPbrVolume scattering = primeOpenPbrVolumeFromTransmission(transmission);
    if (any(abs(scattering.extinction - float3(0.25, 0.5, 1.0)) > 1.0e-6)
            || any(abs(scattering.albedo - float3(0.5)) > 1.0e-6)
            || scattering.anisotropy != 0.4) {
        return PRIME_TEST_FAILURE_STATE;
    }
    PrimeMedium incident = primeVacuumMedium();
    incident.extinction = float3(0.0, 0.1, 37.0);
    incident.ior = 1.333;
    incident.mediumId = 0xfedcba98u;
    incident.iorEncoding = 0x100u;
    PrimeOpenPbrVolume compact = primeOpenPbrVolumeFromMedium(incident);
    PrimeMedium restored = primeMediumFromOpenPbrVolume(compact);
    if (any(compact.albedo != float3(0.0)) || compact.anisotropy != 0.0
            || any(asuint(restored.extinction) != asuint(incident.extinction))
            || asuint(restored.ior) != asuint(incident.ior)
            || restored.mediumId != incident.mediumId
            || restored.iorEncoding != incident.iorEncoding
            || sizeof(PrimeMedium) != 24u) {
        return PRIME_TEST_FAILURE_STATE;
    }
    return 0u;
}

struct PrimeCompactTransmissionTestResult {
    uint mask;
    uint kind;
    uint samplingFlags;
    PrimeCompactTransmissionEvaluation evaluation;
    PrimeCompactTransmissionEvaluation sampledEvaluation;
    PrimeOpenPbrSampleResult sample;
    float3 energy;
};

PrimeOpenPbrVolume primeCompactTransmissionMedium(bool exiting, PrimeOpenPbrMaterial material) {
    if (!exiting) return primeOpenPbrVacuumVolume();
    PrimeOpenPbrVolume medium = primeOpenPbrVolumeFromTransmission(material.transmission);
    medium.ior = material.specular.ior;
    medium.mediumId = 1u;
    return medium;
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
    float roughness = primeCompactTestParam(encoded, 0u);
    float ior = primeCompactTestParam(encoded, 1u);
    float wiCosine = abs(primeCompactTestParam(encoded, 2u));
    if (exiting) { wiCosine = -wiCosine; }
    float3 wi = primeCompactTestDirection(
            wiCosine, primeCompactTestParam(encoded, 3u));
    float3 wo = primeCompactTestDirection(
            primeCompactTestParam(encoded, 4u),
            primeCompactTestParam(encoded, 5u));
    float3 randomValue = float3(
            primeCompactTestParam(encoded, 6u),
            primeCompactTestParam(encoded, 7u),
            primeCompactTestParam(encoded, 8u));
    float3 color = float3(
            primeCompactTestParam(encoded, 9u),
            primeCompactTestParam(encoded, 10u),
            primeCompactTestParam(encoded, 11u));
    float inverseOutsideIor = primeCompactTestParam(encoded, 12u);
    float rayT = primeCompactTestParam(encoded, 13u);
    bool thinPhysicalExit = thinWalled
            && primeCompactTestParam(encoded, 14u) >= 0.5;

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

    PrimeOpenPbrVolume medium = primeCompactTransmissionMedium(exiting, material);
    PrimeOpenPbrVolume outside = primeOpenPbrVacuumVolume();
    outside.ior = 1.0 / inverseOutsideIor;
    uint originalMediumId = medium.mediumId;
    PrimeCompactTransmissionState state = primeCompactTransmissionStateInit(
            primeTransmissionGgxEnergy(), material, wi, outside, rayT);
    if (!exiting) state.transmissionVolume.mediumId = 1u;
    state = primeCompactTransmissionInterfaceState(
            primeTransmissionGgxEnergy(), wi, state);
    if (thinPhysicalExit) { state.entering = 0u; }

    result.evaluation = primeCompactTransmissionEvaluate(
            wi, wo, state, result.samplingFlags);
    result.sample = primeCompactTransmissionSample(
            wi, randomValue, state, result.samplingFlags, medium);
    result.energy = primeCompactTransmissionEnergy(
            primeTransmissionGgxEnergy(), wi, state, result.samplingFlags);
    result.sampledEvaluation.throughput = primeOpenPbrZeroThroughput();
    result.sampledEvaluation.pdf = 0.0;

    primeCompactTestClassify(result.mask, state.specularMicrofacet.alpha);
    primeCompactTestClassify(result.mask, state.transmissionMicrofacet.alpha);
    primeCompactTestClassify(result.mask, state.specularFresnel.ior);
    primeCompactTestClassify(result.mask, state.specularFresnel.energyIor);
    primeCompactTestClassify(result.mask, state.transmissionMultipleScattering);
    primeCompactTestClassify(result.mask, result.evaluation.throughput.value);
    primeCompactTestClassify(result.mask, result.evaluation.pdf);
    primeCompactTestClassify(result.mask, result.energy);
    if (!primeCompactTestNonnegative(result.evaluation.throughput.value)
            || !primeCompactTestNonnegative(result.evaluation.pdf)
            || !primeCompactTestNonnegative(result.energy)
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
    uint expectedMediumId = originalMediumId;
    float expectedRayT = rayT;
    if (!thinWalled && transmitted) {
        expectedMediumId = exiting ? 0u : 1u;
        if (exiting) { expectedRayT = 0.0; }
    }
    if ((accepted
            && (result.sample.medium.mediumId != expectedMediumId
            || !primeTestClose(result.sample.rayT, expectedRayT, 0.0, 0.0)))
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
            result.sample.medium.mediumId,
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
    PrimeCompactTransmissionTestResult result = primeCompactTransmissionRun(encoded);
    if (caseIndex == 0u) result.mask |= primeVolumeRepresentationFailures();
    primeCompactTransmissionWrite(caseIndex, result);
}
