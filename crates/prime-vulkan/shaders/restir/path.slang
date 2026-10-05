/***************************************************************************
 # Copyright (c) 2015-26, NVIDIA CORPORATION. All rights reserved.
 #
 # Redistribution and use in source and binary forms, with or without
 # modification, are permitted provided that the following conditions
 # are met:
 #  * Redistributions of source code must retain the above copyright
 #    notice, this list of conditions and the following disclaimer.
 #  * Redistributions in binary form must reproduce the above copyright
 #    notice, this list of conditions and the following disclaimer in the
 #    documentation and/or other materials provided with the distribution.
 #  * Neither the name of NVIDIA CORPORATION nor the names of its
 #    contributors may be used to endorse or promote products derived
 #    from this software without specific prior written permission.
 #
 # THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS "AS IS" AND ANY
 # EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 # IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR
 # PURPOSE ARE DISCLAIMED.  IN NO EVENT SHALL THE COPYRIGHT OWNER OR
 # CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL,
 # EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO,
 # PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR
 # PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY
 # OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 # (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 # OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 **************************************************************************/

#language slang 2026
module "restir/path.slang";
import "restir/scene.slang";
import "restir/reservoir.slang";
import "restir/rng.slang";
__exported import "restir/types.slang";
import "ray_query.slang";
import "math/color.slang";
import "math/ray_offset.slang";
import "math/transport.slang";
import "pbr.slang";
import "service/bsdf/contract/sample.slang";
import "service/bsdf/support/sanitization.slang";

struct RestirSegment {
    float geometry;
    float inverseGeometry;
    float footprint;
    LastVertexState previous;
};

RestirSegment restirSegment(SurfaceHit hit, float3 origin, float3 direction, float3 previousNormal,
                            float scatterPdf, uint flags, bool rough, float footprintThreshold) {
    // RA-003/004, docs/restir-adaptations.md: preserve source query-origin geometry
    // and geometric predecessor ng, including the reverse footprint's area measure.
    float3 displacement = hit.surface.position - origin;
    float distanceSquared = dot(displacement, displacement);
    RestirSegment segment;
    segment.geometry = abs(dot(hit.surface.normal, -direction)) / distanceSquared;
    segment.inverseGeometry = distanceSquared / abs(dot(previousNormal, -direction));
    segment.footprint = 1.0 / (segment.geometry * scatterPdf);
    segment.previous.Init((flags & PRIME_BSDF_EVENT_DISCRETE) != 0,
                          (flags & PRIME_BSDF_EVENT_TRANSMISSION) != 0, 0, false, rough,
                          segment.footprint >= footprintThreshold);
    return segment;
}

bool restirCanReconnect(SurfaceHit hit, float3 view) {
    PrimePbrVertex material = restirMaterial(hit, view);
    return material.roughness > 0 || (!hit.optical && !primePbrVertexIsConductor(material));
}

void restirForcedNeeVertex(inout PathRISState ris, RestirLightSample light, SurfaceHit receiver,
                           float3 view, uint length) {
    // RA-005, docs/restir-adaptations.md: terminal NEE keeps the sampled emitter's
    // physical receiver chart; it is distinct from BSDF arrivals at an RC.
    ris.reconnectionJacobian = light.geometry;
    ris.integrand *= light.pdf;
    ris.reconnectionIncidentRadiance = light.Le;
    ris.reconnectionWi = light.direction;
    ris.reconnectionHit = light.hit;
    ris.pathFlags.insertRcVertexLength(int(length + 1));
    ris.pathFlags.insertIsTransmissionEvent(
            dot(receiver.surface.normal, light.direction) * dot(receiver.surface.normal, view) < 0,
            true);
    ris.pathFlags.insertSupportBSDFIndexingBeforeRcVertex(false);
    // Local light PDFs and the mixed-measure Jacobian are stored in area measure.
    if (light.hit.isValid())
        ris.lightPdf *= light.geometry;
}

