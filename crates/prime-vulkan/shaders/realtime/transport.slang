#language slang 2026
module "realtime/transport.slang";
import "realtime/landing.slang";
import "realtime/transport_parameters.slang";
import "transport/direct.slang";
import "math/color.slang";
import "math/z_sobol.slang";
import "math/transport.slang";
import "math/ray_offset.slang";
import "service/bsdf/contract/sample.slang";
import "pbr.slang";
import "bsdf/full/energy.slang";
import "ray_query.slang";
import "atmosphere/lighting.slang";
import "service/bsdf/support/sanitization.slang";

// The only reconstruction consumer allowed in K2: one actual reflected segment length.
public interface ITransportDistance {
    void write(uint2 pixel, float distance);
};
public struct NoTransportDistance : ITransportDistance {
    public void write(uint2 pixel, float distance) {}
};

public float3 primeTraceLanding<Distance>(TraceScene resources, AtmLighting environment,
                                          PrimeFullTransmissionGgxEnergy energy,
                                          TransportParameters parameters, uint2 pixel,
                                          Distance distanceOutput) where Distance
    : ITransportDistance {
    uint count = parameters.sampling.x * parameters.sampling.y;
    uint index = pixel.y * parameters.sampling.x + pixel.x;
    uint flags = primeLandingFlags(parameters.commonAddress, count, index);
    if ((flags & PRIME_LANDING_ACTIVE) == 0)
        return float3(0.0);
    uint firstBounce = flags & PRIME_LANDING_BOUNCE_MASK;
    uint sequence = parameters.sampling.z;
    ZSobol sampler = makeZSobolS8(pixel, sequence & 0xffu, parameters.config.x,
                                  parameters.sampling.w ^ (sequence >> 8));
    float3 radiance = float3(0.0);
    float3 origin = float3(0.0);
    float3 direction = float3(0.0);
    float3 previous = float3(0.0);
    float previousPdf = 0.0;
    float3 throughput = float3(1.0);
    float etaScale = 1.0;
    float4 medium = float4(1, 0, 0, 0);
    float coneWidth = 0.0;
    bool distancePending = false;
    for (uint bounce = firstBounce; bounce < parameters.config.y; ++bounce) {
        uint domain = 1 + 4 * bounce;
        SurfacePoint surface;
        PrimePbrVertex material;
        float4 incident;
        float4 transmitted;
        bool thin;
        if (bounce == firstBounce) {
            // K1 already consumed coverage, geometry/texture resolution, segment absorption,
            // cone advance and this vertex's emission. Never repeat any of those tasks here.
            PrimeLanding landing = primeLoadLanding(parameters.commonAddress,
                                                    parameters.opticalAddress, count, index);
            surface = landing.surface;
            material = landing.material;
            direction = landing.direction;
            coneWidth = landing.coneWidth;
            medium = landing.medium;
            incident = landing.incident;
            transmitted = landing.transmitted;
            throughput = landing.throughput;
            etaScale = landing.etaScale;
            thin = (flags & PRIME_LANDING_PHYSICAL_THIN) != 0;
        } else {
            SurfaceHit hit = traceClosest(resources, origin, direction, sampler.bits1D(domain),
                                          coneWidth, parameters.lighting.z);
            if (distancePending) {
                distanceOutput.write(pixel,
                                     hit.found ? length(hit.surface.position - previous) : 65504.0);
                distancePending = false;
            }
            if (!hit.found) {
                float3 escaped = atmSky(environment, direction) * parameters.lighting.y;
                if (atmSunContains(environment, direction)) {
                    float weight = previousPdf == 0.0
                                           ? 1.0
                                           : primePowerHeuristicValue(previousPdf, atmSunPdf());
                    escaped +=
                            weight * atmSunRadiance(environment, direction) * parameters.lighting.x;
                }
                radiance += throughput * escaped;
                break;
            }
            surface = hit.surface;
            float distance = length(surface.position - origin);
            coneWidth += parameters.lighting.z * distance;
            if (opticalFeatures != 0)
                throughput *= exp(-medium.yzw * distance);
            if (dot(surface.normal, direction) > 0.0)
                surface.normal = -surface.normal;
            float3 albedo = sourceToWorking(saturate(hit.sourceColor.rgb));
            float3 normal = dot(hit.shadingNormal, direction) > 0.0 ? -hit.shadingNormal
                                                                    : hit.shadingNormal;
            material = primePbrVertex(albedo, normal, hit.normalSample.a, hit.specularSample.rgb,
                                      (hit.materialFlags & 1) != 0, (hit.materialFlags & 2) != 0,
                                      hit.materialThin, hit.optical, -direction);
            thin = hit.thin;
            incident = hit.optical && !thin ? hit.incident : medium;
            transmitted =
                    thin ? (hit.incident.x > hit.transmitted.x ? hit.incident : hit.transmitted)
                         : hit.transmitted;
            float lightPdf = previousPdf == 0.0 || lightFeatures == 0
                                     ? 0.0
                                     : emissiveHitPdf(resources, hit, previous);
            float weight = lightPdf == 0.0 ? 1.0 : primePowerHeuristicValue(previousPdf, lightPdf);
            radiance += primeTripleProduct(throughput, linear709ToWorking(hit.emission), weight);
        }
        primeTransportDirect(resources, environment, energy, sampler, bounce, parameters.config.y,
                             parameters.lighting.x, surface, material, direction, medium, incident,
                             transmitted, thin, throughput, radiance);
        if (!primePathHasContinuation(bounce, parameters.config.y))
            break;
        float3 view = -direction;
        PrimePbrSample sampled = primeSamplePbrVertex(
                energy, material, view,
                float3(sampler.sample2D(domain + 2), sampler.sample1D(1280 + bounce)), incident,
                transmitted);
        BsdfSample scatter = sampled.bsdfSample;
        if (scatter.eventFlags == 0 ||
            !primeGeometricSampleSupport(primePbrVertexHasNormal(material), surface.normal, view,
                                         scatter))
            break;
        throughput = primeProductOver(throughput, scatter.response, scatter.pdf);
        if (!primeBsdfFinite(throughput) || all(throughput == float3(0)))
            break;
        if (bounce == firstBounce && (flags & PRIME_LANDING_GUIDE_DISTANCE) != 0)
            distancePending = (scatter.eventFlags & PRIME_BSDF_EVENT_REFLECTION) != 0u &&
                              (scatter.eventFlags &
                               (PRIME_BSDF_EVENT_GLOSSY | PRIME_BSDF_EVENT_DISCRETE)) != 0u;
        etaScale = primeEtaScaleAfterScatter(etaScale, scatter.relativeEta,
                                             (scatter.eventFlags & PRIME_BSDF_EVENT_TRANSMISSION) !=
                                                     0u);
        direction = scatter.direction;
        medium = sampled.medium;
        previous = surface.position;
        previousPdf = primeDirectLightingMisPdf(primePbrVertexIsDielectric(material), scatter);
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
    return radiance;
}
