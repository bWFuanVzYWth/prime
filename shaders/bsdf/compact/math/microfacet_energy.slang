// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

#language slang 2026
module "bsdf/compact/math/microfacet_energy.slang";

import "bsdf/compact/contract/material.slang";
import "bsdf/compact/contract/sample.slang";
import "bsdf/compact/math/spectrum.slang";

public interface IPrimeOpenPbrTransmissionGgxEnergy {
    public float4 sampleLevel(float3 uvw);
}

float primeOpenPbrMicrofacetAlpha2(PrimeOpenPbrMicrofacet microfacet) {
    return 0.5 * primeOpenPbrReduceSum(primeOpenPbrSquare(microfacet.alpha));
}

public float3 primeOpenPbrMicrofacetDirectionalAlbedoReflection(
        PrimeOpenPbrMicrofacet microfacet,
        float cosineTheta,
        float3 f0,
        float3 f90) {
    float x = cosineTheta;
    float y2 = primeOpenPbrMicrofacetAlpha2(microfacet);
    float x2 = x * x;
    float y = sqrt(y2);
    float4 r = float4(0.1003, 0.9345, 1.0, 1.0)
            + float4(-0.6303, -2.323, -1.765, 0.2281) * x
            + float4(9.748, 2.229, 8.263, 15.94) * y
            + float4(-2.038, -3.748, 11.53, -55.83) * x * y
            + float4(29.34, 1.424, 28.96, 13.08) * x2
            + float4(-8.245, -0.7684, -7.507, 41.26) * y2
            + float4(-26.44, 1.436, -36.11, 54.9) * x2 * y
            + float4(19.99, 0.2913, 15.86, 300.2) * x * y2
            + float4(-5.448, 0.6286, 33.37, -285.1) * x2 * y2;
    float2 ab = clamp(r.xy / r.zw, float2(0.0), float2(1.0));
    return f0 * ab.x + f90 * ab.y;
}

public float3 primeOpenPbrMicrofacetDielectricMsCompensation<TransmissionGgxEnergy>(
        TransmissionGgxEnergy transmissionGgxEnergy,
        PrimeOpenPbrMicrofacet microfacet,
        float cosineTheta,
        float ior)
    where TransmissionGgxEnergy : IPrimeOpenPbrTransmissionGgxEnergy
{
    if (ior == 1.0) { return float3(1.0, 1.0, 0.0); }

    const float qMin = 5.960464477539063e-8;
    const float qCritical = 0.00010002000500139488;
    const float qMax = 0.999499874937461;
    const float nearLog2Scale = 0.09334822745469708;
    const float farLog2Min = -13.287279491668832;
    const float farLog2Scale = 0.04123374454764629;

    bool inside = (cosineTheta < 0.0) != (ior < 1.0);
    float mu = clamp(abs(cosineTheta), 0.02, 1.0);
    float q = clamp(abs((ior - 1.0) / (ior + 1.0)), qMin, qMax);
    float critical = min(2.0 * sqrt(q) / (1.0 + q), 0.9999999403953552);
    bool nearInside = inside && q <= qCritical;
    bool belowCritical = inside && !nearInside && mu < critical;

    float nearU = clamp((log2(q) + 24.0) * nearLog2Scale, 0.0, 1.0);
    float nearZ = nearU - 0.65 * nearU * (1.0 - nearU) * (2.0 * nearU - 1.0);
    float farU = clamp((log2(q / (1.0 - q)) - farLog2Min) * farLog2Scale, 0.0, 1.0);
    float farZ = sqrt(farU);
    float exteriorZ = q <= qCritical
            ? (12.0 / 47.0) * nearZ
            : (12.0 + 35.0 * farZ) / 47.0;
    float logicalZ = inside ? (nearInside ? nearZ : farZ) : exteriorZ;
    float zOffset = inside ? (nearInside ? 48.0 : (belowCritical ? 111.0 : 63.0)) : 0.0;
    float zExtent = nearInside ? 15.0 : 47.0;

    float roughness = sqrt(sqrt(primeOpenPbrMicrofacetAlpha2(microfacet)));
    float roughnessCoordinate = sqrt(clamp((roughness - 0.01) / 0.99, 0.0, 1.0));
    float linearView = (mu - 0.02) / 0.98;
    float belowView = (mu - 0.02) / max(critical - 0.02, 1.0e-20);
    float aboveView = (mu - critical) / max(1.0 - critical, 1.0e-20);
    float view = clamp(
            belowCritical ? belowView : (inside && !nearInside ? aboveView : linearView),
            0.0,
            1.0);
    view = view <= 0.5
            ? 0.5 * sqrt(2.0 * view)
            : 1.0 - 0.5 * sqrt(2.0 * (1.0 - view));
    view -= 0.64 * view * (1.0 - view) * (2.0 * view - 1.0);

    float3 uvw = float3(
            (view * 43.0 + 0.5) / 44.0,
            (roughnessCoordinate * 31.0 + 0.5) / 32.0,
            (zOffset + logicalZ * zExtent + 0.5) / 159.0);
    float4 encoded = transmissionGgxEnergy.sampleLevel(uvw);

    float a = 0.5 * (sqrt(1.0 + primeOpenPbrMicrofacetAlpha2(microfacet)
            * (1.0 - mu * mu) / (mu * mu)) + 1.0);
    float inverseA = 1.0 / a;
    float etaOneLog2T = 1.0 - 2.0 * a + 0.5 * log2(PRIME_OPENPBR_PI * a) - 1.45676773e-5
            + inverseA * (1.80676418e-1 + inverseA
            * (-1.85532904e-3 - 4.61868116e-3 * inverseA));
    float etaQ0 = 3.0e-4 + 6.82190321e-2 * roughness * mu;
    float etaFactor = etaQ0 / (q + etaQ0) * etaOneLog2T;
    float qFraction = frac(logicalZ * zExtent);
    float transmissionCorrection = (2.0 / 3.0) * encoded.w
            * qFraction * (1.0 - qFraction);
    float2 scale = exp2(clamp(
            float2(encoded.x, encoded.y - etaFactor + transmissionCorrection),
            float2(-80.0),
            float2(80.0)));
    float encodedB = clamp(encoded.z, 0.0, 1.0);
    float bEndpoint = 1.0 - abs(2.0 * encodedB - 1.0);
    float bEdge = 0.5 * primeOpenPbrSquare(bEndpoint) * sqrt(bEndpoint);
    float helperR = encodedB <= 0.5 ? bEdge : 1.0 - bEdge;
    return float3(scale, helperR);
}

public float primeOpenPbrMicrofacetDirectionalAlbedoUnity(
        PrimeOpenPbrMicrofacet microfacet, float cosineTheta) {
    return primeOpenPbrMicrofacetDirectionalAlbedoReflection(
            microfacet, cosineTheta, float3(1.0), float3(1.0)).x;
}