void restirAddEnvironment(inout PathRISState ris, PathReconnectionInfo rc, float3 throughput,
                          float sourcePdf, float previousPdf, float lastScatterPdf, uint flags,
                          bool rough, uint bounce, float3 direction, RestirScene scene,
                          inout SampleGenerator sg) {
    // RA-006, docs/restir-adaptations.md: only finite sun has an NEE competitor.
    // Keep separate RIS domains to preserve Prime's sky/sun source contract.
    for (uint type = 0; type < 2; ++type) {
        uint lightType = type == 0 ? RESTIR_LIGHT_SKY : RESTIR_LIGHT_SUN;
        float u = sampleNext1D(sg);
        float3 Le = restirEnvironmentRadiance(scene, lightType, direction);
        float lightPdf = restirEnvironmentPdf(scene, lightType, direction);
        float mis = previousPdf > 0 && lightPdf > 0
                            ? primePowerHeuristicValue(previousPdf, lightPdf)
                            : 1.0;
        bool selected = ris.addBSDFHitCandidate(u, rc, bounce - 1, throughput * Le * mis, direction,
                                                Le, Le * mis, sourcePdf, lightPdf, lightType);
        if (selected && rc.pathFlags.rcVertexLength() == RESTIR_MAX_RC_LENGTH && rough) {
            LastVertexState previous;
            previous.Init((flags & PRIME_BSDF_EVENT_DISCRETE) != 0,
                          (flags & PRIME_BSDF_EVENT_TRANSMISSION) != 0, 0, false, rough, true);
            ris.integrand *= lastScatterPdf;
            ris.markAsRcVertex(bounce, HitInfo(), previous, lightPdf, lightType, Le, direction, 1);
        }
    }
}

public interface IRestirPrimaryObserver {
    [mutating] void capturePrimary(SurfaceHit hit, float3 view);
    [mutating] void captureFirstScatter(float distance, uint eventFlags);
}
public struct NoRestirPrimaryObserver : IRestirPrimaryObserver {
    [mutating] public void capturePrimary(SurfaceHit hit, float3 view) {}[mutating]
            public void captureFirstScatter(float distance, uint eventFlags) {}
};

