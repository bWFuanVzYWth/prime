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
module restir_workload;
#include "restir/bindings.slang"
import "restir/indexing.slang";
import "restir/pairing.slang";
import "restir/paired_neighbors.slang";
import "restir/types.slang";
import "restir/reservoir.slang";
import "restir/history_support.slang";
import "restir/config.slang";
import "restir/rng.slang";
import "frame.slang";

int2 previousPixel(RestirScene resources, RestirPrimary primary, SurfaceHit hit,
                   inout SampleGenerator sg) {
    if (!hit.found || parameters.control.y == 0u)
        return int2(-1);
    Frame previous = parameters.previousFrame;
    // Prefer a proved rigid correspondence. Raw stationary dynamic hits can also use
    // their current point when the owning slot predates the accepted scene watermark.
    if (!hit.previousPositionKnown && !restirHistoryHitValid(resources, primary.hit))
        return int2(-1);
    float3 point = hit.previousPositionKnown ? hit.previousPosition : hit.surface.position;
    float3 delta = point - previous.position.xyz;
    float z = dot(delta, previous.forward.xyz);
    if (!(z > 0.0))
        return int2(-1);
    float2 screen =
            float2(dot(delta, previous.right.xyz) / (z * previous.forward.w * previous.position.w),
                   -dot(delta, previous.up.xyz) / (z * previous.position.w));
    // RA-002, docs/restir-adaptations.md: Falcor selects donors with no-jitter motion,
    // pixel+.5+previousProjection-currentProjection. The actual pinhole hit projects
    // to pixel+.5+currentJitter, so subtract current jitter here. Accepted previous
    // jitter still belongs to source-ray replay; subtracting it here advects noise.
    float2 location =
            (screen * 0.5 + 0.5) * float2(previous.dimensions.xy) - parameters.sampleJitter.xy;
    // RA-015: stochastic donor uses the first two draws of the temporal merge stream.
    location = restirStochasticLocation(location, parameters.config, sg);
    if (any(!isfinite(location)) || any(location < 0.0) ||
        any(location >= float2(previous.dimensions.xy)))
        return int2(-1);
    return int2(location);
}

[numthreads(8, 8, 1)] void main(uint3 id : SV_DispatchThreadID) {
    uint2 extent = parameters.frame.dimensions.xy;
    uint count = 0;
    uint candidateMask = 0;
    uint offset = 0;
    if (all(id.xy < extent)) {
        offset = restirOffset(id.xy, extent);
        RestirScene resources = restirScene();
        RestirPrimary primary = parameters.primary[offset];
        float3 wo = -restirPrimaryDirection(resources.frame, id.xy, resources.sampleJitter);
        SurfaceHit hit = restirLoadHit(resources, primary.hit, wo, primary.coverageSeed, 0.0);
        PathReservoir *sources = restirCurrentForPass(parameters, pass);
        uint stride = parameters.config.queue.y;
        NeighborValidMask mask = {0u, sources[offset].M};
        for (uint i = 0; i < stride; ++i)
            parameters.retraceOffsets[stride * offset + i] = 0xffffffffu;
        if (pass.phase == 0u) {
            SampleGenerator sg =
                    SampleGenerator(id.xy, (parameters.frame.target.y + pass.sampleOffset +
                                            parameters.frame.target.w) *
                                                           restirStreamCount(parameters.config) +
                                                   restirTemporalStream(parameters.config));
            int2 pixel = previousPixel(resources, primary, hit, sg);
            parameters.reprojection[offset] = pixel;
            if (primary.hit.isValid() && restirInBounds(pixel, extent)) {
                uint previousOffset = restirOffset(uint2(pixel), extent);
                HitInfo previousHit = parameters.previousPrimary[previousOffset].hit;
                PathReservoir history = parameters.history[previousOffset];
                if (restirPathFitsBudget(history.pathFlags,
                                         asuint(parameters.frame.displayExtra.z)) &&
                    previousHit.isValid() && restirHistoryHitValid(resources, previousHit) &&
                    (!history.reconnectionHit.isValid() ||
                     restirHistoryHitValid(resources, history.reconnectionHit))) {
                    mask.data = 1u;
                    candidateMask =
                            uint(parameters.current[offset].pathFlags.rcVertexLength() > 1) |
                            uint(parameters.history[previousOffset].pathFlags.rcVertexLength() > 1)
                                    << 1;
                }
            }
        } else if (primary.hit.isValid()) {
            PairedNeighborTransform pairing =
                    restirPairing(parameters, pass.sampleOffset, pass.reserved1);
            float distanceToCamera = length(hit.surface.position - resources.frame.position.xyz);
            float3 normal = restirMaterial(hit, wo).normal;
            for (uint i = 0; i < parameters.config.reuse.y; ++i) {
                int2 pixel = restirNeighbor(parameters, pairing, id.xy, i);
                if (!restirInBounds(pixel, extent))
                    continue;
                uint neighborOffset = restirOffset(uint2(pixel), extent);
                RestirPrimary neighbor = parameters.primary[neighborOffset];
                if (!neighbor.hit.isValid())
                    continue;
                float3 neighborWo = -restirPrimaryDirection(resources.frame, uint2(pixel),
                                                            resources.sampleJitter);
                SurfaceHit neighborHit = restirLoadHit(resources, neighbor.hit, neighborWo,
                                                       neighbor.coverageSeed, 0.0);
                float neighborDistance =
                        length(neighborHit.surface.position - resources.frame.position.xyz);
                // Original paired-neighbor geometry rejection is symmetric.
                if (dot(normal, restirMaterial(neighborHit, neighborWo).normal) <
                            parameters.config.validation.x ||
                    abs(distanceToCamera - neighborDistance) >=
                            parameters.config.validation.y *
                                    max(distanceToCamera, neighborDistance))
                    continue;
                mask.setValid(i, true);
                mask.cSum += sources[neighborOffset].M;
                candidateMask |= uint(sources[neighborOffset].pathFlags.rcVertexLength() > 1) << i;
            }
        }
        parameters.valid[offset] = mask;
        count = countbits(candidateMask);
    }
    // All lanes, including boundary lanes, participate in the wave reservation.
    uint waveCount = WaveActiveSum(count);
    uint base = 0u;
    if (WaveIsFirstLane())
        InterlockedAdd(parameters.queueControl[0], waveCount, base);
    base = WaveReadLaneFirst(base) + WavePrefixSum(count);
    uint item = 0u;
    for (uint i = 0; i < parameters.config.queue.y; ++i) {
        if ((candidateMask & (1u << i)) != 0u) {
            parameters.workItems[base + item] = uint2(id.y * extent.x + id.x, i);
            parameters.retraceOffsets[parameters.config.queue.y * offset + i] = base + item;
            ++item;
        }
    }
}
