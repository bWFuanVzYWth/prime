// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

#language slang 2026
module "service/bsdf/dielectric/discrete.slang";

import "bsdf/compact/contract/flags.slang";
import "bsdf/compact/contract/sample.slang";
import "bsdf/compact/contract/volume.slang";
import "bsdf/compact/dielectric/contract.slang";
import "bsdf/compact/dielectric/discrete.slang";
import "bsdf/compact/math/frame.slang";
import "service/bsdf/contract/sample.slang";
import "service/bsdf/dielectric/conditional.slang";
import "service/bsdf/dielectric/state.slang";
import "service/bsdf/support/conversion.slang";
import "service/bsdf/support/sanitization.slang";

public PrimeTransmissiveBsdfSample primeSampleMinecraftTransmissionDiscreteFromState(
        PrimeCompactTransmissionState state,
        float3 viewDirection,
        float randomEvent,
        PrimeOpenPbrVolumeStack volumeStack) {
    PrimeTransmissiveBsdfSample result;
    result.bsdfSample = primeInvalidBsdfSample();
    result.volumeStack = volumeStack;
    float3 localView = primeOpenPbrOnbToLocal(state.onb, viewDirection);
    PrimeOpenPbrSampleResult sampled = primeCompactTransmissionDiscreteSample(
            localView,
            randomEvent,
            state,
            PRIME_OPENPBR_FLAG_ALL,
            volumeStack);
    if (!primeOpenPbrHasSample(sampled.bsdfSample)) {
        return result;
    }
    result.bsdfSample.direction = primeOpenPbrOnbToWorld(
            state.onb, sampled.bsdfSample.wo);
    result.bsdfSample.response = sampled.bsdfSample.throughput.value;
    result.bsdfSample.pdf = sampled.bsdfSample.pdf;
    result.bsdfSample.relativeEta = sampled.bsdfSample.eta;
    result.bsdfSample.eventFlags = primeOpenPbrToBsdfEventFlags(
            sampled.bsdfSample.throughput.flags);
    result.volumeStack = sampled.volumeStack;
    result.bsdfSample = primeSanitizeBsdfSample(result.bsdfSample);
    if (result.bsdfSample.eventFlags == 0u) {
        result.volumeStack = volumeStack;
    }
    return result;
}
