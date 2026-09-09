// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.


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
import "bsdf/compact/math/spectrum.slang";
import "bsdf/compact/model/material.slang";

static const uint PRIME_SLAB_INPUT_WORDS = 1u;
static const uint PRIME_SLAB_WITNESS_WORDS = 8u;

PrimeOpenPbrVolumeStack primeSlabEmptyStack() {
    PrimeOpenPbrVolumeStack stack;
    stack.values[0].extinction = float3(0.0);
    stack.values[0].albedo = float3(0.0);
    stack.values[0].anisotropy = 0.0;
    stack.values[0].ior = 1.0;
    stack.values[1] = stack.values[0];
    stack.count = 0u;
    return stack;
}

PrimeOpenPbrMaterial primeSlabMaterial(float ior) {
    PrimeOpenPbrMaterial material = primeCompactMaterialFromMetallic(
            float3(1.0), 0.0, 0.0, float3(0.0, 0.0, 1.0));
    material.weight.transmission = 1.0;
    material.geometry.thinWalled = 0u;
    material.geometry.thickness = 1.0;
    material.specular.ior = ior;
    material.transmission.color = float3(1.0);
    material.transmission.depth = 0.0;
    material.transmission.scatter = float3(0.0);
    material.transmission.scatterAnisotropy = 0.0;
    material.transmission.dispersionScale = 0.0;
    return material;
}

PrimeCompactTransmissionState primeSlabState(
        PrimeOpenPbrMaterial material, float3 wi, PrimeOpenPbrVolumeStack stack) {
    PrimeCompactTransmissionState state = primeCompactTransmissionStateInit(
            primeTransmissionGgxEnergy(),
            material,
            wi,
            primeCompactInverseOutsideIor(wi.z, stack),
            4.0);
    state = primeCompactTransmissionInterfaceState(
            primeTransmissionGgxEnergy(), wi, state);
    return state;
}

void primeSlabClassify(inout uint mask, PrimeOpenPbrSample sampleValue) {
    mask |= primeTestClassify(sampleValue.wo);
    mask |= primeTestClassify(sampleValue.throughput.value);
    mask |= primeTestClassify(sampleValue.pdf);
    mask |= primeTestClassify(sampleValue.eta);
}

