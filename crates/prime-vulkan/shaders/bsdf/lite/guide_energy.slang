// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

#language slang 2026
module "bsdf/lite/guide_energy.slang";

import "bsdf/common/common.slang";

public static const uint PRIME_LITE_OPAQUE_DIELECTRIC = 0u;
public static const uint PRIME_LITE_OPAQUE_CONDUCTOR = 1u;
public static const uint PRIME_LITE_OPAQUE_SUBSURFACE = 2u;
public static const uint PRIME_LITE_OPAQUE_MIXED_SUBSURFACE = 3u;

float primeLiteSchlick(float f0, float cosineTheta) {
    float oneMinusCosine = 1.0 - clamp(abs(cosineTheta), 0.0, 1.0);
    return f0 + (1.0 - f0) * primeOpenPbrPow5(oneMinusCosine);
}

public float primeLiteDielectricFresnel(float ior, float cosineTheta) {
    float cosine = cosineTheta;
    if (cosine < 0.0) {
        float transmittedCosineSquared = 1.0 - (1.0 - cosine * cosine) * ior * ior;
        if (transmittedCosineSquared <= 0.0) {
            return 1.0;
        }
        cosine = sqrt(transmittedCosineSquared);
    }
    float ratio = (ior - 1.0) / (ior + 1.0);
    return primeLiteSchlick(ratio * ratio, cosine);
}

float2 primeLiteReflectiveDirectionalAlbedoBasis(PrimeOpenPbrMicrofacet microfacet,
                                                 float cosineTheta) {
    // Rational fit of the single-scatter GGX directional integral. Keeping only its two Schlick
    // basis coefficients avoids Lite's transmission-energy texture and all of its coordinates.
    float x = clamp(abs(cosineTheta), 0.0, 1.0);
    float y2 = 0.5 * dot(microfacet.alpha, microfacet.alpha);
    float x2 = x * x;
    float y = sqrt(y2);
    float4 r =
            float4(0.1003, 0.9345, 1.0, 1.0) + float4(-0.6303, -2.323, -1.765, 0.2281) * x +
            float4(9.748, 2.229, 8.263, 15.94) * y + float4(-2.038, -3.748, 11.53, -55.83) * x * y +
            float4(29.34, 1.424, 28.96, 13.08) * x2 + float4(-8.245, -0.7684, -7.507, 41.26) * y2 +
            float4(-26.44, 1.436, -36.11, 54.9) * x2 * y +
            float4(19.99, 0.2913, 15.86, 300.2) * x * y2 +
            float4(-5.448, 0.6286, 33.37, -285.1) * x2 * y2;
    return clamp(r.xy / r.zw, float2(0.0), float2(1.0));
}

public float primeLiteDielectricDirectionalClosure(PrimeOpenPbrMicrofacet microfacet,
                                                   float cosineTheta, float energyIor,
                                                   out float specularScale) {
    float f0 = primeOpenPbrIorToF0(energyIor);
    float2 basis = primeLiteReflectiveDirectionalAlbedoBasis(microfacet, cosineTheta);
    float singleScatterUnity = clamp(basis.x + basis.y, 1.0e-5, 1.0);
    float singleScatter = clamp(f0 * basis.x + basis.y, 0.0, 1.0);
    specularScale = 1.0 + f0 * (1.0 - singleScatterUnity) / singleScatterUnity;
    return clamp(singleScatter * specularScale, 0.0, 1.0);
}

public float3 primeLiteSubsurfaceEnergyCore(uint thinWalled, float3 color) {
    return thinWalled != 0u ? color : float3(1.0);
}

public float3 primeLiteBaseEnergyCore(uint kind, float3 baseColor, float subsurfaceWeight,
                                      uint thinWalled, float3 subsurfaceColor) {
    if (kind == PRIME_LITE_OPAQUE_DIELECTRIC) {
        return baseColor;
    }
    if (kind == PRIME_LITE_OPAQUE_SUBSURFACE) {
        return primeLiteSubsurfaceEnergyCore(thinWalled, subsurfaceColor);
    }
    return (1.0 - subsurfaceWeight) * baseColor +
           subsurfaceWeight * primeLiteSubsurfaceEnergyCore(thinWalled, subsurfaceColor);
}
