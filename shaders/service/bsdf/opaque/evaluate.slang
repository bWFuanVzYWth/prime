// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

#language slang 2026
module "service/bsdf/opaque/evaluate.slang";

import "service/bsdf/support/sanitization.slang";

import "bsdf/compact/opaque/evaluate.slang";
import "bsdf/compact/opaque/state.slang";
import "service/bsdf/contract/components.slang";
import "service/bsdf/opaque/state.slang";
import "bsdf/compact/contract/material.slang";
import "bsdf/compact/contract/volume.slang";
import "bsdf/compact/math/frame.slang";

public PrimeBsdfComponents primeEvaluateOpaqueComponents(
        float3 baseColor,
        float3 normal,
        float roughness,
        uint opticalControl,
        uint flags,
        float3 viewDirection,
        float3 scatterDirection,
        float rayT,
        PrimeOpenPbrVolume medium) {
    PrimeBsdfComponents result;
    result.diffuseResponse = float3(0.0);
    result.specularResponse = float3(0.0);
    result.pdf = 0.0;
    PrimeCompactOpaqueState state = primeOpaqueState(
            baseColor,
            normal,
            roughness,
            opticalControl,
            flags,
            viewDirection,
            rayT,
            medium);
    float3 localView = primeOpenPbrOnbToLocal(state.onb, viewDirection);
    float3 localScatter = primeOpenPbrOnbToLocal(state.onb, scatterDirection);
    PrimeCompactOpaqueComponents compact = primeCompactOpaqueEvaluateComponents(
            localView, localScatter, state);
    result.diffuseResponse = compact.diffuse;
    result.specularResponse = compact.specular;
    result.pdf = compact.pdf;
    result = primeSanitizeBsdfComponents(result);
    return result;
}
