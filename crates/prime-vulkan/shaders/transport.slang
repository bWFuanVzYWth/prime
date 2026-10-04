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
import "transport/direct.slang";

// Offline has one radiance consumer. It carries no realtime guide or diagnostic state.
public float3 traceSample(TraceScene resources, AtmEnvironment environment,
                          PrimeFullTransmissionGgxEnergy energy, Frame frame, uint2 pixel,
                          uint sequence) {
    float3 radiance = float3(0);
    ZSobol sampler =
            makeZSobolS8(pixel, sequence & 0xffu, frame.target.z, frame.target.w ^ (sequence >> 8));
    float2 screen =
            ((float2(pixel) + sampler.sample2D(0)) / float2(frame.dimensions.xy)) * 2.0 - 1.0;
    float3 origin = frame.position.xyz;
    float3 direction = normalize(frame.forward.xyz +
                                 frame.right.xyz * (screen.x * frame.forward.w * frame.position.w) -
                                 frame.up.xyz * (screen.y * frame.position.w));
    float3 throughput = float3(1);
    float etaScale = 1.0;
    float3 previous = origin;
    float primaryDistance = 0;
#if defined(PRIME_LIGHT_TREE_SPHERE)
    float3 previousLightNormal = float3(0);
#endif
    float previousPdf = 0;
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
            if (!hit.found) {
                float3 escaped = atmSky(environment, direction, frame.up.w);
                if (atmSunContains(environment, direction)) {
                    float weight = previousPdf == 0
                                           ? 1.0
                                           : primePowerHeuristicValue(previousPdf, atmSunPdf());
                    escaped += weight * atmSunRadiance(environment, direction) * frame.right.w;
                }
                radiance += throughput * escaped;
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
            {
                float lightPdf = previousPdf == 0 || lightFeatures == 0
                                         ? 0
                                         : emissiveHitPdf(resources, hit, previous
#if defined(PRIME_LIGHT_TREE_SPHERE)
                                                          ,
                                                          previousLightNormal
#endif
                                           );
                float emissionWeight =
                        lightPdf == 0 ? 1.0 : primePowerHeuristicValue(previousPdf, lightPdf);
                radiance += primeTripleProduct(throughput, linear709ToWorking(hit.emission),
                                               emissionWeight);
            }
        }
        primeTransportDirect(resources, atmLighting(environment), energy, sampler, bounce,
                             asuint(frame.displayExtra.z), frame.right.w, surface, material,
                             direction, medium, incident, transmitted, thin, throughput, radiance);
        float3 view = -direction;
        bool hasNormal = primePbrVertexHasNormal(material);
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
        etaScale = primeEtaScaleAfterScatter(etaScale, scatter.relativeEta,
                                             (scatter.eventFlags & PRIME_BSDF_EVENT_TRANSMISSION) !=
                                                     0u);
        direction = scatter.direction;
        medium = sampled.medium;
        previous = surface.position;
#if defined(PRIME_LIGHT_TREE_SPHERE)
        previousLightNormal = primePbrVertexIsDielectric(material) ? float3(0) : material.normal;
#endif
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
        radiance = t * radiance + s * frame.right.w;
    }
    radiance = float3(primeBsdfFinite(radiance.x) ? max(radiance.x, 0.0) : 0.0,
                      primeBsdfFinite(radiance.y) ? max(radiance.y, 0.0) : 0.0,
                      primeBsdfFinite(radiance.z) ? max(radiance.z, 0.0) : 0.0);
    return radiance;
}
