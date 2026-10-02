// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

#language slang 2026
module "pbr/guide_albedo.slang";

import "pbr/vertex.slang";
import "bsdf/common/common.slang";
import "bsdf/lite/guide_energy.slang";
import "bsdf/full/contract/sample.slang";
import "bsdf/full/math/microfacet_energy.slang";
import "bsdf/full/math/microfacet_measure.slang";
import "bsdf/full/math/spectrum.slang";
import "model/material/fresnel_code.slang";
import "model/material/translation.slang";
import "model/material/defaults.slang";
import "model/material/optical.slang";
import "service/bsdf/contract/albedo.slang";
import "service/bsdf/support/sanitization.slang";

// The production directional-energy formulas, with no closure, mixture probabilities,
// sampled direction, incident lighting or tangent-frame construction.
public PrimeDenoiseAlbedos primePbrGuideAlbedos<Energy>(Energy energy, PrimePbrVertex vertex,
                                                        float3 viewDirection, float4 incidentMedium,
                                                        float4 transmittedMedium) where Energy
    : IPrimeFullOpenPbrTransmissionGgxEnergy {
    float cosine =
            dot(primePbrVertexFrameNormal(vertex, viewDirection, incidentMedium, transmittedMedium),
                viewDirection);
    PrimeFullOpenPbrMicrofacet microfacet;
    microfacet.alpha = float2(vertex.roughness * vertex.roughness);
    PrimeDenoiseAlbedos result;
    if (primePbrVertexIsDielectric(vertex)) {
        float ior = primePbrVertexInterfaceIor(vertex, incidentMedium, transmittedMedium);
        float reflected = primeFullOpenPbrMicrofacetDielectricMsCompensation(
                                  energy, microfacet,
                                  primePbrVertexIsThinWalled(vertex) ? abs(cosine) : cosine, ior)
                                  .z;
        result.diffuse = float3(1.0 - reflected);
        result.specular = float3(reflected);
    } else if (primePbrVertexIsConductor(vertex)) {
        PrimeConductorFresnel fresnel = primeConductorFresnel(
                vertex.baseColor, vertex.control & PRIME_OPTICAL_FRESNEL_MASK);
        float3 f0 = clamp(fresnel.f0, float3(0.0), float3(1.0));
        float ess = primeFullOpenPbrMicrofacetEffectivelySmooth(microfacet)
                            ? 1.0
                            : primeFullOpenPbrMicrofacetDirectionalAlbedoReflection(
                                      microfacet, cosine, float3(1.0), float3(1.0))
                                      .x;
        result.diffuse = float3(0.0);
        result.specular = cosine < 0.0 ? float3(0.0)
                                       : primeFullOpenPbrMicrofacetDirectionalAlbedoReflection(
                                                 microfacet, cosine, f0, float3(1.0)) *
                                                 (float3(1.0) + f0 * (1.0 - ess) / ess);
    } else {
        uint code = vertex.control & PRIME_OPTICAL_FRESNEL_MASK;
        float ior = primeOpenPbrF0ToIor(primeDecodeDielectricF0(code)) * (1.0 / incidentMedium.x);
        uint subsurface = (vertex.control >> PRIME_OPTICAL_SUBSURFACE_SHIFT) & 0xffu;
        float weight = float(subsurface) / 190.0;
        float3 baseEnergy;
        float reflected;
        if (primePbrVertexUsesLegacyThickSss(vertex)) {
            PrimeOpenPbrMicrofacet liteMicrofacet;
            liteMicrofacet.alpha = microfacet.alpha;
            float energyIor = primeOpenPbrIorAdjustment(ior, 1.0);
            float unusedSpecularScale;
            reflected =
                    primeFullOpenPbrMicrofacetEffectivelySmooth(microfacet)
                            ? primeLiteDielectricFresnel(energyIor, abs(cosine))
                            : primeLiteDielectricDirectionalClosure(liteMicrofacet, abs(cosine),
                                                                    energyIor, unusedSpecularScale);
            uint kind = weight == 1.0 ? PRIME_LITE_OPAQUE_SUBSURFACE
                                      : PRIME_LITE_OPAQUE_MIXED_SUBSURFACE;
            baseEnergy =
                    primeLiteBaseEnergyCore(kind, vertex.baseColor, weight, 0u, vertex.baseColor);
        } else {
            reflected = primeFullOpenPbrMicrofacetDielectricMsCompensation(
                                energy, microfacet, cosine, primeFullOpenPbrIorAdjustment(ior, 1.0))
                                .z;
            // Preserve Full's zero-roughness diffuse/subsurface blend and its operation order.
            baseEnergy =
                    weight > 0.0 && weight != 1.0
                            ? (weight < 1.0 ? vertex.baseColor : float3(0.0)) * (1.0 - weight) +
                                      vertex.baseColor * weight
                            : (weight > 0.0 || cosine >= 0.0 ? vertex.baseColor : float3(0.0));
        }
        result.diffuse = baseEnergy * (1.0 - reflected);
        result.specular =
                float3(primePbrVertexUsesLegacyThickSss(vertex) ? 1.0 - (1.0 - reflected)
                                                                : (cosine < 0.0 ? 0.0 : reflected));
    }
    result.diffuse = primeSanitizeDenoiseAlbedo(result.diffuse);
    result.specular = primeSanitizeDenoiseAlbedo(result.specular);
    return result;
}
