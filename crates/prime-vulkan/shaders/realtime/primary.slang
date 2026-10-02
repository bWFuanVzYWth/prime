#language slang 2026
module "realtime/primary.slang";
import "math/color.slang";
import "math/ray_offset.slang";
import "math/transport.slang";
import "math/z_sobol.slang";
import "pbr/vertex.slang";
import "pbr/delta.slang";
import "trace/closest.slang";
import "service/bsdf/contract/sample.slang";
import "service/bsdf/support/sanitization.slang";
import "realtime/landing.slang";
import "realtime/primary_parameters.slang";
import "realtime/primary_guide_contract.slang";
import "reconstruct/primary_psr.slang";
import "realtime/primary_environment.slang";

public static const uint PRIME_PRIMARY_BRANCH_DOMAIN = 1536u;

// This seed is stored only after the two paths diverge. It has no lighting contribution state.
// The five planes temporarily alias K2's not-yet-published common landing allocation.
struct PrimePrimaryGuideSeed {
    float3 origin;
    float coneWidth;
    float3 previous;
    uint bounce;
    float3 direction;
    float mediumIor;
    PrimePrimaryPsr psr;
};

PrimePrimaryGuideSeed primePrimaryEmptySeed() {
    PrimePrimaryGuideSeed seed = {};
    return seed;
}

void primePrimaryStoreSeed(PrimaryParameters parameters, uint index, PrimePrimaryGuideSeed seed) {
    uint count = parameters.sampling.x * parameters.sampling.y;
    primeRealtimeStorePlane(parameters.commonAddress, count, index, 0,
                            float4(seed.origin, seed.coneWidth));
    primeRealtimeStorePlane(
            parameters.commonAddress, count, index, 1,
            float4(seed.previous,
                   asfloat(seed.bounce | ((seed.psr.flags | PRIME_PRIMARY_GUIDE_SEED) << 8))));
    primeRealtimeStorePlane(parameters.commonAddress, count, index, 2,
                            float4(seed.direction, seed.mediumIor));
    primeRealtimeStorePlane(parameters.commonAddress, count, index, 3, seed.psr.directionLength);
    primeRealtimeStorePlane(parameters.commonAddress, count, index, 4, seed.psr.rotation);
}

PrimePrimaryGuideSeed primePrimaryLoadSeed(PrimaryParameters parameters, uint index) {
    uint count = parameters.sampling.x * parameters.sampling.y;
    float4 ray = primeRealtimeLoadPlane(parameters.commonAddress, count, index, 0);
    float4 physical = primeRealtimeLoadPlane(parameters.commonAddress, count, index, 1);
    float4 direction = primeRealtimeLoadPlane(parameters.commonAddress, count, index, 2);
    PrimePrimaryGuideSeed seed;
    seed.origin = ray.xyz;
    seed.coneWidth = ray.w;
    seed.previous = physical.xyz;
    seed.bounce = asuint(physical.w) & 0xffu;
    seed.direction = direction.xyz;
    seed.mediumIor = direction.w;
    seed.psr = {primeRealtimeLoadPlane(parameters.commonAddress, count, index, 3),
                primeRealtimeLoadPlane(parameters.commonAddress, count, index, 4),
                asuint(physical.w) >> 8};
    return seed;
}

PrimePrimaryGuideSeed primePrimaryFinishInactive(PrimaryParameters parameters, uint index,
                                                 bool detached, float3 radiance,
                                                 float primaryDistance) {
    // Load every seed plane before publishing the overlapping landing/status.
    PrimePrimaryGuideSeed seed =
            detached ? primePrimaryLoadSeed(parameters, index) : primePrimaryEmptySeed();
    primeStoreInactiveLanding(parameters.commonAddress,
                              parameters.sampling.x * parameters.sampling.y, index);
    parameters.prefixAddress[index] = float4(radiance, primaryDistance);
    return seed;
}

