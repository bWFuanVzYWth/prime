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

PrecomputedShiftData temporalShift(RestirScene resources, RestirPrimary primary, float3 wo,
                                   inout PathReservoir source, uint replayAddress) {
    RestirReplayData replay = replayAddress != 0xffffffffu
                                      ? parameters.retrace[replayAddress]
                                      : restirDirectReplayData(resources, primary, wo);
    return restirShift(resources, primary, wo, source, replay);
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
    temporal.M = min(20.0, temporal.M);
    float canonicalWeight;
    {
        // The original temporal pass computes each direction here, without a full-screen
        // precompute write/read. Its inverse mapping must not mutate the canonical source.
        PathReservoir inverseSource = canonical;
        RestirScene previousScene = restirScene(true);
        float3 previousWo = -restirPrimaryDirection(previousScene.frame, previousPixel);
        PrecomputedShiftData inverse =
                temporalShift(previousScene, parameters.previousPrimary[previousOffset], previousWo,
                              inverseSource, parameters.retraceOffsets[3u * offset]);
        canonicalWeight = temporalCanonicalWeight(
                canonical.M, temporal.M, PathRISState::toScalar(canonical.integrand),
                PathRISState::toScalar(inverse.pathThroughput) * inverse.Jacobian);
    }
    RestirScene resources = restirScene();
    float3 wo = -restirPrimaryDirection(resources.frame, id.xy);
    PrecomputedShiftData forward =
            temporalShift(resources, parameters.primary[offset], wo, temporal,
                          parameters.retraceOffsets[3u * offset + 1u]);
    float temporalWeight = temporalNeighborWeight(
            canonical.M, temporal.M, PathRISState::toScalar(temporal.integrand),
            PathRISState::toScalar(forward.pathThroughput) * forward.Jacobian);
    temporal.reconnectionJacobian = forward.reconnectionJacobian;
    SampleGenerator sg = TinyUniformSampleGenerator(
            id.xy,
            4u * (parameters.frame.target.y + pass.sampleOffset + parameters.frame.target.w) + 1u);
    PathReservoir result;
    result.init();
    result.merge(forward.pathThroughput, forward.Jacobian, temporal, sg, temporalWeight);
    result.merge(canonical.integrand, 1.0, canonical, sg, canonicalWeight);
    result.finalizeReservoirWeight();
    result.weight = isfinite(result.weight) ? max(0.0, result.weight) : 0.0;
    parameters.current[offset] = result;
}
