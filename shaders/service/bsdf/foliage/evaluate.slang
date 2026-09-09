// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

#language slang 2026
module "service/bsdf/foliage/evaluate.slang";

import "service/bsdf/contract/sample.slang";
import "service/bsdf/support/sanitization.slang";
import "bsdf/compact/foliage/evaluate.slang";
import "bsdf/compact/foliage/contract.slang";
import "service/bsdf/contract/components.slang";
import "service/bsdf/foliage/state.slang";
import "bsdf/compact/contract/flags.slang";
import "bsdf/compact/contract/material.slang";
import "bsdf/compact/contract/volume.slang";
import "bsdf/compact/math/frame.slang";

public BsdfEvaluation primeEvaluateMinecraftFoliage(
        float3 baseColor,
        float3 outwardNormal,
        float roughness,
        uint opticalControl,
        uint materialControl,
        float3 viewDirection,
        float3 scatterDirection,
        float rayT,
        PrimeOpenPbrVolumeStack volumeStack) {
    PrimeCompactFoliageState state = primeMinecraftFoliageState(
            baseColor,
            outwardNormal,
            roughness,
            opticalControl,
            materialControl,
            viewDirection,
            rayT,
            volumeStack);
    float3 localView = primeOpenPbrOnbToLocal(state.base.onb, viewDirection);
    float3 localScatter = primeOpenPbrOnbToLocal(state.base.onb, scatterDirection);
    PrimeCompactFoliageEvaluation evaluation = primeCompactFoliageEvaluate(
            localView, localScatter, state);
    BsdfEvaluation result = primeInvalidBsdfEvaluation();
    if (evaluation.throughput.flags != PRIME_OPENPBR_FLAG_NONE) {
        result.response = evaluation.throughput.value;
        result.pdf = evaluation.pdf;
    }
    result = primeSanitizeBsdfEvaluation(result);
    return result;
}

public PrimeBsdfComponents primeEvaluateMinecraftFoliageComponents(
        float3 baseColor,
        float3 outwardNormal,
        float roughness,
        uint opticalControl,
        uint materialControl,
        float3 viewDirection,
        float3 scatterDirection,
        float rayT,
        PrimeOpenPbrVolumeStack volumeStack) {
    PrimeBsdfComponents result;
    result.diffuseResponse = float3(0.0);
    result.specularResponse = float3(0.0);
    result.pdf = 0.0;
    PrimeCompactFoliageState state = primeMinecraftFoliageState(
            baseColor,
            outwardNormal,
            roughness,
            opticalControl,
            materialControl,
            viewDirection,
            rayT,
            volumeStack);
    float3 localView = primeOpenPbrOnbToLocal(state.base.onb, viewDirection);
    float3 localScatter = primeOpenPbrOnbToLocal(state.base.onb, scatterDirection);
    PrimeCompactFoliageComponents components = primeCompactFoliageEvaluateComponents(
            localView, localScatter, state);
    result.pdf = components.pdf;
    result.diffuseResponse = components.diffuse;
    result.specularResponse = components.specular;
    result = primeSanitizeBsdfComponents(result);
    return result;
}