PrimePrimaryGuideSeed primePrimaryFinishLanding(PrimaryParameters parameters, uint index,
                                                bool detached, PrimeLanding landing,
                                                float3 radiance, float primaryDistance) {
    PrimePrimaryGuideSeed seed =
            detached ? primePrimaryLoadSeed(parameters, index) : primePrimaryEmptySeed();
    primeStoreLanding(parameters.commonAddress, parameters.opticalAddress,
                      parameters.sampling.x * parameters.sampling.y, index, landing);
    parameters.prefixAddress[index] = float4(radiance, primaryDistance);
    return seed;
}

PrimePbrVertex primePrimaryMaterial(SurfaceHit hit, float3 direction) {
    float3 normal =
            dot(hit.shadingNormal, direction) > 0.0 ? -hit.shadingNormal : hit.shadingNormal;
    return primePbrVertex(sourceToWorking(saturate(hit.sourceColor.rgb)), normal,
                          hit.normalSample.a, hit.specularSample.rgb, (hit.materialFlags & 1) != 0,
                          (hit.materialFlags & 2) != 0, hit.materialThin, hit.optical, -direction);
}

PrimePrimaryGuideSeed primePrimaryNextGuide(PrimaryParameters parameters, SurfacePoint surface,
                                            PrimePbrVertex material, float3 incoming,
                                            float3 previous, float coneWidth, uint bounce,
                                            float4 incident, float4 transmitted,
                                            PrimePbrGuideDirection next, PrimePrimaryPsr psr,
                                            bool staticGeometry) {
    bool reflection = (next.eventFlags & PRIME_BSDF_EVENT_REFLECTION) != 0;
    bool refracted =
            !reflection && !primePbrVertexIsThinWalled(material) && incident.x != transmitted.x;
    PrimePrimaryGuideSeed seed;
    seed.origin = spawnRay(surface, next.direction);
    seed.previous = surface.position;
    seed.coneWidth = coneWidth;
    seed.bounce = bounce + 1u;
    seed.direction = next.direction;
    seed.mediumIor = next.nextIor;
    seed.psr = primePrimaryPsrAppend(psr, parameters.position.xyz, previous, surface.position,
                                     material.normal, reflection, refracted, staticGeometry);
    return seed;
}