public PathReservoir restirGenerate<T : IRestirPrimaryObserver>(RestirScene scene, uint2 pixel,
                                                                uint sequence,
                                                                out RestirPrimary primary,
                                                                out float3 residual,
                                                                inout T observer) {
    SampleGenerator sg = SampleGenerator(pixel, sequence * 4);
    uint seed = sg.rng;
    primary.coverageSeed = seed;
    primary.hit = HitInfo();
    residual = float3(0);
    float distanceThreshold, roughnessThreshold;
    restirSampleThresholds(sg, distanceThreshold, roughnessThreshold);
    PathRISState ris = {};
    PathReconnectionInfo rc = {};
    rc.initRandomSeed = seed;
    rc.rcThroughput = float3(1);
    rc.pathFlags.insertRcVertexLength(RESTIR_MAX_RC_LENGTH);
    float3 throughput = float3(1);
    float sourcePdf = 1;
    float3 origin = scene.frame.position.xyz;
    float3 direction = restirPrimaryDirection(scene.frame, pixel, scene.sampleJitter);
    float3 previousPosition = origin, previousNormal = -direction;
    float previousPdf = 0, scatterPdf = 1, lastScatterPdf = 1;
    uint previousFlags = 0;
    bool previousRough = false;
    float primaryFootprint = 0;
    float4 medium = float4(1, 0, 0, 0);
    uint budget = min(asuint(scene.frame.displayExtra.z), 254u);
    for (uint bounce = 0; bounce < budget; ++bounce) {
        HitInfo identity;
        SurfaceHit hit = restirTrace(scene, origin, direction, seed, identity);
        if (bounce == 0) {
            primary.hit = identity;
            observer.capturePrimary(hit, -direction);
        } else if (bounce == 1) {
            observer.captureFirstScatter(hit.found ? length(hit.surface.position - previousPosition)
                                                   : 65504.0,
                                         previousFlags);
        }
        if (!hit.found) {
            if (bounce == 0) {
                residual = restirEnvironmentRadiance(scene, RESTIR_LIGHT_SKY, direction) +
                           restirEnvironmentRadiance(scene, RESTIR_LIGHT_SUN, direction);
            } else {
                restirAddEnvironment(ris, rc, throughput, sourcePdf, previousPdf, lastScatterPdf,
                                     previousFlags, previousRough, bounce, direction, scene, sg);
            }
            break;
        }
        float3 view = -direction;
        if (bounce == 0) {
            medium = hit.thin ? float4(1, 0, 0, 0) : hit.incident;
            primaryFootprint = restirPrimaryFootprint(scene, hit, view);
        }
        float3 attenuation = opticalFeatures != 0
                                     ? exp(-medium.yzw * length(hit.surface.position - origin))
                                     : float3(1);
        throughput *= attenuation;
        rc.rcThroughput *= attenuation;
        float threshold = primaryFootprint * distanceThreshold;
        RestirSegment segment = restirSegment(hit, origin, direction, previousNormal, scatterPdf,
                                              previousFlags, previousRough, threshold);
        float uBsdf = sampleNext1D(sg);
        float3 Le = linear709ToWorking(hit.emission);
        if (bounce == 0) {
            residual += throughput * Le;
        } else if (any(Le > 0)) {
            float lightPdf = previousPdf > 0 ? restirEmitterPdf(scene, hit, previousPosition) : 0;
            float mis = lightPdf > 0 ? primePowerHeuristicValue(previousPdf, lightPdf) : 1.0;
            bool selected = ris.addBSDFHitCandidate(uBsdf, rc, bounce - 1, throughput * Le * mis,
                                                    direction, attenuation * Le, Le * mis,
                                                    sourcePdf, lightPdf, RESTIR_LIGHT_EMISSIVE);
            if (selected && rc.pathFlags.rcVertexLength() == RESTIR_MAX_RC_LENGTH &&
                min(segment.footprint, segment.inverseGeometry / RESTIR_DIFFUSE_PSEUDO_PDF) >=
                        threshold) {
                ris.integrand *= lastScatterPdf;
                ris.markAsRcVertex(bounce, identity, segment.previous, lightPdf * segment.geometry,
                                   RESTIR_LIGHT_EMISSIVE, Le, float3(0), segment.geometry);
            }
        }

        float uNee = sampleNext1D(sg);
        uint neeSeed = sg.rng;
        RestirLightSample light = restirSampleLight(scene, hit, view, medium, seed, sg);
        if (light.pdf > 0 && any(light.Le > 0) && any(light.visibility > 0)) {
            BsdfEvaluation evaluated = restirEvaluate(scene, hit, view, light.direction, medium);
            bool continuation = bounce + 1 < budget;
            float mis = continuation ? primePowerHeuristicValue(light.pdf, evaluated.pdf) : 1;
            float3 visibleLe = light.Le * light.visibility;
            float3 Lr = evaluated.response * visibleLe * (mis / light.pdf);
            bool canConnect =
                    bounce > 0 && rc.pathFlags.rcVertexLength() == RESTIR_MAX_RC_LENGTH &&
                    segment.previous.isLastVertexRough() &&
                    min(segment.footprint, segment.inverseGeometry / evaluated.pdf) >= threshold;
            bool selected = ris.addNEECandidate(uNee, rc, canConnect, bounce, throughput * Lr,
                                                light.direction, visibleLe, Lr, sourcePdf,
                                                light.pdf, light.type);
            if (selected && canConnect) {
                ris.reconnectionHit = identity;
                ris.reconnectionJacobian = segment.geometry;
                ris.integrand *= lastScatterPdf * light.pdf;
                ris.rcNextVertexBounceSeed = neeSeed;
                ris.pathFlags.tryInsertBounceTypesBeforeRcVertex(segment.previous.data & 0x3fu,
                                                                 true);
            } else if (selected && rc.pathFlags.rcVertexLength() == RESTIR_MAX_RC_LENGTH) {
                restirForcedNeeVertex(ris, light, hit, view, bounce);
            }
        }
        if (bounce + 1 >= budget)
            break;
        // RA-010, docs/restir-adaptations.md: retain upstream roulette draw/order and
        // proposal probability; hybrid replay skips the decision and probability reweighting.
        float roulette = sampleNext1D(sg);
        float survival = min(1.0, max(0.0, workingLuminance(throughput)));
        if (!(survival > 0) || roulette >= survival)
            break;
        throughput /= survival;
        sourcePdf *= survival;
        PrimePbrSample sampled = restirScatter(scene, hit, view, medium, sg);
        BsdfSample scatter = sampled.bsdfSample;
        if (scatter.eventFlags == 0)
            break;
        float samplePdf =
                (scatter.eventFlags & PRIME_BSDF_EVENT_DISCRETE) != 0 ? 1e20 : scatter.pdf;
        float sampleLastPdf =
                (scatter.eventFlags & PRIME_BSDF_EVENT_DISCRETE) != 0 ? 1 : scatter.pdf;
        bool rough = restirSampleRough(samplePdf, roughnessThreshold);
        bool canConnect = bounce > 0 && rc.pathFlags.rcVertexLength() == RESTIR_MAX_RC_LENGTH &&
                          segment.previous.isLastVertexRough() &&
                          min(segment.footprint, segment.inverseGeometry / samplePdf) >= threshold;
        float3 weight = scatter.response / scatter.pdf;
        throughput *= weight;
        rc.rcThroughput *= weight;
        if (canConnect) {
            rc.rcNextVertexBounceSeed = sg.rng;
            rc.reconnectionWi = scatter.direction;
            rc.pathFlags.insertRcVertexLength(int(bounce));
            rc.reconnectionHit = identity;
            rc.reconnectionJacobian = segment.geometry;
            rc.pathFlags.tryInsertBounceTypesBeforeRcVertex(segment.previous.data & 0x3fu, true);
            rc.pathFlags.tryInsertBounceTypesAfterRcVertex(
                    uint((scatter.eventFlags & PRIME_BSDF_EVENT_DISCRETE) != 0) |
                            (uint((scatter.eventFlags & PRIME_BSDF_EVENT_TRANSMISSION) != 0) << 1),
                    true);
            sourcePdf *= lastScatterPdf * sampleLastPdf;
            rc.rcThroughput = float3(1);
        }
        if (any(isnan(throughput) || isinf(throughput)) || all(throughput == 0))
            break;
        medium = sampled.medium;
        previousPosition = hit.surface.position;
        previousNormal = hit.surface.normal;
        // RA-007, docs/restir-adaptations.md: actual PDF stays in geometry; opaque
        // SSS transmission has zero direct-light competition.
        previousPdf = primeDirectLightingMisPdf(hit.optical, scatter);
        scatterPdf = samplePdf;
        lastScatterPdf = sampleLastPdf;
        previousFlags = scatter.eventFlags;
        previousRough = rough;
        direction = scatter.direction;
        origin = spawnRay(hit.surface, direction);
    }
    PathReservoir reservoir = PathReservoir::createFromRisState(ris, seed, float4(0));
    reservoir.finalizeReservoirWeight();
    reservoir.M = 1;
    return reservoir;
}

