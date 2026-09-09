// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

#language slang 2026
module "service/bsdf/dielectric/conditional.slang";

import "service/bsdf/contract/sample.slang";
import "service/bsdf/support/sanitization.slang";
import "bsdf/compact/math/fresnel.slang";
import "bsdf/compact/math/microfacet_energy.slang";
import "bsdf/compact/dielectric/contract.slang";
import "service/bsdf/support/transmission_energy.slang";
import "service/bsdf/dielectric/state.slang";
import "service/bsdf/dielectric/traits.slang";
import "service/bsdf/support/conversion.slang";
import "bsdf/compact/contract/volume.slang";
import "bsdf/compact/contract/sample.slang";
import "bsdf/compact/math/spectrum.slang";

public struct PrimeMinecraftMirrorSplit {
    public float3 reflectance;
};

public PrimeMinecraftMirrorSplit primeMinecraftMirrorSplit(
        PrimeCompactTransmissionState state,
        float3 localView) {
    PrimeMinecraftMirrorSplit result;
    // The default Minecraft adapter keeps dielectric reflection achromatic. Retaining the color
    // term here makes the split remain correct if a future material decoder tints the interface.
    float directionalReflectance = state.geometryThinWalled != 0u
            ? primeOpenPbrMicrofacetDielectricMsCompensation(
                    primeTransmissionGgxEnergy(),
                    state.specularMicrofacet,
                    abs(localView.z),
                    state.specularFresnel.ior).z
            : state.transmissionMultipleScattering.z;
    result.reflectance = directionalReflectance * state.specularFresnel.color;
    if (!primeBsdfFinite(result.reflectance)
            || any(lessThan(result.reflectance, float3(0.0)))) {
        result.reflectance = float3(0.0);
    } else {
        result.reflectance = clamp(result.reflectance, float3(0.0), float3(1.0));
    }
    return result;
}

public PrimeOpenPbrVolumeStack primeMinecraftTransmissionStack(
        PrimeCompactTransmissionState state,
        PrimeOpenPbrVolumeStack volumeStack) {
    if (state.geometryThinWalled != 0u) {
        return volumeStack;
    }
    if (state.entering != 0u) {
        PrimeOpenPbrVolume volume = state.transmissionVolume;
        volume.ior = state.originalIor;
        primeOpenPbrStackPush(volumeStack, volume);
    } else if (volumeStack.count > 0u) {
        primeOpenPbrStackPop(volumeStack);
    }
    return volumeStack;
}

public float primeMinecraftInterfaceIor(
        PrimeCompactTransmissionState state) {
    // State initialization selected the medium on the other side of the boundary from the stack.
    return state.specularFresnel.ior;
}

public PrimeTransmissiveBsdfSample primeSampleMinecraftRefractedTransmissionFromState(
        PrimeCompactTransmissionState state,
        float3 outwardNormal,
        float3 viewDirection,
        float3 interfaceWeight,
        bool reflectOnTotalInternalReflection,
        PrimeOpenPbrVolumeStack volumeStack) {
    PrimeTransmissiveBsdfSample result;
    result.bsdfSample = primeInvalidBsdfSample();
    result.volumeStack = volumeStack;
    if (state.geometryThinWalled != 0u) {
        result.bsdfSample.direction = -viewDirection;
        result.bsdfSample.response = interfaceWeight * state.transmissionTint;
        result.bsdfSample.pdf = 1.0;
        result.bsdfSample.relativeEta = 1.0;
        result.bsdfSample.eventFlags = PRIME_BSDF_EVENT_TRANSMISSION
                | PRIME_BSDF_EVENT_DISCRETE;
        result.bsdfSample = primeSanitizeBsdfSample(result.bsdfSample);
        return result;
    }

    PrimeOpenPbrRefractResult refracted = primeOpenPbrDielectricRefract(
            primeMinecraftInterfaceIor(state),
            -viewDirection,
            outwardNormal);
    if (refracted.valid == 0u) {
        // A forced transmission proposal has no support under TIR. Guide-only continuations use
        // the sole valid discrete event instead; the primary transmission slot stays empty because
        // its paired Fresnel reflection already carries this energy.
        if (!reflectOnTotalInternalReflection) {
            return result;
        }
        result.bsdfSample.direction = reflect(-viewDirection, outwardNormal);
        result.bsdfSample.response = interfaceWeight;
        result.bsdfSample.pdf = 1.0;
        result.bsdfSample.relativeEta = 1.0;
        result.bsdfSample.eventFlags = PRIME_BSDF_EVENT_REFLECTION
                | PRIME_BSDF_EVENT_DISCRETE;
        result.bsdfSample = primeSanitizeBsdfSample(result.bsdfSample);
        return result;
    }

    result.bsdfSample.direction = refracted.wo;
    // Match RoboCute's radiance-mode discrete transmission: the event carries 1 / eta^2, while
    // path etaScale remains reserved for Russian-roulette survival probability.
    result.bsdfSample.response = interfaceWeight
            / primeOpenPbrSquare(refracted.relativeIor);
    result.bsdfSample.pdf = 1.0;
    result.bsdfSample.relativeEta = refracted.relativeIor;
    result.bsdfSample.eventFlags = PRIME_BSDF_EVENT_TRANSMISSION
            | PRIME_BSDF_EVENT_DISCRETE;
    result.volumeStack = primeMinecraftTransmissionStack(
            state, volumeStack);
    result.bsdfSample = primeSanitizeBsdfSample(result.bsdfSample);
    if (result.bsdfSample.eventFlags == 0u) {
        result.volumeStack = volumeStack;
    }
    return result;
}
