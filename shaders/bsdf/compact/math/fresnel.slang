// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

#language slang 2026
module "bsdf/compact/math/fresnel.slang";

import "bsdf/compact/contract/sample.slang";
import "bsdf/compact/math/spectrum.slang";


// Exact Fresnel subset required by Prime's compact OpenPBR topologies.
// Thin film is omitted because the compact support predicate rejects it.

public PrimeOpenPbrFloatPair primeOpenPbrDielectricRsRp(float ior, float cosineTheta) {
    float eta = ior;
    if (cosineTheta < 0.0) {
        cosineTheta = -cosineTheta;
        eta = 1.0 / eta;
    }
    float t0 = cosineTheta * cosineTheta + eta * eta - 1.0;
    t0 = t0 > 0.0 ? sqrt(t0) / eta : 0.0;
    float t1 = eta * t0;
    float t2 = eta * cosineTheta;
    PrimeOpenPbrFloatPair result;
    result.first = (cosineTheta - t1) / (cosineTheta + t1);
    result.second = (t0 - t2) / (t0 + t2);
    return result;
}

public PrimeOpenPbrFloatPair primeOpenPbrDielectricReflectance(float ior, float cosineTheta) {
    PrimeOpenPbrFloatPair result = primeOpenPbrDielectricRsRp(ior, cosineTheta);
    result.first *= result.first;
    result.second *= result.second;
    return result;
}

public float primeOpenPbrDielectricUnpolarized(float ior, float cosineTheta) {
    PrimeOpenPbrFloatPair value = primeOpenPbrDielectricReflectance(ior, cosineTheta);
    return 0.5 * (value.first + value.second);
}

public PrimeOpenPbrRefractResult primeOpenPbrDielectricRefract(float ior, float3 wi, float3 normal) {
    float cosineTheta = dot(normal, wi);
    float inverseEta;
    if (cosineTheta < 0.0) {
        inverseEta = 1.0 / ior;
    } else {
        cosineTheta = -cosineTheta;
        normal = -normal;
        inverseEta = ior;
    }
    float t0 = 1.0 - (1.0 - cosineTheta * cosineTheta) * inverseEta * inverseEta;
    PrimeOpenPbrRefractResult result;
    result.valid = t0 > 0.0 ? 1u : 0u;
    result.relativeIor = 1.0 / inverseEta;
    result.wo = inverseEta * wi
            - (inverseEta * cosineTheta + sqrt(max(t0, 0.0))) * normal;
    return result;
}

float3 primeOpenPbrSchlickF82Tint(
        float3 f0, float3 f82, float3 f90, float weight, float cosineTheta) {
    const float cosineThetaMax = 1.0 / 7.0;
    const float cosineThetaFactor = 1.0
            / (cosineThetaMax * primeOpenPbrPow6(1.0 - cosineThetaMax));
    float3 a = lerp(f0, f90, primeOpenPbrPow5(1.0 - cosineThetaMax))
            * (float3(1.0) - f82) * cosineThetaFactor;
    return clamp((lerp(f0, f90, primeOpenPbrPow5(1.0 - cosineTheta))
            - a * cosineTheta * primeOpenPbrPow6(1.0 - cosineTheta)) * weight,
            float3(0.0), float3(1.0));
}

public float3 primeOpenPbrSpecularFresnelUnpolarized(
        PrimeOpenPbrSpecularFresnel fresnel,
        float3 lambdaNm,
        uint spectrumed,
        float cosineTheta) {
    return float3(primeOpenPbrDielectricUnpolarized(fresnel.energyIor, cosineTheta))
            * fresnel.color;
}

public PrimeOpenPbrVecPair primeOpenPbrSpecularFresnelRt(
        PrimeOpenPbrSpecularFresnel fresnel,
        float3 lambdaNm,
        uint spectrumed,
        float cosineTheta) {
    bool tint = true;
    if (cosineTheta < 0.0) {
        float t0 = 1.0 - (1.0 - cosineTheta * cosineTheta) * fresnel.ior * fresnel.ior;
        if (t0 > 0.0) {
            cosineTheta = sqrt(t0);
        } else {
            PrimeOpenPbrVecPair totalInternalReflection;
            totalInternalReflection.first = float3(1.0);
            totalInternalReflection.second = float3(0.0);
            return totalInternalReflection;
        }
        tint = false;
    }
    float3 reflection = float3(primeOpenPbrDielectricUnpolarized(fresnel.energyIor, cosineTheta));
    PrimeOpenPbrVecPair result;
    result.second = float3(1.0) - reflection;
    result.first = tint ? reflection * fresnel.color : reflection;
    return result;
}

public PrimeOpenPbrRefractResult primeOpenPbrSpecularFresnelRefract(
        PrimeOpenPbrSpecularFresnel fresnel, float3 wi, float3 normal) {
    return primeOpenPbrDielectricRefract(fresnel.ior, wi, normal);
}

public float3 primeOpenPbrConductorFresnelUnpolarized(
        PrimeOpenPbrConductorFresnel fresnel,
        float3 lambdaNm,
        uint spectrumed,
        float cosineTheta) {
    return primeOpenPbrSchlickF82Tint(
            fresnel.f0, fresnel.f82, fresnel.f90, fresnel.weight, cosineTheta);
}