public PathReservoir restirGenerate(RestirScene scene, uint2 pixel, uint sequence,
                                    out RestirPrimary primary, out float3 residual) {
    NoRestirPrimaryObserver observer;
    return restirGenerate(scene, pixel, sequence, primary, residual, observer);
}

// RA-011, docs/restir-adaptations.md: Falcor TraceTemporalUpdate replays the current-scene
// suffix. Reconnection evaluates the RC's first scatter response, so exclude it here.
public bool restirUpdateSuffix(RestirScene scene, SurfaceHit rcHit, float3 rcView,
                               float4 incomingMedium, inout PathReservoir reservoir) {
    reservoir.reconnectionIncidentRadiance = float3(0);
    uint rcLength = uint(reservoir.pathFlags.rcVertexLength());
    uint pathLength = uint(reservoir.pathFlags.pathLength());
    bool nee = reservoir.pathFlags.lastVertexNEE();
    SampleGenerator sg = SampleGenerator(reservoir.rcNextVertexBounceSeed);
    if (nee && rcLength == pathLength) {
        RestirLightSample light = restirSampleLight(scene, rcHit, rcView, incomingMedium,
                                                    reservoir.initRandomSeed, sg);
        if (!(light.pdf > 0) || all(light.Le == 0) || all(light.visibility == 0))
            return false;
        BsdfEvaluation evaluated =
                restirEvaluate(scene, rcHit, rcView, light.direction, incomingMedium);
        if (!(evaluated.pdf > 0) || all(evaluated.response == 0))
            return false;
        reservoir.reconnectionIncidentRadiance = light.Le * light.visibility;
        reservoir.reconnectionWi = light.direction;
        reservoir.lightPdf = light.pdf;
        reservoir.pathFlags.insertLightType(light.type);
        return true;
    }
    float3 direction = reservoir.reconnectionWi;
    bool transmission =
            dot(rcHit.surface.normal, rcView) * dot(rcHit.surface.normal, direction) < 0;
    if (transmission != reservoir.pathFlags.decodeIsTransmissionEvent(false))
        return false;
    BsdfEvaluation evaluated =
            restirEvaluate(scene, rcHit, rcView, direction, incomingMedium, true);
    if (!(evaluated.pdf > 0) || all(evaluated.response == 0))
        return false;
    float4 medium = rcHit.optical && !rcHit.thin && transmission
                            ? rcHit.transmitted
                            : restirIncident(rcHit, incomingMedium);
    float3 origin = spawnRay(rcHit.surface, direction);
    float3 previousPosition = rcHit.surface.position;
    float previousPdf = restirDirectCompetitionPdf(rcHit.optical, transmission, evaluated.pdf);
    float3 throughput = float3(1);
    uint lightType = reservoir.pathFlags.lightType();
    uint budget = min(asuint(scene.frame.displayExtra.z), 254u);
    for (uint bounce = rcLength + 1; bounce < budget && bounce <= pathLength + 1; ++bounce) {
        HitInfo identity;
        SurfaceHit hit = restirTrace(scene, origin, direction, reservoir.initRandomSeed, identity);
        float3 view = -direction;
        if (!hit.found) {
            if (nee || bounce != pathLength + 1 || lightType == RESTIR_LIGHT_EMISSIVE)
                return false;
            float lightPdf = restirEnvironmentPdf(scene, lightType, direction);
            float mis = previousPdf > 0 && lightPdf > 0
                                ? primePowerHeuristicValue(previousPdf, lightPdf)
                                : 1;
            reservoir.reconnectionIncidentRadiance =
                    throughput * restirEnvironmentRadiance(scene, lightType, direction) *
                    (pathLength == rcLength ? 1 : mis);
            reservoir.lightPdf = lightPdf;
            return any(reservoir.reconnectionIncidentRadiance > 0);
        }
        throughput *= opticalFeatures != 0
                              ? exp(-medium.yzw * length(hit.surface.position - origin))
                              : float3(1);
        sampleNext1D(sg); // BSDF RIS, including terminal hits.
        if (!nee && bounce == pathLength + 1) {
            // Prime's split sky/sun RIS domains cannot be exchanged with finite endpoints.
            if (lightType != RESTIR_LIGHT_EMISSIVE)
                return false;
            float lightPdf = previousPdf > 0 ? restirEmitterPdf(scene, hit, previousPosition) : 0;
            float mis = lightPdf > 0 ? primePowerHeuristicValue(previousPdf, lightPdf) : 1;
            reservoir.reconnectionIncidentRadiance = throughput * linear709ToWorking(hit.emission) *
                                                     (pathLength == rcLength ? 1 : mis);
            reservoir.lightPdf = lightPdf;
            return any(reservoir.reconnectionIncidentRadiance > 0);
        }
        sampleNext1D(sg); // NEE RIS.
        if (nee && bounce == pathLength) {
            RestirLightSample light =
                    restirSampleLight(scene, hit, view, medium, reservoir.initRandomSeed, sg);
            if (!(light.pdf > 0) || all(light.Le == 0) || all(light.visibility == 0))
                return false;
            BsdfEvaluation direct = restirEvaluate(scene, hit, view, light.direction, medium);
            float mis = bounce + 1 < budget ? primePowerHeuristicValue(light.pdf, direct.pdf) : 1;
            reservoir.reconnectionIncidentRadiance =
                    throughput * direct.response * light.Le * light.visibility * (mis / light.pdf);
            reservoir.lightPdf = light.pdf;
            reservoir.pathFlags.insertLightType(light.type);
            return any(reservoir.reconnectionIncidentRadiance > 0);
        }
        restirSkipLightSample(sg);
        sampleNext1D(sg); // Roulette is replayed without its decision or reweighting.
        PrimePbrSample sampled = restirScatter(scene, hit, view, medium, sg);
        BsdfSample scatter = sampled.bsdfSample;
        if (scatter.eventFlags == 0)
            return false;
        throughput *= scatter.response / scatter.pdf;
        if (any(isnan(throughput) || isinf(throughput)) || all(throughput == 0))
            return false;
        medium = sampled.medium;
        previousPosition = hit.surface.position;
        previousPdf = primeDirectLightingMisPdf(hit.optical, scatter);
        direction = scatter.direction;
        origin = spawnRay(hit.surface, direction);
    }
    return false;
}

