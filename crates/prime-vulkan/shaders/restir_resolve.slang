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
module restir_resolve;
#include "restir/bindings.slang"
import "display/prime_drt.slang";
import "math/color.slang";
[[vk::binding(4, 0)]] [[vk::image_format("rgba8")]] RWTexture2D<float4> outputImage;
[[vk::binding(24, 0)]] [[vk::image_format("rgba32f")]] RWTexture2D<float4> linearOutput;
// Offline specialization is selected at pipeline creation; no history-address test on the GPU.
[[vk::constant_id(4)]] const uint offline = 0u;
[numthreads(8, 8, 1)] void main(uint3 id : SV_DispatchThreadID) {
    uint2 extent = parameters.frame.dimensions.xy;
    if (any(id.xy >= extent))
        return;
    uint offset = restirOffset(id.xy, extent);
    RestirScene resources = restirScene();
    PathReservoir reservoir = parameters.history[offset];
    float3 radiance = parameters.residual[offset].rgb + reservoir.integrand * reservoir.weight;
    RestirPrimary primary = parameters.primary[offset];
    float3 wo = -restirPrimaryDirection(resources.frame, id.xy);
    SurfaceHit hit = restirLoadHit(resources, primary.hit, wo, primary.coverageSeed, 0.0);
    float depth = hit.found ? dot(hit.surface.position - resources.frame.position.xyz,
                                  resources.frame.forward.xyz)
                            : 0.0;
    if (hit.found && (hit.incident.x == 1.0 || hit.thin)) {
        float3 scattering, transmittance;
        atmAerial(resources.environment, (float2(id.xy) + 0.5) / float2(extent),
                  length(hit.surface.position - resources.frame.position.xyz), scattering,
                  transmittance);
        radiance = transmittance * radiance + scattering * resources.frame.right.w;
    }
    radiance = float3(isfinite(radiance.x) ? max(0.0, radiance.x) : 0.0,
                      isfinite(radiance.y) ? max(0.0, radiance.y) : 0.0,
                      isfinite(radiance.z) ? max(0.0, radiance.z) : 0.0);
    uint pixel = id.y * extent.x + id.x;
    if (offline != 0u) {
        uint count = parameters.frame.dimensions.z + pass.sampleOffset;
        float3 mean = count == 0u ? float3(0) : parameters.accumulation[pixel].rgb;
        radiance = mean + (radiance - mean) / float(count + 1u);
        parameters.accumulation[pixel] = float4(radiance, 1);
    }
    uint view = offline != 0u ? 0u : asuint(parameters.frame.displayExtra.w) & 3u;
    if (parameters.control.w != 0u && view == 0u) {
        if (offline == 0u)
            linearOutput[id.xy] = float4(radiance, 1);
        return;
    }
    float3 encoded;
    if (view == 2u)
        encoded = float3(saturate(depth / parameters.frame.displayExtra.y));
    else if (view == 3u)
        encoded = hit.found ? restirMaterial(hit, wo).normal * 0.5 + 0.5 : float3(0);
    else if (view == 1u)
        encoded = encodeSrgb(workingToLinear709(radiance) * parameters.frame.display.x);
    else {
        PrimeDRTParameters display = {parameters.frame.display.x, parameters.frame.display.y,
                                      parameters.frame.display.z, parameters.frame.display.w,
                                      parameters.frame.displayExtra.x};
        encoded = primeDRT(radiance, display);
    }
    uint2 target = uint2(id.x, parameters.frame.target.x != 0u ? extent.y - 1u - id.y : id.y);
    outputImage[target] = float4(float3(uint3(saturate(encoded) * 255.0 + 0.5)) / 255.0, 1);
}
