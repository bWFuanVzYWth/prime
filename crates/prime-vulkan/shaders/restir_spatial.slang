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
module restir_spatial;
import "restir/parameters.slang";
import "restir/indexing.slang";
import "restir/pairing.slang";
import "restir/pairwise.slang";
import "restir/reservoir.slang";
import "restir/rng.slang";
import "restir/paired_neighbors.slang";
import "restir/config.slang";
[[vk::binding(23, 0)]] ConstantBuffer<RestirParameters> parameters;
[[vk::push_constant]] ConstantBuffer<RestirPass> pass;
[numthreads(8, 8, 1)] void main(uint3 id : SV_DispatchThreadID) {
    uint2 extent = parameters.frame.dimensions.xy;
    if (any(id.xy >= extent))
        return;
    uint offset = restirOffset(id.xy, extent);
    PathReservoir *sources = restirCurrentForPass(parameters, pass);
    PathReservoir *destination = restirDestinationForPass(parameters, pass);
    PathReservoir canonical = sources[offset];
    bool sourceProfiles = (parameters.config.queue.w & 8u) != 0u;
    uint canonicalProfile =
            sourceProfiles ? restirCurrentProfilesForPass(parameters, pass)[offset] : 0u;
    uint selectedProfile = canonicalProfile;
    bool decoupled =
            parameters.config.features.z != 0u && pass.reserved1 + 1u == parameters.config.reuse.z;
    if (!parameters.primary[offset].hit.isValid()) {
        destination[offset] = canonical;
        if (sourceProfiles)
            restirDestinationProfilesForPass(parameters, pass)[offset] = canonicalProfile;
        if (decoupled)
            parameters.shadingSum[offset] = float4(canonical.integrand * canonical.weight, 0);
        return;
    }
    NeighborValidMask mask = parameters.valid[offset];
    PairwiseFactor factors = {mask.cSum, canonical.M};
    float canonicalWeight = spatialCanonicalInitialWeight(factors);
    PairedNeighborTransform pairing = restirPairing(parameters, pass.sampleOffset, pass.reserved1);
    SampleGenerator sg = TinyUniformSampleGenerator(
            id.xy,
            restirStreamCount(parameters.config) * (parameters.frame.target.y + pass.sampleOffset +
                                                    parameters.frame.target.w) +
                    restirSpatialStream(parameters.config, pass.reserved1));
    // Match the original RNG consumption for the unused unpaired-neighbor start index.
    sampleNext1D(sg);
    PathReservoir result;
    result.init();
    float3 color = float3(0);
    uint stride = parameters.config.queue.y;
    for (uint i = 0; i < parameters.config.reuse.y; ++i) {
        if (!mask.isValid(i))
            continue;
        uint2 neighbor = uint2(restirNeighbor(parameters, pairing, id.xy, i));
        uint neighborOffset = restirOffset(neighbor, extent);
        PathReservoir source = sources[neighborOffset];
        // Reciprocal pairing reuses the other pixel's already computed reverse shift.
        PrecomputedShiftData inverse = parameters.shifts[stride * neighborOffset + i];
        PrecomputedShiftData forward = parameters.shifts[stride * offset + i];
        canonicalWeight += spatialCanonicalWeightTerm(
                factors, source.M, PathRISState::toScalar(canonical.integrand),
                PathRISState::toScalar(inverse.pathThroughput) * inverse.Jacobian);
        float neighborWeight = spatialNeighborWeight(
                factors, source.M, PathRISState::toScalar(source.integrand),
                PathRISState::toScalar(forward.pathThroughput) * forward.Jacobian);
        source.reconnectionJacobian = forward.reconnectionJacobian;
        if (decoupled)
            color += forward.pathThroughput * forward.Jacobian * source.weight * neighborWeight;
        bool selected =
                result.merge(forward.pathThroughput, forward.Jacobian, source, sg, neighborWeight);
        if (sourceProfiles && selected)
            selectedProfile = restirCurrentProfilesForPass(parameters, pass)[neighborOffset];
    }
    bool selectedCanonical = result.merge(canonical.integrand, 1.0, canonical, sg, canonicalWeight);
    if (decoupled) {
        // RA-015: Rao-Blackwellize the final color only; history keeps the same RIS winner.
        color += canonical.integrand * canonical.weight * canonicalWeight;
        parameters.shadingSum[offset] = float4(any(!isfinite(color)) ? float3(0) : color, 0);
    }
    result.finalizeReservoirWeight();
    result.weight = isfinite(result.weight) ? max(0.0, result.weight) : 0.0;
    destination[offset] = result;
    if (sourceProfiles)
        restirDestinationProfilesForPass(parameters, pass)[offset] =
                selectedCanonical ? canonicalProfile : selectedProfile;
    if ((parameters.config.queue.w & 1u) != 0u && result.initRandomSeed != canonical.initRandomSeed)
        parameters.currentAges[offset] = 0u;
}
