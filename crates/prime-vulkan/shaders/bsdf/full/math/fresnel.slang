// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

#language slang 2026
module "bsdf/full/math/fresnel.slang";

import "bsdf/full/contract/sample.slang";
import "bsdf/full/math/spectrum.slang";

// Exact Fresnel subset required by Prime's compact OpenPBR topologies.
// Thin film is omitted because the compact support predicate rejects it.

public PrimeFullOpenPbrFloatPair primeFullOpenPbrDielectricRsRp(float ior, float cosineTheta) {
    float eta = ior;
    if (cosineTheta < 0.0) {
        cosineTheta = -cosineTheta;
        eta = 1.0 / eta;
    }
    float eta2 = eta * eta;
    float t0 = cosineTheta * cosineTheta + eta2 - 1.0;
    float t1 = t0 > 0.0 ? sqrt(t0) : 0.0;
    // Multiply Rp's numerator and denominator by eta; IOR is strictly positive.
    float t2 = eta2 * cosineTheta;
    PrimeFullOpenPbrFloatPair result;
    result.first = (cosineTheta - t1) / (cosineTheta + t1);
    result.second = (t1 - t2) / (t1 + t2);
    return result;
}

public PrimeFullOpenPbrFloatPair primeFullOpenPbrDielectricReflectance(float ior,
                                                                       float cosineTheta) {
    PrimeFullOpenPbrFloatPair result = primeFullOpenPbrDielectricRsRp(ior, cosineTheta);
    result.first *= result.first;
    result.second *= result.second;
    return result;
}

public float primeFullOpenPbrDielectricUnpolarized(float ior, float cosineTheta) {
    PrimeFullOpenPbrFloatPair value = primeFullOpenPbrDielectricReflectance(ior, cosineTheta);
    return 0.5 * (value.first + value.second);
}

public PrimeFullOpenPbrRefractResult primeFullOpenPbrDielectricRefract(float ior, float3 wi,
                                                                       float3 normal) {
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
    PrimeFullOpenPbrRefractResult result;
    result.valid = t0 > 0.0 ? 1u : 0u;
    result.relativeIor = 1.0 / inverseEta;
    result.wo = inverseEta * wi - (inverseEta * cosineTheta + sqrt(max(t0, 0.0))) * normal;
    return result;
}

float3 primeFullOpenPbrSchlickF82Tint(float3 f0, float3 f82, float3 f90, float weight,
                                      float cosineTheta) {
    const float cosineThetaMax = 1.0 / 7.0;
    const float cosineThetaFactor =
            1.0 / (cosineThetaMax * primeFullOpenPbrPow6(1.0 - cosineThetaMax));
    float3 a = lerp(f0, f90, primeFullOpenPbrPow5(1.0 - cosineThetaMax)) * (float3(1.0) - f82) *
               cosineThetaFactor;
    return clamp((lerp(f0, f90, primeFullOpenPbrPow5(1.0 - cosineTheta)) -
                  a * cosineTheta * primeFullOpenPbrPow6(1.0 - cosineTheta)) *
                         weight,
                 float3(0.0), float3(1.0));
}

public float3 primeFullOpenPbrSpecularFresnelUnpolarized(PrimeFullOpenPbrSpecularFresnel fresnel,
                                                         float cosineTheta) {
    return float3(primeFullOpenPbrDielectricUnpolarized(fresnel.energyIor, cosineTheta)) *
           fresnel.color;
}

public PrimeFullOpenPbrVecPair
primeFullOpenPbrSpecularFresnelRt(PrimeFullOpenPbrSpecularFresnel fresnel, float cosineTheta) {
    bool tint = true;
    if (cosineTheta < 0.0) {
        float t0 = 1.0 - (1.0 - cosineTheta * cosineTheta) * fresnel.ior * fresnel.ior;
        if (t0 > 0.0) {
            cosineTheta = sqrt(t0);
        } else {
            PrimeFullOpenPbrVecPair totalInternalReflection;
            totalInternalReflection.first = float3(1.0);
            totalInternalReflection.second = float3(0.0);
            return totalInternalReflection;
        }
        tint = false;
    }
    float3 reflection =
            float3(primeFullOpenPbrDielectricUnpolarized(fresnel.energyIor, cosineTheta));
    PrimeFullOpenPbrVecPair result;
    result.second = float3(1.0) - reflection;
    result.first = tint ? reflection * fresnel.color : reflection;
    return result;
}

public PrimeFullOpenPbrRefractResult
primeFullOpenPbrSpecularFresnelRefract(PrimeFullOpenPbrSpecularFresnel fresnel, float3 wi,
                                       float3 normal) {
    return primeFullOpenPbrDielectricRefract(fresnel.ior, wi, normal);
}

public float3 primeFullOpenPbrConductorFresnelUnpolarized(PrimeFullOpenPbrConductorFresnel fresnel,
                                                          float cosineTheta) {
    return primeFullOpenPbrSchlickF82Tint(fresnel.f0, fresnel.f82, fresnel.f90, fresnel.weight,
                                          cosineTheta);
}
