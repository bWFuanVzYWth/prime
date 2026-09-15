// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

#language slang 2026
module "service/bsdf/dielectric/sample.slang";

import "service/bsdf/contract/sample.slang";
import "service/bsdf/support/sanitization.slang";
import "bsdf/compact/math/microfacet_measure.slang";
import "bsdf/compact/dielectric/sample.slang";
import "bsdf/compact/dielectric/contract.slang";
import "service/bsdf/dielectric/state.slang";
import "service/bsdf/dielectric/traits.slang";
import "service/bsdf/dielectric/conditional.slang";
import "service/bsdf/dielectric/discrete.slang";
import "service/bsdf/support/conversion.slang";
import "bsdf/compact/contract/flags.slang";
import "bsdf/compact/contract/material.slang";
import "bsdf/compact/contract/volume.slang";
import "bsdf/compact/contract/sample.slang";
import "bsdf/compact/math/frame.slang";

public PrimeTransmissiveBsdfSample primeSampleMinecraftTransparentReflectionFromState(
        PrimeCompactTransmissionState state,
        PrimeMinecraftMirrorSplit mirror,
        float3 outwardNormal,
        float3 viewDirection,
        float3 localView,
        float3 sampleValue,
        PrimeOpenPbrVolume medium);

public PrimeTransmissiveBsdfSample primeSampleMinecraftTransparentTransmissionFromState(
        PrimeCompactTransmissionState state,
        PrimeMinecraftMirrorSplit mirror,
        float3 outwardNormal,
        float3 viewDirection,
        float3 localView,
        float3 sampleValue,
        PrimeOpenPbrVolume medium) {
    if (primeMinecraftTransmissionIsDiscrete(state)) {
        return primeSampleMinecraftRefractedTransmissionFromState(
                state,
                outwardNormal,
                viewDirection,
                float3(1.0) - mirror.reflectance,
                false,
                medium);
    }

    PrimeTransmissiveBsdfSample result;
    result.bsdfSample = primeInvalidBsdfSample();
    result.medium = medium;
    PrimeOpenPbrSampleResult sampled = primeCompactTransmissionSample(
            localView,
            sampleValue,
            state,
            PRIME_OPENPBR_FLAG_TRANSMISSION,
            medium);
    if (!primeOpenPbrHasSample(sampled.bsdfSample)) {
        return result;
    }
    // Sampling flags normalize the proposal over transmission while the response retains the
    // complete Fresnel and rough-microfacet energy.
    result.bsdfSample.direction = primeOpenPbrOnbToWorld(
            state.onb, sampled.bsdfSample.wo);
    result.bsdfSample.response = sampled.bsdfSample.throughput.value;
    result.bsdfSample.pdf = sampled.bsdfSample.pdf;
    result.bsdfSample.relativeEta = sampled.bsdfSample.eta;
    result.bsdfSample.eventFlags = primeOpenPbrToBsdfEventFlags(
            sampled.bsdfSample.throughput.flags);
    result.medium = sampled.medium;
    result.bsdfSample = primeSanitizeBsdfSample(result.bsdfSample);
    if (result.bsdfSample.eventFlags == 0u) {
        result.medium = medium;
    }
    return result;
}

// A queued single-path integrator samples the complete transmissive closure instead of forcing a
// hemisphere. The closure's reflection/transmission selection probability remains in the PDF,
// and only a sampled transmission event may replace the current medium.
public PrimeTransmissiveBsdfSample primeSampleMinecraftTransmissionCompleteFromState(
        PrimeCompactTransmissionState state,
        float3 baseColor,
        float opacity,
        uint materialControl,
        float3 viewDirection,
        float3 sampleValue,
        PrimeOpenPbrVolume medium) {
    PrimeTransmissiveBsdfSample result;
    result.bsdfSample = primeInvalidBsdfSample();
    result.medium = medium;
    float3 localView = primeOpenPbrOnbToLocal(state.onb, viewDirection);
    PrimeOpenPbrSampleResult sampled = primeCompactTransmissionSample(
            localView, sampleValue, state, PRIME_OPENPBR_FLAG_ALL, medium);
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
    result.medium = sampled.medium;
    result.bsdfSample = primeSanitizeBsdfSample(result.bsdfSample);
    if (result.bsdfSample.eventFlags == 0u) {
        result.medium = medium;
    }
    return result;
}

