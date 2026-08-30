#language slang 2026
module "service/bsdf/dielectric/evaluate.slang";

import "service/bsdf/contract/sample.slang";
import "service/bsdf/support/sanitization.slang";
import "bsdf/compact/dielectric/evaluate.slang";
import "bsdf/compact/dielectric/contract.slang";
import "service/bsdf/dielectric/state.slang";
import "service/bsdf/dielectric/traits.slang";
import "bsdf/compact/contract/flags.slang";
import "bsdf/compact/contract/material.slang";
import "bsdf/compact/contract/volume.slang";
import "bsdf/compact/math/frame.slang";

public BsdfEvaluation primeEvaluateMinecraftTransparentReflection(
        float3 baseColor,
        float opacity,
        float3 outwardNormal,
        uint materialControl,
        float roughness,
        uint opticalControl,
        float3 viewDirection,
        float3 scatterDirection,
        float rayT,
        PrimeOpenPbrVolumeStack volumeStack,
        float3 adjacentBaseColor,
        uint adjacentInterfaceControl,
        bool usesAirGap) {
    PrimeCompactTransmissionState state = primeMinecraftBoundaryTransmissionState(
            baseColor,
            opacity,
            outwardNormal,
            materialControl,
            roughness,
            opticalControl,
            viewDirection,
            rayT,
            volumeStack,
            0u,
            adjacentBaseColor,
            adjacentInterfaceControl,
            0u,
            usesAirGap);
    float3 localView = primeOpenPbrOnbToLocal(state.onb, viewDirection);
    float3 localScatter = primeOpenPbrOnbToLocal(state.onb, scatterDirection);
    if (localView.z * localScatter.z < 0.0
            || primeMinecraftTransmissionIsDiscrete(state)) {
        // A coherent reflection has no finite solid-angle evaluation.
        return primeInvalidBsdfEvaluation();
    }
    PrimeCompactTransmissionEvaluation evaluation = primeCompactTransmissionEvaluate(
            localView, localScatter, state, PRIME_OPENPBR_FLAG_REFLECTION);
    BsdfEvaluation result = primeInvalidBsdfEvaluation();
    if (evaluation.throughput.flags != PRIME_OPENPBR_FLAG_NONE) {
        result.response = evaluation.throughput.value;
        result.pdf = evaluation.pdf;
    }
    result = primeSanitizeBsdfEvaluation(result);
    return result;
}

public BsdfEvaluation primeEvaluateMinecraftTransparentReflection(
        float3 baseColor,
        float opacity,
        float3 outwardNormal,
        uint materialControl,
        float roughness,
        uint opticalControl,
        float3 viewDirection,
        float3 scatterDirection,
        float rayT,
        PrimeOpenPbrVolumeStack volumeStack) {
    return primeEvaluateMinecraftTransparentReflection(
            baseColor,
            opacity,
            outwardNormal,
            materialControl,
            roughness,
            opticalControl,
            viewDirection,
            scatterDirection,
            rayT,
            volumeStack,
            float3(0.0),
            0u,
            false);
}

public BsdfEvaluation primeEvaluateMinecraftTransparentTransmission(
        float3 baseColor,
        float opacity,
        float3 outwardNormal,
        uint materialControl,
        float roughness,
        uint opticalControl,
        float3 viewDirection,
        float3 scatterDirection,
        float rayT,
        PrimeOpenPbrVolumeStack volumeStack,
        float3 adjacentBaseColor,
        uint adjacentInterfaceControl,
        bool usesAirGap) {
    PrimeCompactTransmissionState state = primeMinecraftBoundaryTransmissionState(
            baseColor,
            opacity,
            outwardNormal,
            materialControl,
            roughness,
            opticalControl,
            viewDirection,
            rayT,
            volumeStack,
            0u,
            adjacentBaseColor,
            adjacentInterfaceControl,
            0u,
            usesAirGap);
    float3 localView = primeOpenPbrOnbToLocal(state.onb, viewDirection);
    float3 localScatter = primeOpenPbrOnbToLocal(state.onb, scatterDirection);
    if (localView.z * localScatter.z >= 0.0
            || primeMinecraftTransmissionIsDiscrete(state)) {
        return primeInvalidBsdfEvaluation();
    }
    PrimeCompactTransmissionEvaluation evaluation = primeCompactTransmissionEvaluate(
            localView, localScatter, state, PRIME_OPENPBR_FLAG_TRANSMISSION);
    BsdfEvaluation result = primeInvalidBsdfEvaluation();
    if (evaluation.throughput.flags != PRIME_OPENPBR_FLAG_NONE) {
        result.response = evaluation.throughput.value;
        result.pdf = evaluation.pdf;
    }
    result = primeSanitizeBsdfEvaluation(result);
    return result;
}

public BsdfEvaluation primeEvaluateMinecraftTransparentTransmission(
        float3 baseColor,
        float opacity,
        float3 outwardNormal,
        uint materialControl,
        float roughness,
        uint opticalControl,
        float3 viewDirection,
        float3 scatterDirection,
        float rayT,
        PrimeOpenPbrVolumeStack volumeStack) {
    return primeEvaluateMinecraftTransparentTransmission(
            baseColor,
            opacity,
            outwardNormal,
            materialControl,
            roughness,
            opticalControl,
            viewDirection,
            scatterDirection,
            rayT,
            volumeStack,
            float3(0.0),
            0u,
            false);
}


public BsdfEvaluation primeEvaluateMinecraftTransmissionCompleteFromState(
        PrimeCompactTransmissionState state,
        float3 baseColor,
        float opacity,
        uint materialControl,
        float3 viewDirection,
        float3 scatterDirection) {
    float3 localView = primeOpenPbrOnbToLocal(state.onb, viewDirection);
    float3 localScatter = primeOpenPbrOnbToLocal(
            state.onb, scatterDirection);
    PrimeCompactTransmissionEvaluation evaluated = primeCompactTransmissionEvaluate(
            localView, localScatter, state, PRIME_OPENPBR_FLAG_ALL);
    BsdfEvaluation result = primeInvalidBsdfEvaluation();
    if (evaluated.throughput.flags != PRIME_OPENPBR_FLAG_NONE) {
        result.response = evaluated.throughput.value;
        result.pdf = evaluated.pdf;
    }
    return primeSanitizeBsdfEvaluation(result);
}
