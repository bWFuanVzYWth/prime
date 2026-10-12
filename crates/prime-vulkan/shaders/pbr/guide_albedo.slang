// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

#language slang 2026
module "pbr/guide_albedo.slang";

import "pbr/vertex.slang";
import "bsdf/full/contract/sample.slang";
import "bsdf/full/math/microfacet_energy.slang";
import "bsdf/full/math/microfacet_measure.slang";
import "bsdf/full/math/spectrum.slang";
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
    } else {
        float m = primePbrVertexMetalness(vertex);
        result.diffuse = result.specular = float3(0.0);
        if (m < 1.0) {
            float ior = primePbrVertexIor(vertex) / incidentMedium.x;
            float weight = primePbrVertexSubsurface(vertex);
            float reflected =
                    primeFullOpenPbrMicrofacetDielectricMsCompensation(
                            energy, microfacet, cosine, primeFullOpenPbrIorAdjustment(ior, 1.0))
                            .z;
            float3 baseEnergy =
                    weight > 0.0 && weight != 1.0
                            ? vertex.baseColor * (1.0 - weight) + vertex.baseColor * weight
                            : (weight > 0.0 || cosine >= 0.0 ? vertex.baseColor : float3(0.0));
            result.diffuse = (1.0 - m) * baseEnergy * (1.0 - reflected);
            result.specular = (1.0 - m) * float3(cosine < 0.0 ? 0.0 : reflected);
        }
        if (m > 0.0 && cosine >= 0.0) {
            float3 f0 = saturate(vertex.baseColor);
            float ess = primeFullOpenPbrMicrofacetEffectivelySmooth(microfacet)
                                ? 1.0
                                : primeFullOpenPbrMicrofacetDirectionalAlbedoReflection(
                                          microfacet, cosine, float3(1.0), float3(1.0))
                                          .x;
            result.specular += m *
                               primeFullOpenPbrMicrofacetDirectionalAlbedoReflection(
                                       microfacet, cosine, f0, float3(1.0)) *
                               (float3(1.0) + f0 * (1.0 - ess) / ess);
        }
    }
    result.diffuse = primeSanitizeDenoiseAlbedo(result.diffuse);
    result.specular = primeSanitizeDenoiseAlbedo(result.specular);
    return result;
}
