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
module restir_duplicate_map;
import "restir/parameters.slang";
import "restir/indexing.slang";
[[vk::binding(23, 0)]] ConstantBuffer<RestirParameters> parameters;
groupshared uint sampleIds[1024];

// RA-015, docs/restir-adaptations.md: upstream 17x17 duplication window (exclude self).
// Boundary lanes still fill the 32x32 tile and reach its barrier before leaving.
[numthreads(16, 16, 1)] void main(uint3 id : SV_DispatchThreadID, uint3 local : SV_GroupThreadID,
                                  uint3 group : SV_GroupID) {
    uint2 extent = parameters.frame.dimensions.xy;
    int2 topLeft = int2(group.xy * 16u) - 8;
    for (uint i = 0u; i < 2u; ++i)
        for (uint j = 0u; j < 2u; ++j) {
            uint2 tile = 2u * local.xy + uint2(j, i);
            int2 pixel = topLeft + int2(tile);
            sampleIds[tile.y * 32u + tile.x] =
                    restirInBounds(pixel, extent)
                            ? parameters.sampleIds[restirOffset(uint2(pixel), extent)]
                            : 0u;
        }
    GroupMemoryBarrierWithGroupSync();
    if (any(id.xy >= extent))
        return;
    uint own = sampleIds[(local.y + 8u) * 32u + local.x + 8u];
    uint duplicates = 0u;
    if (own != 0u) {
        for (int i = -8; i <= 8; ++i)
            for (int j = -8; j <= 8; ++j) {
                int2 pixel = int2(id.xy) + int2(j, i);
                if (!restirInBounds(pixel, extent) || (i == 0 && j == 0))
                    continue;
                uint neighbor = sampleIds[(int(local.y) + i + 8) * 32 + int(local.x) + j + 8];
                duplicates += uint(own == neighbor);
            }
    }
    parameters.duplicateCounts[restirOffset(id.xy, extent)] = duplicates;
}