public PrimeTransmissiveBsdfSplit primeSampleMinecraftTransmissionSplitFromState(
        PrimeCompactTransmissionState state,
        float3 localView,
        PrimeMinecraftMirrorSplit mirror,
        float3 outwardNormal,
        float3 viewDirection,
        float3 reflectionSample,
        float3 baseColor,
        float opacity,
        uint materialControl,
        PrimeOpenPbrVolume medium) {
    // The first visible transparent interface evaluates one conditional reflection and one
    // conditional transmission event. Smooth interfaces remain exact; rough interfaces sample
    // the corresponding finite lobe without adding an event-selection probability.
    PrimeTransmissiveBsdfSplit result;
    result.reflection = primeSampleMinecraftTransparentReflectionFromState(
            state,
            mirror,
            outwardNormal,
            viewDirection,
            localView,
            reflectionSample,
            medium);
    result.transmission = primeSampleMinecraftTransparentTransmissionFromState(
            state,
            mirror,
            outwardNormal,
            viewDirection,
            localView,
            reflectionSample,
            medium);
    // Both paths are evaluated, so neither PDF contains a branch-selection probability.
    result.reflection.bsdfSample = primeSanitizeBsdfSample(result.reflection.bsdfSample);
    if (result.reflection.bsdfSample.eventFlags == 0u) {
        result.reflection.medium = medium;
    }
    result.transmission.bsdfSample = primeSanitizeBsdfSample(result.transmission.bsdfSample);
    if (result.transmission.bsdfSample.eventFlags == 0u) {
        result.transmission.medium = medium;
    }
    return result;
}

public PrimeTransmissiveBsdfSample primeSampleMinecraftTransparentReflectionFromState(
        PrimeCompactTransmissionState state,
        PrimeMinecraftMirrorSplit mirror,
        float3 outwardNormal,
        float3 viewDirection,
        float3 localView,
        float3 sampleValue,
        PrimeOpenPbrVolume medium) {
    PrimeTransmissiveBsdfSample result;
    result.bsdfSample = primeInvalidBsdfSample();
    result.medium = medium;
    if (state.geometryThinWalled == 0u
            && primeOpenPbrMicrofacetEffectivelySmooth(state.specularMicrofacet)) {
        // Closed Minecraft glass models the reflected interface as a discrete mirror. This
        // conditional response already carries the complete Fresnel energy.
        if (all(lessThanEqual(mirror.reflectance, float3(0.0)))) {
            return result;
        }
        result.bsdfSample.direction = reflect(-viewDirection, outwardNormal);
        result.bsdfSample.response = mirror.reflectance;
        result.bsdfSample.pdf = 1.0;
        result.bsdfSample.relativeEta = 1.0;
        result.bsdfSample.eventFlags = PRIME_BSDF_EVENT_REFLECTION
                | PRIME_BSDF_EVENT_DISCRETE;
        result.bsdfSample = primeSanitizeBsdfSample(result.bsdfSample);
        return result;
    }

    PrimeOpenPbrSampleResult sampled = primeCompactTransmissionSample(
            localView,
            sampleValue,
            state,
            PRIME_OPENPBR_FLAG_REFLECTION,
            medium);
    if (!primeOpenPbrHasSample(sampled.bsdfSample)) {
        return result;
    }
    // The conditional proposal is normalized over reflection. Its response retains the complete
    // reference Fresnel and rough-microfacet energy.
    result.bsdfSample.direction = primeOpenPbrOnbToWorld(
            state.onb, sampled.bsdfSample.wo);
    result.bsdfSample.response = sampled.bsdfSample.throughput.value;
    result.bsdfSample.pdf = sampled.bsdfSample.pdf;
    result.bsdfSample.relativeEta = 1.0;
    result.bsdfSample.eventFlags = primeOpenPbrToBsdfEventFlags(
            sampled.bsdfSample.throughput.flags);
    result.medium = sampled.medium;
    result.bsdfSample = primeSanitizeBsdfSample(result.bsdfSample);
    if (result.bsdfSample.eventFlags == 0u) {
        result.medium = medium;
    }
    return result;
}