public RestirReplayData restirDirectReplayData(RestirScene scene, RestirPrimary primary,
                                               float3 primaryWo) {
    RestirReplayData result;
    result.data = ReconnectionData(HitInfo(), primaryWo, float3(0), 0);
    result.medium = float4(1, 0, 0, 0);
    if (!primary.hit.isValid())
        return result;
    SurfaceHit hit = restirLoadHit(scene, primary.hit, primaryWo, primary.coverageSeed);
    result.medium = hit.thin ? float4(1, 0, 0, 0) : hit.incident;
    float3 attenuation = opticalFeatures != 0
                                 ? exp(-result.medium.yzw *
                                       length(hit.surface.position - scene.frame.position.xyz))
                                 : float3(1);
    result.data = ReconnectionData(primary.hit, primaryWo, attenuation, 0);
    return result;
}

public RestirReplayData restirReplay(RestirScene scene, RestirPrimary primary, float3 primaryWo,
                                     PathReservoir reservoir) {
    RestirReplayData result;
    result.data = ReconnectionData(HitInfo(), float3(0), float3(0), 0);
    result.medium = float4(1, 0, 0, 0);
    if (!primary.hit.isValid())
        return result;
    SurfaceHit hit = restirLoadHit(scene, primary.hit, primaryWo, primary.coverageSeed);
    float3 throughput = float3(1), origin = scene.frame.position.xyz, direction = -primaryWo;
    float3 previousPosition = origin, previousNormal = primaryWo;
    uint previousFlags = 0;
    float previousPdf = 0, scatterPdf = 1;
    bool previousRough = false;
    float4 medium = hit.thin ? float4(1, 0, 0, 0) : hit.incident;
    float primaryFootprint = restirPrimaryFootprint(scene, hit, primaryWo);
    SampleGenerator sg = SampleGenerator(reservoir.initRandomSeed);
    float distanceThreshold, roughnessThreshold;
    restirSampleThresholds(sg, distanceThreshold, roughnessThreshold);
    float threshold = primaryFootprint * distanceThreshold;
    uint rcLength = uint(reservoir.pathFlags.rcVertexLength());
    uint pathLength = uint(reservoir.pathFlags.pathLength());
    bool shouldConnect = rcLength <= pathLength + 1;
    HitInfo identity = primary.hit;
    uint budget = min(asuint(scene.frame.displayExtra.z), 254u);
    for (uint bounce = 0; bounce <= budget; ++bounce) {
        if (bounce > 0)
            hit = restirTrace(scene, origin, direction, reservoir.initRandomSeed, identity);
        float3 view = -direction;
        if (!hit.found) {
            if (shouldConnect || bounce != pathLength + 1 || reservoir.pathFlags.lastVertexNEE() ||
                previousRough)
                return result;
            float lightPdf =
                    restirEnvironmentPdf(scene, reservoir.pathFlags.lightType(), direction);
            float mis = previousPdf > 0 && lightPdf > 0
                                ? primePowerHeuristicValue(previousPdf, lightPdf)
                                : 1;
            result.data.pathThroughput =
                    throughput *
                    restirEnvironmentRadiance(scene, reservoir.pathFlags.lightType(), direction) *
                    mis;
            result.medium = medium;
            return result;
        }
        throughput *= opticalFeatures != 0
                              ? exp(-medium.yzw * length(hit.surface.position - origin))
                              : float3(1);
        RestirSegment segment = restirSegment(hit, origin, direction, previousNormal, scatterPdf,
                                              previousFlags, previousRough, threshold);
        sampleNext1D(sg); // BSDF RIS
        if (bounce == rcLength - 1 && shouldConnect) {
            if (!restirCanReconnect(hit, view))
                return result;
            float factor =
                    !previousRough || segment.footprint < threshold ? -1 : segment.inverseGeometry;
            result.data = ReconnectionData(identity, view, throughput, factor);
            result.medium = medium;
            return result;
        }
        if (bounce == pathLength + 1 && !reservoir.pathFlags.lastVertexNEE()) {
            if (shouldConnect ||
                min(segment.footprint, segment.inverseGeometry / RESTIR_DIFFUSE_PSEUDO_PDF) >=
                        threshold)
                return result;
            float lightPdf = previousPdf > 0 ? restirEmitterPdf(scene, hit, previousPosition) : 0;
            float mis = lightPdf > 0 ? primePowerHeuristicValue(previousPdf, lightPdf) : 1;
            result.data.pathThroughput = throughput * linear709ToWorking(hit.emission) * mis;
            result.medium = medium;
            return result;
        }
        if (bounce >= budget || bounce == pathLength && reservoir.pathFlags.lastVertexNEE())
            return result;
        sampleNext1D(sg); // NEE RIS
        restirSkipLightSample(sg);
        sampleNext1D(sg); // Roulette draw; no probability/reweighting in replay.
        PrimePbrSample sampled = restirScatter(scene, hit, view, medium, sg);
        BsdfSample scatter = sampled.bsdfSample;
        if (scatter.eventFlags == 0)
            return result;
        float samplePdf =
                (scatter.eventFlags & PRIME_BSDF_EVENT_DISCRETE) != 0 ? 1e20 : scatter.pdf;
        if (bounce > 0 &&
            min(segment.footprint, segment.inverseGeometry / samplePdf) >= threshold &&
            segment.previous.isLastVertexRough())
            return result;
        throughput *= scatter.response / scatter.pdf;
        medium = sampled.medium;
        previousPosition = hit.surface.position;
        previousNormal = hit.surface.normal;
        previousPdf = primeDirectLightingMisPdf(hit.optical, scatter);
        scatterPdf = samplePdf;
        previousFlags = scatter.eventFlags;
        previousRough = restirSampleRough(samplePdf, roughnessThreshold);
        direction = scatter.direction;
        origin = spawnRay(hit.surface, direction);
    }
    return result;
}
