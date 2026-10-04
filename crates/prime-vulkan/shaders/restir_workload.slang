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

int2 previousPixel(RestirScene resources, SurfaceHit hit) {
    if (!hit.found || parameters.control.y == 0u)
        return int2(-1);
    Frame previous = parameters.previousFrame;
    // Accepted temporal validity proves that published geometry, placement, source and
    // anchor are unchanged. Thus the actual current hit is also its previous position,
    // including stationary dynamic instances; no unsupported host motion is inferred.
    float3 delta = hit.surface.position - previous.position.xyz;
    float z = dot(delta, previous.forward.xyz);
    if (!(z > 0.0))
        return int2(-1);
    float2 screen =
            float2(dot(delta, previous.right.xyz) / (z * previous.forward.w * previous.position.w),
                   -dot(delta, previous.up.xyz) / (z * previous.position.w));
    float2 location = (screen * 0.5 + 0.5) * float2(previous.dimensions.xy);
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
        float3 wo = -restirPrimaryDirection(resources.frame, id.xy);
        SurfaceHit hit = restirLoadHit(resources, primary.hit, wo, primary.coverageSeed, 0.0);
        NeighborValidMask mask = {0u, parameters.current[offset].M};
        for (uint i = 0; i < 3; ++i)
            parameters.retraceOffsets[3u * offset + i] = 0xffffffffu;
        if (pass.phase == 0u) {
            int2 pixel = previousPixel(resources, hit);
            parameters.reprojection[offset] = pixel;
            if (primary.hit.isValid() && restirInBounds(pixel, extent)) {
                uint previousOffset = restirOffset(uint2(pixel), extent);
                if (parameters.previousPrimary[previousOffset].hit.isValid()) {
                    mask.data = 1u;
                    candidateMask =
                            uint(parameters.current[offset].pathFlags.rcVertexLength() > 1) |
                            uint(parameters.history[previousOffset].pathFlags.rcVertexLength() > 1)
                                    << 1;
                }
            }
        } else if (primary.hit.isValid()) {
            PairedNeighborTransform pairing = restirPairing(parameters, pass.sampleOffset);
            float distanceToCamera = length(hit.surface.position - resources.frame.position.xyz);
            float3 normal = restirMaterial(hit, wo).normal;
            for (uint i = 0; i < 3; ++i) {
                int2 pixel = restirNeighbor(parameters, pairing, id.xy, i);
                if (!restirInBounds(pixel, extent))
                    continue;
                uint neighborOffset = restirOffset(uint2(pixel), extent);
                RestirPrimary neighbor = parameters.primary[neighborOffset];
                if (!neighbor.hit.isValid())
                    continue;
                float3 neighborWo = -restirPrimaryDirection(resources.frame, uint2(pixel));
                SurfaceHit neighborHit = restirLoadHit(resources, neighbor.hit, neighborWo,
                                                       neighbor.coverageSeed, 0.0);
                float neighborDistance =
                        length(neighborHit.surface.position - resources.frame.position.xyz);
                // Original paired-neighbor geometry rejection is symmetric.
                if (dot(normal, restirMaterial(neighborHit, neighborWo).normal) < 0.5 ||
                    abs(distanceToCamera - neighborDistance) >=
                            0.1 * max(distanceToCamera, neighborDistance))
                    continue;
                mask.setValid(i, true);
                mask.cSum += parameters.current[neighborOffset].M;
                candidateMask |=
                        uint(parameters.current[neighborOffset].pathFlags.rcVertexLength() > 1)
                        << i;
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
    for (uint i = 0; i < 3; ++i) {
        if ((candidateMask & (1u << i)) != 0u) {
            parameters.workItems[base + item] = uint2(id.y * extent.x + id.x, i);
            parameters.retraceOffsets[3u * offset + i] = base + item;
            ++item;
        }
    }
}
