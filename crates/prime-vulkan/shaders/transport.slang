#language slang 2026
module "transport.slang";
import "frame.slang";
import "math/color.slang";
import "math/z_sobol.slang";
import "math/ray_offset.slang";
import "math/transport.slang";
import "pbr.slang";
import "bsdf/full/energy.slang";
import "service/bsdf/contract/sample.slang";
import "service/bsdf/support/sanitization.slang";
import "ray_query.slang";
import "atmosphere/view.slang";

public struct TraceResult {
    public float3 radiance;
    public float depth;
    public float3 normal;
};
// A specialized consumer writes primary guides while the resolved hit is still local.
// Raw realtime and offline instantiate the empty consumer, without guide image writes.
public interface ITraceGuides {
    float2 sampleOffset(ZSobol sampler);
    void primary(Frame frame, uint2 pixel, float2 sampleUv, SurfaceHit hit, PrimePbrVertex material,
                 float3 direction, float4 incident, float4 transmitted);
    void miss(Frame frame, uint2 pixel, float2 sampleUv, float3 direction);
    void specularDistance(uint2 pixel, float distance);
};
public struct NoTraceGuides : ITraceGuides {
    public float2 sampleOffset(ZSobol sampler) {
        return sampler.sample2D(0);
    }
    public void primary(Frame frame, uint2 pixel, float2 sampleUv, SurfaceHit hit,
                        PrimePbrVertex material, float3 direction, float4 incident,
                        float4 transmitted) {}
    public void miss(Frame frame, uint2 pixel, float2 sampleUv, float3 direction) {}
    public void specularDistance(uint2 pixel, float distance) {}
};
// Guides describe the very same jittered primary sample and alpha decision as noisy radiance.
public TraceResult traceSampleWithGuides<Guides>(TraceScene resources, AtmEnvironment environment,
                                                 PrimeFullTransmissionGgxEnergy energy, Frame frame,
                                                 uint2 pixel, uint sequence,
                                                 Guides guides) where Guides : ITraceGuides {
    TraceResult result = {float3(0), 3.402823466e38f, float3(0)};
    ZSobol sampler =
            makeZSobolS8(pixel, sequence & 0xffu, frame.target.z, frame.target.w ^ (sequence >> 8));
    float2 screen =
            ((float2(pixel) + guides.sampleOffset(sampler)) / float2(frame.dimensions.xy)) * 2.0 -
            1.0;
    float3 origin = frame.position.xyz;
    float3 direction = normalize(frame.forward.xyz +
                                 frame.right.xyz * (screen.x * frame.forward.w * frame.position.w) -
                                 frame.up.xyz * (screen.y * frame.position.w));
    float3 throughput = float3(1);
    float etaScale = 1.0;
    float3 previous = origin;
    float primaryDistance = 0;
    float previousPdf = 0;
    bool primarySpecular = false;
    float4 medium = float4(1, 0, 0, 0);
    float coneWidth = 0.0;
    float coneSpread =
            surfaceFeatures >= 2 ? 2.0 * frame.position.w / float(frame.dimensions.y) : 0.0;
    for (uint bounce = 0; bounce < asuint(frame.displayExtra.z); ++bounce) {
        uint domain = 1 + 4 * bounce;
        SurfacePoint surface;
        PrimePbrVertex material;
        float4 incident;
        float4 transmitted;
        bool optical;
        bool thin;
        // Raw hit samples and emitter metadata end here. Only canonical shading inputs
        // and the physical boundary endpoints are needed by the shadow consumers.
        {
            SurfaceHit hit = traceClosest(resources, origin, direction, sampler.bits1D(domain),
                                          coneWidth, coneSpread);
            if (bounce == 1 && primarySpecular)
                guides.specularDistance(pixel, hit.found ? length(hit.surface.position - previous)
                                                         : 65504.0);
            if (!hit.found) {
                if (bounce == 0)
                    guides.miss(frame, pixel, 0.5 * (screen + 1.0), direction);
                float3 escaped = atmSky(environment, direction) * frame.up.w;
                if (atmSunContains(environment, direction)) {
                    float weight = previousPdf == 0
                                           ? 1.0
                                           : primePowerHeuristicValue(previousPdf, atmSunPdf());
                    escaped += weight * atmSunRadiance(environment, direction) * frame.right.w;
                }
                result.radiance += throughput * escaped;
                break;
            }
            surface = hit.surface;
            coneWidth += coneSpread * length(surface.position - origin);
            if (opticalFeatures != 0) {
                if (bounce == 0)
                    medium = hit.thin ? float4(1, 0, 0, 0) : hit.incident;
                throughput *= exp(-medium.yzw * length(surface.position - origin));
            }
            if (dot(surface.normal, direction) > 0)
                surface.normal = -surface.normal;
            if (bounce == 0) {
                primaryDistance =
                        medium.x == 1.0 ? length(surface.position - frame.position.xyz) : 0.0;
                result.depth = dot(surface.position - frame.position.xyz, frame.forward.xyz);
                result.normal = dot(hit.shadingNormal, direction) > 0 ? -hit.shadingNormal
                                                                      : hit.shadingNormal;
            }
            float3 albedo = sourceToWorking(saturate(hit.sourceColor.rgb));
            float3 normal =
                    dot(hit.shadingNormal, direction) > 0 ? -hit.shadingNormal : hit.shadingNormal;
            optical = hit.optical;
            thin = hit.thin;
            material = primePbrVertex(albedo, normal, hit.normalSample.a, hit.specularSample.rgb,
                                      (hit.materialFlags & 1) != 0, (hit.materialFlags & 2) != 0,
                                      hit.materialThin, optical, -direction);
            incident = optical && !thin ? hit.incident : medium;
            transmitted =
                    hit.thin ? (hit.incident.x > hit.transmitted.x ? hit.incident : hit.transmitted)
                             : hit.transmitted;
            if (bounce == 0)
                guides.primary(frame, pixel, 0.5 * (screen + 1.0), hit, material, direction,
                               incident, transmitted);
            {
                float lightPdf = previousPdf == 0 || lightFeatures == 0
                                         ? 0
                                         : emissiveHitPdf(resources, hit, previous);
                float emissionWeight =
                        lightPdf == 0 ? 1.0 : primePowerHeuristicValue(previousPdf, lightPdf);
                result.radiance += primeTripleProduct(throughput, linear709ToWorking(hit.emission),
                                                      emissionWeight);
            }
        }
        float3 view = -direction;
        float3 normal = material.normal;
        bool hasNormal = primePbrVertexHasNormal(material);
        if (lightFeatures != 0 && hasLocalLights(resources)) {
            uint lightDomain = 512 + 4 * bounce;
            LightSample light = sampleLocalLight(resources, sampler, bounce, surface.position);
            float3 lightDirection = light.valid
                                            ? normalize(light.surface.position - surface.position)
                                            : surface.normal;
            float4 lightMedium = optical && !thin && dot(surface.normal, lightDirection) < 0
                                         ? transmitted
                                         : medium;
            float pdf;
            float3 radiance =
                    evaluateLocalLight(resources, surface, light, sampler.bits1D(lightDomain + 3),
                                       lightMedium, optical, pdf);
            if (pdf > 0) {
                BsdfEvaluation evaluated = primeEvaluatePbrVertex(
                        energy, material, view, lightDirection, incident, transmitted);
                if (!primeGeometricEvaluationSupport(hasNormal, surface.normal, normal, view,
                                                     lightDirection, optical))
                    evaluated = primeInvalidBsdfEvaluation();
                float bsdfPdf = evaluated.pdf;
                float weightedInversePdf =
                        primePathHasContinuation(bounce, asuint(frame.displayExtra.z))
                                ? primePowerHeuristicOverPdfValue(pdf, bsdfPdf)
                                : 1.0 / pdf;
                result.radiance +=
                        throughput * primeTripleProduct(linear709ToWorking(radiance),
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
                            primePathHasContinuation(bounce, asuint(frame.displayExtra.z))
                                    ? primePowerHeuristicOverPdfValue(atmSunPdf(), bsdfPdf)
                                    : 1.0 / atmSunPdf();
                    result.radiance +=
                            throughput * primeTripleProduct(sunRadiance * visibility,
                                                            evaluated.response,
                                                            weightedInversePdf * frame.right.w);
                }
            }
        }
        // The final vertex has no continuation consumer. Keep its NEE and MIS above,
        // but do not construct a BSDF sample or roulette state that will be discarded.
        if (!primePathHasContinuation(bounce, asuint(frame.displayExtra.z)))
            break;
        // The three-dimensional closure sample keeps its lobe selector independent of roulette.
        PrimePbrSample sampled = primeSamplePbrVertex(
                energy, material, view,
                float3(sampler.sample2D(domain + 2), sampler.sample1D(1280 + bounce)), incident,
                transmitted);
        BsdfSample scatter = sampled.bsdfSample;
        if (scatter.eventFlags == 0 ||
            !primeGeometricSampleSupport(hasNormal, surface.normal, view, scatter))
            break;
        throughput = primeProductOver(throughput, scatter.response, scatter.pdf);
        if (!primeBsdfFinite(throughput) || all(throughput == float3(0)))
            break;
        if (bounce == 0)
            primarySpecular = (scatter.eventFlags & PRIME_BSDF_EVENT_REFLECTION) != 0u &&
                              (scatter.eventFlags &
                               (PRIME_BSDF_EVENT_GLOSSY | PRIME_BSDF_EVENT_DISCRETE)) != 0u;
        etaScale = primeEtaScaleAfterScatter(etaScale, scatter.relativeEta,
                                             (scatter.eventFlags & PRIME_BSDF_EVENT_TRANSMISSION) !=
                                                     0u);
        direction = scatter.direction;
        medium = sampled.medium;
        previous = surface.position;
        previousPdf = primeDirectLightingMisPdf(optical, scatter);
        origin = spawnRay(surface, direction);
        if (primeRussianRouletteApplies(bounce, PRIME_RUSSIAN_ROULETTE_START)) {
            float survival = primeRussianRouletteSurvival(throughput, etaScale);
            if (!(survival > 0.0))
                break;
            if (survival < 1.0) {
                if (!(sampler.sample1D(domain + 3) < survival))
                    break;
                throughput = primeRussianRouletteReweight(throughput, survival);
                if (!primeBsdfFinite(throughput))
                    break;
            }
        }
    }
    if (primaryDistance > 0) {
        float3 s, t;
        atmAerial(environment, 0.5 * (screen + 1.0), primaryDistance, s, t);
        result.radiance = t * result.radiance + s * frame.right.w;
    }
    result.radiance =
            float3(primeBsdfFinite(result.radiance.x) ? max(result.radiance.x, 0.0) : 0.0,
                   primeBsdfFinite(result.radiance.y) ? max(result.radiance.y, 0.0) : 0.0,
                   primeBsdfFinite(result.radiance.z) ? max(result.radiance.z, 0.0) : 0.0);
    return result;
}

public TraceResult traceSample(TraceScene resources, AtmEnvironment environment,
                               PrimeFullTransmissionGgxEnergy energy, Frame frame, uint2 pixel,
                               uint sequence) {
    return traceSampleWithGuides(resources, environment, energy, frame, pixel, sequence,
                                 NoTraceGuides());
}
