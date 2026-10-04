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
module "restir/shift.slang";
import "restir/scene.slang";
import "restir/path.slang";
import "restir/reservoir.slang";
import "restir/rng.slang";
import "ray_query.slang";
import "pbr.slang";
import "math/color.slang";
import "math/ray_offset.slang";
import "math/transport.slang";
import "service/bsdf/contract/sample.slang";

public PrecomputedShiftData restirShift(RestirScene scene, RestirPrimary primary, float3 primaryWo,
                                        inout PathReservoir reservoir, RestirReplayData replay,
                                        bool updateSuffix = false) {
    PrecomputedShiftData result = {};
    result.reconnectionJacobian = reservoir.reconnectionJacobian;
    if (reservoir.weight == 0 || all(replay.data.pathThroughput == 0))
        return result;
    uint rcLength = uint(reservoir.pathFlags.rcVertexLength());
    uint pathLength = uint(reservoir.pathFlags.pathLength());
    if (rcLength > pathLength + 1) {
        result.pathThroughput = replay.data.pathThroughput;
        result.Jacobian = 1;
        return result;
    }
    if (!primary.hit.isValid() || !replay.data.rcPrevHit.isValid() ||
        reservoir.pathFlags.decodeIsDeltaEvent(true) ||
        reservoir.pathFlags.decodeIsDeltaEvent(false))
        return result;
    SurfaceHit primaryHit = restirLoadHit(scene, primary.hit, primaryWo, primary.coverageSeed);
    float primaryFootprint = restirPrimaryFootprint(scene, primaryHit, primaryWo);
    SurfaceHit previous = rcLength == 1
                                  ? primaryHit
                                  : restirLoadHit(scene, replay.data.rcPrevHit,
                                                  replay.data.rcPrevWo, reservoir.initRandomSeed);
    float3 previousView = replay.data.rcPrevWo;
    bool emitter = rcLength == pathLength + 1;
    bool nee = reservoir.pathFlags.lastVertexNEE();
    bool neeAtRc = rcLength == pathLength && nee;
    bool finite = reservoir.reconnectionHit.isValid();
    SurfacePoint target = {};
    float distanceSquared = 0;
    float3 connection = reservoir.reconnectionWi;
    if (finite) {
        target = restirHitSurface(scene, reservoir.reconnectionHit);
        float3 displacement = target.position - previous.surface.position;
        distanceSquared = dot(displacement, displacement);
        if (!(distanceSquared > 0))
            return result;
        connection = displacement * rsqrt(distanceSquared);
    }
    bool transmission =
            dot(previous.surface.normal, connection) * dot(previous.surface.normal, previousView) <
            0;
    // Falcor fixes the ray-origin side to the source event; preserve its support
    // rather than allowing a reflection/transmission switch during reconnection.
    if (transmission != reservoir.pathFlags.decodeIsTransmissionEvent(true))
        return result;
    float4 medium = previous.optical && !previous.thin && transmission ? previous.transmitted
                                                                       : replay.medium;
    BsdfEvaluation first = restirEvaluate(scene, previous, previousView, connection, replay.medium);
    if (all(first.response == 0) || !(first.pdf > 0))
        return result;
    float geometry = finite ? abs(dot(target.normal, -connection)) / distanceSquared : 1;
    if (!(geometry > 0) || !(reservoir.reconnectionJacobian > 0))
        return result;
    float Jacobian = geometry / reservoir.reconnectionJacobian;
    SampleGenerator thresholds = SampleGenerator(reservoir.initRandomSeed);
    float distanceThreshold, roughnessThreshold;
    restirSampleThresholds(thresholds, distanceThreshold, roughnessThreshold);
    float threshold = primaryFootprint * distanceThreshold;
    // The preceding vertex must remain in the replay portion of the path in the inverse
    // mapping. Both source and destination use the same random soft threshold.
    if (rcLength > 1 && replay.data.rcFootprintFactor > 0 &&
        replay.data.rcFootprintFactor / first.pdf >= threshold)
        return result;
    bool violateRoughness = !(emitter && !nee) && !restirSampleRough(first.pdf, roughnessThreshold);
    bool violateDistance = finite && 1.0 / (first.pdf * geometry) < threshold;
    float3 integrand = float3(0);
    float3 emitterRadiance = float3(0);
    float emitterMis = 1;
    float secondPdf = 0;
    if (finite) {
        SurfaceHit rcHit = restirLoadHit(scene, reservoir.reconnectionHit, -connection,
                                         reservoir.initRandomSeed);
        if (emitter) {
            // All configured local proposals may depend on the receiver. Re-evaluate their
            // exact selection PDF instead of applying Falcor's position-independent shortcut.
            PrimePbrVertex previousMaterial = restirMaterial(previous, previousView);
            float3 proposalNormal = previous.optical ? float3(0) : previousMaterial.normal;
            float lightPdf =
                    restirEmitterPdf(scene, rcHit, previous.surface.position, proposalNormal);
            bool continuation = !nee || pathLength + 1 < asuint(scene.frame.displayExtra.z);
            float mis = continuation ? (nee ? primePowerHeuristicValue(lightPdf, first.pdf)
                                            : primePowerHeuristicValue(first.pdf, lightPdf))
                                     : 1;
            emitterRadiance = rcHit.emission;
            emitterMis = mis;
            if (updateSuffix) {
                reservoir.reconnectionIncidentRadiance = linear709ToWorking(emitterRadiance);
                reservoir.lightPdf = lightPdf * geometry;
            }
        } else {
            if (neeAtRc && !restirNeeMayReconnectAtSurface(rcHit))
                return result;
            if (updateSuffix && !restirUpdateSuffix(scene, rcHit, -connection, medium, reservoir))
                return result;
            integrand = reservoir.reconnectionIncidentRadiance * first.response;
            BsdfEvaluation second =
                    restirEvaluate(scene, rcHit, -connection, reservoir.reconnectionWi, medium);
            secondPdf = second.pdf;
            integrand *= second.response;
            if (rcLength == pathLength) {
                bool continuation = !nee || pathLength + 1 < asuint(scene.frame.displayExtra.z);
                float mis =
                        continuation
                                ? (neeAtRc ? primePowerHeuristicValue(reservoir.lightPdf, secondPdf)
                                           : primePowerHeuristicValue(secondPdf,
                                                                      reservoir.lightPdf))
                                : 1;
                integrand *= mis;
            }
        }
    } else {
        float lightPdf = restirEnvironmentPdf(scene, reservoir.pathFlags.lightType(), connection);
        if (updateSuffix) {
            reservoir.reconnectionIncidentRadiance =
                    restirEnvironmentRadiance(scene, reservoir.pathFlags.lightType(), connection);
            reservoir.lightPdf = lightPdf;
        }
        integrand = reservoir.reconnectionIncidentRadiance * first.response;
        bool continuation = !nee || pathLength + 1 < asuint(scene.frame.displayExtra.z);
        float mis = continuation ? (nee ? primePowerHeuristicValue(lightPdf, first.pdf)
                                        : primePowerHeuristicValue(first.pdf, lightPdf))
                                 : 1;
        integrand *= mis;
    }
    // PDF-based roughness is Falcor's custom-material route; emissive BSDF-hit endpoints
    // skip that test and use the diffuse pseudo-PDF for the reverse footprint, as upstream.
    if (!(emitter && nee)) {
        float inverseGeometry =
                finite ? distanceSquared / abs(dot(previous.shadingNormal, -connection)) : 0;
        if (violateRoughness || violateDistance ||
            !(emitter && !finite) &&
                    inverseGeometry / (emitter && !nee ? RESTIR_DIFFUSE_PSEUDO_PDF : secondPdf) <
                            threshold)
            return result;
    }
    float3 begin = spawnRay(previous.surface, connection);
    float3 visibility;
    if (finite) {
        float3 end = spawnRay(target, -connection);
        float3 segment = end - begin;
        float limit = length(segment);
        if (!(limit > 0))
            return result;
        visibility = traceTransmittance(scene.resources, begin, segment / limit,
                                        reservoir.initRandomSeed, limit, medium);
    } else {
        visibility = traceTransmittance(scene.resources, begin, connection,
                                        reservoir.initRandomSeed, 100000, medium);
    }
    // Match Prime's existing local-emitter transport order. RGB transmittance and
    // the non-diagonal Rec.709->working conversion cannot be interchanged.
    if (finite && emitter) {
        float3 visibleLe = nee ? linear709ToWorking(emitterRadiance * visibility)
                               : linear709ToWorking(emitterRadiance) * visibility;
        integrand = visibleLe * first.response * emitterMis * replay.data.pathThroughput;
    } else
        integrand *= visibility * replay.data.pathThroughput;
    if (!(Jacobian > 0) || isnan(Jacobian) || isinf(Jacobian) ||
        any(isnan(integrand) || isinf(integrand) || integrand < 0) || all(integrand == 0))
        return result;
    reservoir.reconnectionJacobian = geometry;
    result.reconnectionJacobian = geometry;
    result.Jacobian = Jacobian;
    result.pathThroughput = integrand;
    return result;
}
