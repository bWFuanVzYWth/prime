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
module restir_shift;
#include "restir/bindings.slang"
import "restir/indexing.slang";
import "restir/pairing.slang";
import "restir/paired_neighbors.slang";
import "restir/path.slang";
import "restir/shift.slang";
import "restir/types.slang";
import "restir/reservoir.slang";
[numthreads(8, 8, 1)] void main(uint3 id : SV_DispatchThreadID) {
    uint2 extent = parameters.frame.dimensions.xy;
    if (any(id.xy >= extent))
        return;
    uint offset = restirOffset(id.xy, extent);
    NeighborValidMask mask = parameters.valid[offset];
    PairedNeighborTransform pairing = restirPairing(parameters, pass.sampleOffset);
    for (uint i = 0; i < 3u; ++i) {
        // Reciprocal validity is symmetric; merge reads only valid pairs. Valid mappings
        // that fail the shift still write an explicit zero contribution.
        if (!mask.isValid(i))
            continue;
        RestirScene resources = restirScene();
        uint2 neighbor = uint2(restirNeighbor(parameters, pairing, id.xy, i));
        RestirPrimary primary = parameters.primary[offset];
        PathReservoir reservoir = parameters.current[restirOffset(neighbor, extent)];
        float3 wo = -restirPrimaryDirection(resources.frame, id.xy, resources.sampleJitter);
        RestirReplayData replay;
        uint address = parameters.retraceOffsets[3u * offset + i];
        if (address != 0xffffffffu)
            replay = parameters.retrace[address];
        else
            replay = restirDirectReplayData(resources, primary, wo);
        PrecomputedShiftData shifted = restirShift(resources, primary, wo, reservoir, replay);
        parameters.shifts[3u * offset + i] = shifted;
    }
}