[numthreads(64, 1, 1)]
void main(uint3 dispatchThreadId : SV_DispatchThreadID)
{
    uint caseIndex;
    if (!primeTestBegin(dispatchThreadId, caseIndex)) return;
    uint inputOffset = primeTestInputOffset(caseIndex, PRIME_SLAB_INPUT_WORDS);
    float2 parameters = asfloat(primeTestInput[inputOffset].xy);
    float ior = parameters.x;
    float sine = parameters.y;
    float cosine = sqrt(max(0.0, 1.0 - sine * sine));
    float3 wi = float3(sine, 0.0, cosine);
    float3 randomValue = float3(0.5, 0.5, 0.0);
    PrimeOpenPbrMaterial material = primeSlabMaterial(ior);

    uint mask = 0u;
    PrimeOpenPbrVolumeStack emptyStack = primeSlabEmptyStack();
    PrimeCompactTransmissionState enterState = primeSlabState(material, wi, emptyStack);
    PrimeOpenPbrSampleResult entered = primeCompactTransmissionSample(
            wi, randomValue, enterState,
            PRIME_OPENPBR_FLAG_DELTA_TRANSMISSION, emptyStack);
    float3 exitWi = entered.bsdfSample.wo;
    PrimeCompactTransmissionState exitState = primeSlabState(
            material, exitWi, entered.volumeStack);
    PrimeOpenPbrSampleResult exited = primeCompactTransmissionSample(
            exitWi, randomValue, exitState,
            PRIME_OPENPBR_FLAG_DELTA_TRANSMISSION, entered.volumeStack);

    primeSlabClassify(mask, entered.bsdfSample);
    primeSlabClassify(mask, exited.bsdfSample);
    mask |= primeTestClassify(enterState.specularFresnel.ior);
    mask |= primeTestClassify(exitState.specularFresnel.ior);
    mask |= primeTestClassify(enterState.transmissionMultipleScattering);
    mask |= primeTestClassify(exitState.transmissionMultipleScattering);

    if (entered.bsdfSample.throughput.flags != PRIME_OPENPBR_FLAG_DELTA_TRANSMISSION
            || exited.bsdfSample.throughput.flags != PRIME_OPENPBR_FLAG_DELTA_TRANSMISSION) {
        mask |= PRIME_TEST_FAILURE_EVENT;
    }
    if (entered.volumeStack.count != 1u
            || exited.volumeStack.count != 0u
            || !primeTestClose(exited.rayT, 0.0, 0.0, 0.0)) {
        mask |= PRIME_TEST_FAILURE_STATE;
    }
    if (!primeTestClose(enterState.specularFresnel.ior, ior, 2.0e-6, 2.0e-6)
            || !primeTestClose(exitState.specularFresnel.ior, ior, 2.0e-6, 2.0e-6)
            || !primeTestClose(
                    entered.bsdfSample.eta * exited.bsdfSample.eta,
                    1.0,
                    2.0e-6,
                    2.0e-6)) {
        mask |= PRIME_TEST_FAILURE_RECIPROCITY;
    }
    if (!primeTestClose(exited.bsdfSample.wo, wi, 4.0e-6, 2.0e-5)) {
        mask |= PRIME_TEST_FAILURE_ROUND_TRIP;
    }
    float3 enterTransmission = entered.bsdfSample.throughput.value
            * primeOpenPbrSquare(entered.bsdfSample.eta);
    float3 exitTransmission = exited.bsdfSample.throughput.value
            * primeOpenPbrSquare(exited.bsdfSample.eta);
    if (!primeTestClose(enterTransmission, exitTransmission, 4.0e-6, 2.0e-5)) {
        mask |= PRIME_TEST_FAILURE_VALUE;
    }

    float criticalSine = 1.0 / ior;
    float tirSine = 0.5 * (criticalSine + 1.0);
    float tirCosine = sqrt(max(0.0, 1.0 - tirSine * tirSine));
    float3 tirWi = float3(-tirSine, 0.0, -tirCosine);
    PrimeCompactTransmissionState tirState = primeSlabState(
            material, tirWi, entered.volumeStack);
    PrimeOpenPbrSampleResult tir = primeCompactTransmissionSample(
            tirWi, randomValue, tirState,
            PRIME_OPENPBR_FLAG_DELTA_TRANSMISSION, entered.volumeStack);
    primeSlabClassify(mask, tir.bsdfSample);
    if (tir.bsdfSample.throughput.flags != PRIME_OPENPBR_FLAG_NONE
            || tir.volumeStack.count != 1u
            || !primeTestClose(tirState.specularFresnel.ior, ior, 2.0e-6, 2.0e-6)) {
        mask |= PRIME_TEST_FAILURE_EVENT;
    }

    uint outputWords = primeTestOutputWords();
    uint outputOffset = primeTestOutputOffset(caseIndex, outputWords);
    primeTestOutput[outputOffset] = uint4(mask, 0u, caseIndex, 0u);
    if (outputWords >= PRIME_SLAB_WITNESS_WORDS) {
        primeTestOutput[outputOffset + 1u] =
                primeTestInput[inputOffset];
        primeTestOutput[outputOffset + 2u] = asuint(float4(
                enterState.specularFresnel.ior,
                exitState.specularFresnel.ior,
                entered.bsdfSample.eta,
                exited.bsdfSample.eta));
        primeTestOutput[outputOffset + 3u] =
                asuint(float4(entered.bsdfSample.wo, entered.bsdfSample.pdf));
        primeTestOutput[outputOffset + 4u] =
                asuint(float4(exited.bsdfSample.wo, exited.bsdfSample.pdf));
        primeTestOutput[outputOffset + 5u] =
                asuint(float4(enterTransmission, entered.rayT));
        primeTestOutput[outputOffset + 6u] =
                asuint(float4(exitTransmission, exited.rayT));
        primeTestOutput[outputOffset + 7u] = uint4(
                entered.bsdfSample.throughput.flags,
                exited.bsdfSample.throughput.flags,
                tir.bsdfSample.throughput.flags,
                tir.volumeStack.count);
    }
}
