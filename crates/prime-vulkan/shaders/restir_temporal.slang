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
module restir_temporal;
#include "restir/bindings.slang"
import "restir/indexing.slang";
import "restir/path.slang";
import "restir/shift.slang";
import "restir/types.slang";
import "restir/reservoir.slang";
import "restir/rng.slang";
import "restir/pairwise.slang";
import "restir/config.slang";
import "restir/history_support.slang";

PrecomputedShiftData temporalShift(RestirScene resources, RestirPrimary primary, float3 wo,
                                   inout PathReservoir source, uint replayAddress,
                                   bool updateSuffix = false) {
    RestirReplayData replay = replayAddress != 0xffffffffu
                                      ? parameters.retrace[replayAddress]
                                      : restirDirectReplayData(resources, primary, wo);
    return restirShift(resources, primary, wo, source, replay, updateSuffix);
}
[numthreads(8, 8, 1)] void main(uint3 id : SV_DispatchThreadID) {
    uint2 extent = parameters.frame.dimensions.xy;
    if (any(id.xy >= extent))
        return;
    uint offset = restirOffset(id.xy, extent);
    if (!parameters.valid[offset].isValid(0))
        return;
    uint2 previousPixel = uint2(parameters.reprojection[offset]);
    uint previousOffset = restirOffset(previousPixel, extent);
    PathReservoir canonical = parameters.current[offset];
    PathReservoir temporal = parameters.history[previousOffset];
    RestirScene resources = restirScene();
    // Workload validated both history identities; metadata is immutable until this consumer.
    uint duplicates = parameters.config.features.y != 0u && (parameters.config.queue.w & 4u) != 0u
                              ? parameters.duplicateCounts[previousOffset]
                              : 0u;
    temporal.M = min(restirDuplicateMCap(parameters.config, duplicates), temporal.M);
    uint stride = parameters.config.queue.y;
    float canonicalWeight = 1.0;
    // RA-009: a new-budget-only path has zero inverse target density. Avoid its
    // prefix/direct replay altogether; keep the independently supported forward donor.
    if (restirPathFitsBudget(canonical.pathFlags,
                             asuint(parameters.previousFrame.displayExtra.z))) {
        // The original temporal pass computes each direction here, without a full-screen
        // precompute write/read. Its inverse mapping must not mutate the canonical source.
        PathReservoir inverseSource = canonical;
        RestirScene previousScene = restirScene(true);
        if ((parameters.config.queue.w & 8u) != 0u)
            previousScene.config.shift =
                    parameters.sourceThresholds[parameters.currentProfiles[offset]];
        float3 previousWo = -restirPrimaryDirection(previousScene.frame, previousPixel,
                                                    previousScene.sampleJitter);
        PrecomputedShiftData inverse =
                temporalShift(previousScene, parameters.previousPrimary[previousOffset], previousWo,
                              inverseSource, parameters.retraceOffsets[stride * offset]);
        canonicalWeight = temporalCanonicalWeight(
                canonical.M, temporal.M, PathRISState::toScalar(canonical.integrand),
                PathRISState::toScalar(inverse.pathThroughput) * inverse.Jacobian);
    }
    float3 wo = -restirPrimaryDirection(resources.frame, id.xy, resources.sampleJitter);
    uint temporalProfile = 0u;
    if ((parameters.config.queue.w & 8u) != 0u) {
        temporalProfile = restirPreviousProfile(parameters, previousOffset);
        resources.config.shift = parameters.sourceThresholds[temporalProfile];
    }
    PrecomputedShiftData forward =
            temporalShift(resources, parameters.primary[offset], wo, temporal,
                          parameters.retraceOffsets[stride * offset + 1u], pass.reserved0 != 0u);
    float temporalWeight = temporalNeighborWeight(
            canonical.M, temporal.M, PathRISState::toScalar(temporal.integrand),
            PathRISState::toScalar(forward.pathThroughput) * forward.Jacobian);
    temporal.reconnectionJacobian = forward.reconnectionJacobian;
    SampleGenerator sg = TinyUniformSampleGenerator(
            id.xy,
            restirStreamCount(parameters.config) * (parameters.frame.target.y + pass.sampleOffset +
                                                    parameters.frame.target.w) +
                    restirTemporalStream(parameters.config));
    // RA-015: workload already selected the stochastic donor with these first draws.
    if (parameters.config.features.x != 0u)
        sampleNext2D(sg);
    PathReservoir result;
    result.init();
    result.merge(forward.pathThroughput, forward.Jacobian, temporal, sg, temporalWeight);
    bool selectedCanonical = result.merge(canonical.integrand, 1.0, canonical, sg, canonicalWeight);
    result.finalizeReservoirWeight();
    result.weight = isfinite(result.weight) ? max(0.0, result.weight) : 0.0;
    parameters.current[offset] = result;
    if ((parameters.config.queue.w & 8u) != 0u)
        parameters.currentProfiles[offset] =
                selectedCanonical ? parameters.currentProfiles[offset] : temporalProfile;
    // Upstream's pixel-local survival statistic is diagnostic, not a maximum path age.
    if ((parameters.config.queue.w & 1u) != 0u)
        parameters.currentAges[offset] = restirTemporalSurvival(
                parameters.config, selectedCanonical,
                (parameters.config.queue.w & 2u) != 0u ? parameters.previousAges[previousOffset]
                                                       : 0u);
}
