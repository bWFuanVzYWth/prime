// Adapted from Sky Tracer b66b163 (GPL-3.0-only); see THIRD_PARTY_NOTICES.md.

#language slang 2026
module "atmosphere/bake/ground.slang";
#include "atmosphere/model_bindings.slang"

import "atmosphere/parameters.slang";
import "atmosphere/sky.slang";
import "atmosphere/transmittance.slang";
import "atmosphere/spectrum.slang";
struct AtmSolve {
    uint firstHeight;
    uint heightCount;
    uint iteration;
    uint padding;
};
[[vk::push_constant]] ConstantBuffer<AtmSolve> solve;

[[vk::binding(8, 0)]] RWStructuredBuffer<float4> directions;
[[vk::binding(9, 0)]] RWStructuredBuffer<float4> incoming;
[[vk::binding(11, 0)]] [[vk::image_format("rgba32f")]] RWTexture2D<float4> groundOutput;

[numthreads(64, 1, 1)] void main(uint3 id : SV_DispatchThreadID) {
    if (id.x >= ATM_DIMS.y || solve.firstHeight != 0u) {
        return;
    }
    let sunMu = mediumInput[ATM_EXTRA.x + id.x].y;
    var irradiance = atmSunT(atmosphereModel(), 0.0, sunMu) * max(sunMu, 0.0) * ATM_SOLAR;
    if (solve.iteration > 0u) {
        for (var j = 0u; j < 1536u; j++) {
            let q = directions[id.x * 1536u + j];
            irradiance += incoming[id.x * 1536u + j] * (q.w * max(q.y, 0.0));
        }
    }
    groundOutput[int2(int(id.x), 0)] = irradiance * (0.18 / ATM_PI);
}