// Lighting owns this loop. Once detached, the guide seed lives in memory rather than as a second
// path state across its queries. The returned seed becomes live only after lighting has finished.
PrimePrimaryGuideSeed primePrimaryLighting<Guides, Environment>(
        TraceScene resources, Environment environment, PrimaryParameters parameters, uint2 pixel,
        ZSobol sampler, float2 sampleUv, float3 initialDirection, Guides guides) where Guides
    : IPrimaryGuides where Environment : IPrimaryEnvironment {
    uint index = pixel.y * parameters.sampling.x + pixel.x;
    float3 origin = parameters.position.xyz;
    float3 previous = origin;
    float3 direction = initialDirection;
    float3 throughput = float3(1.0), radiance = float3(0.0);
    float etaScale = 1.0, primaryDistance = 0.0, coneWidth = 0.0;
    float coneSpread =
            surfaceFeatures >= 2 ? 2.0 * parameters.position.w / float(parameters.sampling.y) : 0.0;
    float4 medium = float4(1, 0, 0, 0);
    PrimePrimaryPsr psr = primePrimaryPsrInitial();
    bool guideDetached = false, guideDone = !guides.enabled();
    bool pendingSpecularDistance = false;
    for (uint bounce = 0; bounce < parameters.config.y; ++bounce) {
        uint domain = 1u + 4u * bounce;
        SurfaceHit hit = traceClosest(resources, origin, direction, sampler.bits1D(domain),
                                      coneWidth, coneSpread);
        if (pendingSpecularDistance) {
            guides.specularDistance(pixel,
                                    hit.found ? length(hit.surface.position - previous) : 65504.0);
            pendingSpecularDistance = false;
        }
        if (!hit.found) {
            if (!guideDetached && !guideDone)
                guides.environment(parameters, pixel, sampleUv, direction, psr);
            radiance += throughput * environment.escape(direction);
            return primePrimaryFinishInactive(parameters, index, guideDetached, radiance,
                                              primaryDistance);
        }
        SurfacePoint surface = hit.surface;
        float segmentDistance = length(surface.position - origin);
        coneWidth += coneSpread * segmentDistance;
        if (opticalFeatures != 0) {
            if (bounce == 0)
                medium = hit.thin ? float4(1, 0, 0, 0) : hit.incident;
            throughput *= exp(-medium.yzw * segmentDistance);
        }
        if (dot(surface.normal, direction) > 0.0)
            surface.normal = -surface.normal;
        PrimePbrVertex material = primePrimaryMaterial(hit, direction);
        float4 incident = hit.optical && !hit.thin ? hit.incident : medium;
        float4 transmitted =
                hit.thin ? (hit.incident.x > hit.transmitted.x ? hit.incident : hit.transmitted)
                         : hit.transmitted;
        if (bounce == 0) {
            primaryDistance =
                    medium.x == 1.0 ? length(surface.position - parameters.position.xyz) : 0.0;
            if (parameters.config.z != 0)
                parameters.diagnosticAddress[index] =
                        float4(material.normal, dot(surface.position - parameters.position.xyz,
                                                    parameters.forward.xyz));
        }
        // Every predecessor handled by K1 is discrete, so hit emission has MIS weight one.
        radiance += primeTripleProduct(throughput, linear709ToWorking(hit.emission), 1.0);
        bool lightingValid = primeBsdfFinite(throughput) && any(throughput != float3(0.0));
        if (!primePbrVertexIsDiscreteOnly(material, incident, transmitted)) {
            if (!guideDetached && !guideDone)
                guides.surface(parameters, pixel, sampleUv, surface, material, direction, incident,
                               transmitted, hit.staticGeometry, psr, previous);
            if (!lightingValid)
                return primePrimaryFinishInactive(parameters, index, guideDetached, radiance,
                                                  primaryDistance);
            PrimeLanding landing = {
                    surface,     material,
                    direction,   coneWidth,
                    medium,      incident,
                    transmitted, throughput,
                    etaScale,    bounce | (hit.thin ? PRIME_LANDING_PHYSICAL_THIN : 0u)};
            if (guides.enabled() && !guideDetached && !guideDone &&
                (psr.flags & PRIME_PRIMARY_PSR_INTERFACE_SPECULAR) == 0)
                landing.flags |= PRIME_LANDING_GUIDE_DISTANCE;
            return primePrimaryFinishLanding(parameters, index, guideDetached, landing, radiance,
                                             primaryDistance);
        }

        bool continuation = primePathHasContinuation(bounce, parameters.config.y);
        PrimePrimaryGuideSeed nextGuide = primePrimaryEmptySeed();
        bool guideCanContinue = false;
        if (!guideDetached && !guideDone) {
            if (!continuation) {
                guides.unresolved(pixel);
                guideDone = true;
            } else {
                PrimePbrGuideDirection next = primePbrDeltaGuideDirection(
                        material, -direction, surface.normal, incident.x, transmitted.x);
                if (!next.geometricValid) {
                    guides.unresolved(pixel);
                    guideDone = true;
                } else {
                    if (bounce == 0 && hit.optical && !next.totalInternalReflection) {
                        guides.interfaceSpecular(pixel, material, -direction, incident,
                                                 transmitted);
                        psr.flags |= PRIME_PRIMARY_PSR_INTERFACE_SPECULAR;
                    }
                    nextGuide = primePrimaryNextGuide(parameters, surface, material, direction,
                                                      previous, coneWidth, bounce, incident,
                                                      transmitted, next, psr, hit.staticGeometry);
                    guideCanContinue = true;
                }
            }
        }
        if (!continuation)
            return primePrimaryFinishInactive(parameters, index, guideDetached, radiance,
                                              primaryDistance);

        PrimePbrSample sampled;
        sampled.bsdfSample = primeInvalidBsdfSample();
        sampled.medium = incident;
        bool survived = lightingValid;
        if (survived) {
            float branchWeight = 1.0;
            if (bounce == 0 && hit.optical)
                sampled = primeSamplePbrPrimaryDelta(material, -direction, surface.normal,
                                                     sampler.sample1D(PRIME_PRIMARY_BRANCH_DOMAIN),
                                                     incident, transmitted, branchWeight);
            else
                sampled = primeSamplePbrDelta(material, -direction, surface.normal,
                                              sampler.sample1D(1280u + bounce), incident,
                                              transmitted);
            survived = sampled.bsdfSample.eventFlags != 0;
            if (survived) {
                throughput = primeProductOver(throughput, sampled.bsdfSample.response,
                                              sampled.bsdfSample.pdf) *
                             branchWeight;
                etaScale = primeEtaScaleAfterScatter(
                        etaScale, sampled.bsdfSample.relativeEta,
                        (sampled.bsdfSample.eventFlags & PRIME_BSDF_EVENT_TRANSMISSION) != 0);
                survived = primeBsdfFinite(throughput) && any(throughput != float3(0.0));
                if (survived && primeRussianRouletteApplies(bounce, PRIME_RUSSIAN_ROULETTE_START)) {
                    float survival = primeRussianRouletteSurvival(throughput, etaScale);
                    survived = survival > 0.0;
                    if (survived && survival < 1.0) {
                        survived = sampler.sample1D(domain + 3u) < survival;
                        if (survived) {
                            throughput = primeRussianRouletteReweight(throughput, survival);
                            survived = primeBsdfFinite(throughput);
                        }
                    }
                }
            }
        }

        if (guideCanContinue) {
            bool guideReflection = (nextGuide.psr.flags & PRIME_PRIMARY_PSR_ODD) !=
                                   (psr.flags & PRIME_PRIMARY_PSR_ODD);
            bool lightingReflection =
                    (sampled.bsdfSample.eventFlags & PRIME_BSDF_EVENT_REFLECTION) != 0;
            // A discrete event has one deterministic direction. Same-event paths share the query.
            // A lighting termination is a separation point even when its sampled event matches.
            if (!survived || guideReflection != lightingReflection) {
                primePrimaryStoreSeed(parameters, index, nextGuide);
                guideDetached = true;
                psr = primePrimaryPsrInitial();
            } else {
                psr = nextGuide.psr;
            }
        }
        if (!survived)
            return primePrimaryFinishInactive(parameters, index, guideDetached, radiance,
                                              primaryDistance);
        if (guides.enabled() && bounce == 0 && hit.optical &&
            (sampled.bsdfSample.eventFlags & PRIME_BSDF_EVENT_REFLECTION) != 0 &&
            (nextGuide.psr.flags & PRIME_PRIMARY_PSR_INTERFACE_SPECULAR) != 0)
            pendingSpecularDistance = true;
        previous = surface.position;
        medium = sampled.medium;
        if (guideCanContinue && !guideDetached) {
            // Both consumers take this one geometric producer. Replaying the same guide seed
            // must use the identical ray even if the lighting BSDF arithmetic is optimized
            // differently; it also avoids spawning the shared ray a second time.
            direction = nextGuide.direction;
            origin = nextGuide.origin;
        } else {
            direction = sampled.bsdfSample.direction;
            origin = spawnRay(surface, direction);
        }
    }
    if (!guideDetached && !guideDone)
        guides.unresolved(pixel);
    return primePrimaryFinishInactive(parameters, index, guideDetached, radiance, primaryDistance);
}

