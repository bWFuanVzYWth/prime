#language slang 2026
module "transport/direct.slang";
import "pbr.slang";
import "bsdf/full/energy.slang";
import "ray_query.slang";
import "atmosphere/lighting.slang";
import "math/color.slang";
import "math/transport.slang";
import "math/z_sobol.slang";
import "math/ray_offset.slang";
import "service/bsdf/contract/sample.slang";
import "service/bsdf/support/sanitization.slang";

// Shared by Offline and the realtime transport kernel. Material/ONB state is prepared only
// after each visibility query; no primary, guide, history or display state enters this scope.
public void primeTransportDirect(TraceScene resources, AtmLighting environment,
                                 PrimeFullTransmissionGgxEnergy energy, ZSobol sampler, uint bounce,
                                 uint budget, float sunIntensity, SurfacePoint surface,
                                 PrimePbrVertex material, float3 direction, float4 medium,
                                 float4 incident, float4 transmitted, bool thin, float3 throughput,
                                 inout float3 accumulated) {
    uint domain = 1 + 4 * bounce;
    bool optical = primePbrVertexIsDielectric(material);
    float3 view = -direction;
    float3 normal = material.normal;
    bool hasNormal = primePbrVertexHasNormal(material);
    if (lightFeatures != 0 && hasLocalLights(resources)) {
        uint lightDomain = 512 + 4 * bounce;
        LightSample light = sampleLocalLight(resources, sampler, bounce, surface.position);
        float3 lightDirection =
                light.valid ? normalize(light.surface.position - surface.position) : surface.normal;
        float4 lightMedium =
                optical && !thin && dot(surface.normal, lightDirection) < 0 ? transmitted : medium;
        float pdf;
        float3 radiance =
                evaluateLocalLight(resources, surface, light, sampler.bits1D(lightDomain + 3),
                                   lightMedium, optical, pdf);
        if (pdf > 0 && !primeCanSkipZeroNee(radiance, pdf, throughput,
                                            primePathHasContinuation(bounce, budget))) {
            BsdfEvaluation evaluated = primeEvaluatePbrVertex(
                    energy, material, view, lightDirection, incident, transmitted);
            if (!primeGeometricEvaluationSupport(hasNormal, surface.normal, normal, view,
                                                 lightDirection, optical))
                evaluated = primeInvalidBsdfEvaluation();
            float bsdfPdf = evaluated.pdf;
            float weightedInversePdf = primePathHasContinuation(bounce, budget)
                                               ? primePowerHeuristicOverPdfValue(pdf, bsdfPdf)
                                               : 1.0 / pdf;
            accumulated += throughput * primeTripleProduct(linear709ToWorking(radiance),
                                                           evaluated.response, weightedInversePdf);
        }
    }
    float3 sunDirection = atmSampleSun(environment, sampler.sample2D(1024 + bounce));
    float cosine = saturate(dot(surface.normal, sunDirection));
    if (cosine > 0 || optical) {
        float3 sunRadiance = atmSunRadiance(environment, sunDirection);
        if (any(sunRadiance > 0.0)) {
            float3 visibility = traceTransmittance(
                    resources, spawnRay(surface, sunDirection), sunDirection,
                    sampler.bits1D(domain + 1), 100000.0,
                    optical && !thin && dot(surface.normal, sunDirection) < 0 ? transmitted
                                                                              : medium);
            if (any(visibility > 0.0)) {
                BsdfEvaluation evaluated = primeEvaluatePbrVertex(
                        energy, material, view, sunDirection, incident, transmitted);
                if (!primeGeometricEvaluationSupport(hasNormal, surface.normal, normal, view,
                                                     sunDirection, optical))
                    evaluated = primeInvalidBsdfEvaluation();
                float bsdfPdf = evaluated.pdf;
                float weightedInversePdf =
                        primePathHasContinuation(bounce, budget)
                                ? primePowerHeuristicOverPdfValue(atmSunPdf(), bsdfPdf)
                                : 1.0 / atmSunPdf();
                accumulated += throughput * primeTripleProduct(sunRadiance * visibility,
                                                               evaluated.response,
                                                               weightedInversePdf * sunIntensity);
            }
        }
    }
}
