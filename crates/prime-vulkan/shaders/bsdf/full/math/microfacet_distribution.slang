// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

#language slang 2026
module "bsdf/full/math/microfacet_distribution.slang";

import "bsdf/full/math/microfacet_energy.slang";
import "bsdf/full/math/microfacet_measure.slang";
import "bsdf/full/contract/flags.slang";
import "bsdf/full/contract/material.slang";
import "bsdf/full/contract/sample.slang";
import "bsdf/full/math/spectrum.slang";

// Exact production-reachable microfacet subset. Generic full-state entry points
// outside the compact opaque and transmission topologies are intentionally absent.

public float primeFullOpenPbrMicrofacetLambda(PrimeFullOpenPbrMicrofacet microfacet, float3 value) {
    return (sqrt(1.0 +
                 primeFullOpenPbrReduceSum(primeFullOpenPbrSquare(microfacet.alpha * value.xy)) /
                         primeFullOpenPbrSquare(value.z)) -
            sign(value.z)) *
           0.5;
}

public float primeFullOpenPbrMicrofacetD(PrimeFullOpenPbrMicrofacet microfacet, float3 halfVector) {
    float denominator =
            primeFullOpenPbrReduceSum(primeFullOpenPbrSquare(halfVector.xy / microfacet.alpha)) +
            primeFullOpenPbrSquare(halfVector.z);
    float result = 1.0 / (PRIME_FULL_OPENPBR_PI * microfacet.alpha.x * microfacet.alpha.y *
                          primeFullOpenPbrSquare(denominator));
    return result * halfVector.z > 1.0e-20 ? result : 0.0;
}

float primeFullOpenPbrMicrofacetG2r(PrimeFullOpenPbrMicrofacet microfacet, float3 wi, float3 wo) {
    if (wi.z < 0.0) {
        wi = -wi;
        wo = -wo;
    }
    return 1.0 / (primeFullOpenPbrMicrofacetLambda(microfacet, -wi) +
                  primeFullOpenPbrMicrofacetLambda(microfacet, wo));
}

float primeFullOpenPbrLogGamma(float value) {
    const float gam0 = 1.0 / 12.0;
    const float gam1 = 1.0 / 30.0;
    const float gam2 = 53.0 / 210.0;
    const float gam3 = 195.0 / 371.0;
    const float gam4 = 22999.0 / 22737.0;
    const float gam5 = 29944523.0 / 19733142.0;
    const float gam6 = 109535241009.0 / 48264275462.0;
    return 0.5 * log(2.0 * PRIME_FULL_OPENPBR_PI) - value + (value - 0.5) * log(value) +
           gam0 / (value +
                   gam1 / (value +
                           gam2 / (value +
                                   gam3 / (value +
                                           gam4 / (value + gam5 / (value + gam6 / value))))));
}

float primeFullOpenPbrGamma(float value) {
    return exp(primeFullOpenPbrLogGamma(value + 5.0)) /
           (value * (value + 1.0) * (value + 2.0) * (value + 3.0) * (value + 4.0));
}

float primeFullOpenPbrBeta(float first, float second) {
    return primeFullOpenPbrGamma(first) * primeFullOpenPbrGamma(second) /
           primeFullOpenPbrGamma(first + second);
}

public float primeFullOpenPbrMicrofacetG2t(PrimeFullOpenPbrMicrofacet microfacet, float3 wi,
                                           float3 wo) {
    if (wi.z < 0.0) {
        wi = -wi;
        wo = -wo;
    }
    return primeFullOpenPbrBeta(primeFullOpenPbrMicrofacetLambda(microfacet, -wi),
                                primeFullOpenPbrMicrofacetLambda(microfacet, wo));
}

public float3 primeFullOpenPbrMicrofacetSample(PrimeFullOpenPbrMicrofacet microfacet, float3 wi,
                                               float2 sampleValue, bool reflectOnly) {
    float3 visibleHalf = normalize(float3(microfacet.alpha * wi.xy, wi.z));
    float phi = 2.0 * PRIME_FULL_OPENPBR_PI * sampleValue.x;
    float k = 1.0;
    if (reflectOnly) {
        float a = clamp(min(microfacet.alpha.x, microfacet.alpha.y), 0.0, 1.0);
        float s = 1.0 + length(wi.xy);
        float a2 = a * a;
        float s2 = s * s;
        k = (s2 - a2 * s2) / (s2 + a2 * wi.z * wi.z);
    }
    float z = lerp(1.0, -k * visibleHalf.z, sampleValue.y);
    float sine = sqrt(clamp(1.0 - z * z, 0.0, 1.0));
    float3 halfVector = float3(sine * cos(phi), sine * sin(phi), z) + visibleHalf;
    return normalize(float3(microfacet.alpha * halfVector.xy, max(0.0, halfVector.z)));
}

public float primeFullOpenPbrMicrofacetPdf(PrimeFullOpenPbrMicrofacet microfacet, float3 wi,
                                           float3 halfVector, bool reflectOnly) {
    float lengthV = length(float3(microfacet.alpha * wi.xy, wi.z));
    float k = 1.0;
    if (reflectOnly) {
        float a = clamp(min(microfacet.alpha.x, microfacet.alpha.y), 0.0, 1.0);
        float s = 1.0 + length(wi.xy);
        float a2 = a * a;
        float s2 = s * s;
        k = (s2 - a2 * s2) / (s2 + a2 * wi.z * wi.z);
    }
    return 2.0 * primeFullOpenPbrMicrofacetD(microfacet, halfVector) * dot(wi, halfVector) /
           (k * wi.z + lengthV);
}