void primePrimaryGuideWalk<Guides>(TraceScene resources, PrimaryParameters parameters, uint2 pixel,
                                   ZSobol sampler, float2 sampleUv, PrimePrimaryGuideSeed seed,
                                   Guides guides) where Guides : IPrimaryGuides {
    float coneSpread =
            surfaceFeatures >= 2 ? 2.0 * parameters.position.w / float(parameters.sampling.y) : 0.0;
    for (uint bounce = seed.bounce; bounce < parameters.config.y; ++bounce) {
        SurfaceHit hit = traceClosest(resources, seed.origin, seed.direction,
                                      sampler.bits1D(1u + 4u * bounce), seed.coneWidth, coneSpread);
        if (!hit.found) {
            guides.environment(parameters, pixel, sampleUv, seed.direction, seed.psr);
            return;
        }
        SurfacePoint surface = hit.surface;
        seed.coneWidth += coneSpread * length(surface.position - seed.origin);
        if (dot(surface.normal, seed.direction) > 0.0)
            surface.normal = -surface.normal;
        PrimePbrVertex material = primePrimaryMaterial(hit, seed.direction);
        // Guide direction/classification/albedo consume only the incident IOR. Extinction is
        // deliberately absent from guide state: it cannot terminate or select this path.
        float4 incident = hit.optical && !hit.thin ? hit.incident : float4(seed.mediumIor, 0, 0, 0);
        float4 transmitted =
                hit.thin ? (hit.incident.x > hit.transmitted.x ? hit.incident : hit.transmitted)
                         : hit.transmitted;
        if (!primePbrVertexIsDiscreteOnly(material, incident, transmitted)) {
            guides.surface(parameters, pixel, sampleUv, surface, material, seed.direction, incident,
                           transmitted, hit.staticGeometry, seed.psr, seed.previous);
            return;
        }
        if (!primePathHasContinuation(bounce, parameters.config.y))
            break;
        PrimePbrGuideDirection next = primePbrDeltaGuideDirection(
                material, -seed.direction, surface.normal, incident.x, transmitted.x);
        if (!next.geometricValid)
            break;
        seed = primePrimaryNextGuide(parameters, surface, material, seed.direction, seed.previous,
                                     seed.coneWidth, bounce, incident, transmitted, next, seed.psr,
                                     hit.staticGeometry);
    }
    guides.unresolved(pixel);
}

