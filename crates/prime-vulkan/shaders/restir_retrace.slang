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
module restir_retrace;
#include "restir/bindings.slang"
[numthreads(64, 1, 1)] void main(uint3 id : SV_DispatchThreadID) {
    if (id.x >= parameters.queueControl[0])
        return;
    uint2 item = parameters.workItems[id.x];
    uint2 extent = parameters.frame.dimensions.xy;
    uint2 pixel = uint2(item.x % extent.x, item.x / extent.x);
    uint offset = restirOffset(pixel, extent);
    RestirScene resources = restirScene();
    RestirPrimary primary;
    PathReservoir reservoir;
    uint2 destination = pixel;
    if (pass.phase == 0u) {
        uint2 previous = uint2(parameters.reprojection[offset]);
        uint previousOffset = restirOffset(previous, extent);
        if (item.y == 0u) {
            primary = parameters.previousPrimary[previousOffset];
            reservoir = parameters.current[offset];
            resources = restirScene(true);
            destination = previous;
        } else {
            primary = parameters.primary[offset];
            reservoir = parameters.history[previousOffset];
        }
    } else {
        PairedNeighborTransform pairing = restirPairing(parameters, pass.sampleOffset);
        uint2 neighbor = uint2(restirNeighbor(parameters, pairing, pixel, item.y));
        primary = parameters.primary[offset];
        reservoir = parameters.current[restirOffset(neighbor, extent)];
    }
    float3 wo = -restirPrimaryDirection(resources.frame, destination);
    parameters.retrace[id.x] = restirReplay(resources, primary, wo, reservoir);
}
