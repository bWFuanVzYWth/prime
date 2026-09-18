// Adapted from Sky Tracer b66b163 (GPL-3.0-only); see THIRD_PARTY_LICENSES/SKY-TRACER-NOTICE.md.

#language slang 2026
module "entry/atmosphere/atmosphere_ground.compute.slang";

import "model/atmosphere/parameters.slang";
import "model/atmosphere/sky.slang";
import "model/atmosphere/transmittance.slang";
import "model/atmosphere/spectrum.slang";
import "contract/atmosphere/solve.slang";

[[vk::binding(29, 0)]] RWStructuredBuffer<float4> directions;
[[vk::binding(30, 0)]] RWStructuredBuffer<float4> incoming;
[[vk::binding(27, 0)]] [[vk::image_format("rgba32f")]] RWTexture2D<float4> groundOutput;

[numthreads(64, 1, 1)] void main(uint3 id : SV_DispatchThreadID) {
    if (id.x >= ATM_DIMS.y || solve.firstHeight != 0u) {
        return;
    }
    let sunMu = atmData[ATM_EXTRA.x + id.x].y;
    var irradiance = atmSunT(0.0, sunMu) * max(sunMu, 0.0) * ATM_SOLAR;
    if (solve.iteration > 0u) {
        for (var j = 0u; j < 1536u; j++) {
            let q = directions[id.x * 1536u + j];
            irradiance += incoming[id.x * 1536u + j] * (q.w * max(q.y, 0.0));
        }
    }
    groundOutput[int2(int(id.x), 0)] = irradiance * (0.18 / ATM_PI);
}