public struct PrimeFullOpenPbrReflectiveEvalBase {
    public float factor;
    public float microCosine;
    public uint flags;
};

public struct PrimeFullOpenPbrReflectiveSampleBase {
    public PrimeFullOpenPbrSample bsdfSample;
    public float microCosine;
};

public PrimeFullOpenPbrReflectiveEvalBase
primeFullOpenPbrReflectiveEvalBase(float3 wi, float3 wo, PrimeFullOpenPbrMicrofacet microfacet,
                                   uint samplingFlags, uint checkFlags) {
    PrimeFullOpenPbrReflectiveEvalBase result;
    result.factor = 0.0;
    result.microCosine = 0.0;
    result.flags = PRIME_FULL_OPENPBR_FLAG_NONE;
    if (primeFullOpenPbrMicrofacetEffectivelySmooth(microfacet) || wi.z < 0.0 || wo.z <= 0.0 ||
        (samplingFlags & (checkFlags & PRIME_FULL_OPENPBR_FLAG_SPECULAR)) == 0u) {
        return result;
    }
    float3 halfVector = wi + wo;
    float lengthSquared = dot(halfVector, halfVector);
    if (lengthSquared == 0.0) {
        return result;
    }
    halfVector *= rsqrt(lengthSquared);
    result.microCosine = dot(wi, halfVector);
    result.factor = primeFullOpenPbrMicrofacetD(microfacet, halfVector) *
                    primeFullOpenPbrMicrofacetG2r(microfacet, wi, wo) / (4.0 * abs(wi.z));
    result.flags = PRIME_FULL_OPENPBR_FLAG_SPECULAR_REFLECTION;
    return result;
}

public PrimeFullOpenPbrReflectiveSampleBase
primeFullOpenPbrReflectiveSampleBase(float3 wi, float3 randomValue,
                                     PrimeFullOpenPbrMicrofacet microfacet, uint samplingFlags,
                                     uint checkFlags) {
    PrimeFullOpenPbrReflectiveSampleBase result;
    result.bsdfSample = primeFullOpenPbrZeroSample();
    result.microCosine = 0.0;
    if (wi.z < 0.0) {
        return result;
    }
    float3 halfVector = float3(0.0, 0.0, 1.0);
    float microfacetPdf = 1.0;
    float distribution = 1.0;
    if (!primeFullOpenPbrMicrofacetEffectivelySmooth(microfacet)) {
        if ((samplingFlags & (checkFlags & PRIME_FULL_OPENPBR_FLAG_SPECULAR)) == 0u) {
            return result;
        }
        halfVector = primeFullOpenPbrMicrofacetSample(microfacet, wi, randomValue.xy, true);
        microfacetPdf = primeFullOpenPbrMicrofacetPdf(microfacet, wi, halfVector, true);
        distribution = primeFullOpenPbrMicrofacetD(microfacet, halfVector);
    } else if ((samplingFlags & (checkFlags & PRIME_FULL_OPENPBR_FLAG_DELTA)) == 0u) {
        return result;
    }
    float3 wo = reflect(-wi, halfVector);
    if (wo.z <= 0.0) {
        return result;
    }
    result.microCosine = dot(halfVector, wi);
    result.bsdfSample.wo = wo;
    if (primeFullOpenPbrMicrofacetEffectivelySmooth(microfacet)) {
        result.bsdfSample.throughput.value = float3(1.0);
        result.bsdfSample.throughput.flags = PRIME_FULL_OPENPBR_FLAG_DELTA_REFLECTION;
        result.bsdfSample.pdf = 1.0;
    } else {
        result.bsdfSample.throughput.value = float3(
                distribution * primeFullOpenPbrMicrofacetG2r(microfacet, wi, wo) / abs(4.0 * wi.z));
        result.bsdfSample.throughput.flags = PRIME_FULL_OPENPBR_FLAG_SPECULAR_REFLECTION;
        result.bsdfSample.pdf = microfacetPdf / (4.0 * abs(result.microCosine));
    }
    return result;
}

public float primeFullOpenPbrReflectivePdf(float3 wi, float3 wo,
                                           PrimeFullOpenPbrMicrofacet microfacet,
                                           uint samplingFlags, uint checkFlags) {
    if (primeFullOpenPbrMicrofacetEffectivelySmooth(microfacet) || wi.z < 0.0 || wo.z <= 0.0 ||
        (samplingFlags & (checkFlags & PRIME_FULL_OPENPBR_FLAG_SPECULAR)) == 0u) {
        return 0.0;
    }
    float3 halfVector = wi + wo;
    float lengthSquared = dot(halfVector, halfVector);
    if (lengthSquared == 0.0) {
        return 0.0;
    }
    halfVector *= rsqrt(lengthSquared);
    return primeFullOpenPbrMicrofacetPdf(microfacet, wi, halfVector, true) /
           (4.0 * abs(dot(wi, halfVector)));
}