public void primeTracePrimary<Guides, Environment>(TraceScene resources, Environment environment,
                                                   PrimaryParameters parameters, uint2 pixel,
                                                   Guides guides) where Guides
    : IPrimaryGuides where Environment : IPrimaryEnvironment {
    ZSobol sampler = makeZSobolS8(pixel, parameters.sampling.z & 0xffu, parameters.config.x,
                                  parameters.sampling.w ^ (parameters.sampling.z >> 8));
    float2 sampleUv =
            (float2(pixel) + guides.sampleOffset(sampler)) / float2(parameters.sampling.xy);
    float2 screen = sampleUv * 2.0 - 1.0;
    float3 direction = normalize(parameters.forward.xyz +
                                 parameters.right.xyz *
                                         (screen.x * parameters.forward.w * parameters.position.w) -
                                 parameters.up.xyz * (screen.y * parameters.position.w));
    uint index = pixel.y * parameters.sampling.x + pixel.x;
    guides.initialize(pixel);
    if (parameters.config.z != 0)
        parameters.diagnosticAddress[index] = float4(0, 0, 0, 3.402823466e38f);
    PrimePrimaryGuideSeed seed = primePrimaryLighting(resources, environment, parameters, pixel,
                                                      sampler, sampleUv, direction, guides);
    if (guides.enabled() && (seed.psr.flags & PRIME_PRIMARY_GUIDE_SEED) != 0)
        primePrimaryGuideWalk(resources, parameters, pixel, sampler, sampleUv, seed, guides);
}
