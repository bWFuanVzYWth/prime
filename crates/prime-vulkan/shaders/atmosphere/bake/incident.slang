// Adapted from Sky Tracer b66b163 (GPL-3.0-only); see THIRD_PARTY_NOTICES.md.

#language slang 2026
module "atmosphere/bake/incident.slang";
#include "atmosphere/model_bindings.slang"

import "atmosphere/spectrum.slang";
import "atmosphere/parameters.slang";
import "atmosphere/sky.slang";
import "atmosphere/integrate.slang";
struct AtmSolve {
    uint firstHeight;
    uint heightCount;
    uint iteration;
    uint padding;
};
[[vk::push_constant]] ConstantBuffer<AtmSolve> solve;

[[vk::binding(8, 0)]] RWStructuredBuffer<float4> directions;
[[vk::binding(9, 0)]] RWStructuredBuffer<float4> incoming;

[numthreads(64, 1, 1)] void main(uint3 id : SV_DispatchThreadID) {
    if (id.x >= 1536u || id.y >= solve.heightCount * ATM_DIMS.y) {
        return;
    }
    let stateIndex = solve.firstHeight * ATM_DIMS.y + id.y;
    let state = mediumInput[ATM_EXTRA.x + stateIndex];
    let q = directions[id.y * 1536u + id.x];
    if (q.w == 0.0) {
        incoming[id.y * 1536u + id.x] = float4(0.0);
        return;
    }
    let sun = float3(0.0, state.y, state.z);
    let index = id.y * 1536u + id.x;
    let result =
            atmIntegrateAt(atmosphereModel(), q.xyz, sun, state.x, 0.0, 64u, solve.iteration != 1u);
    let light = result.light;
    incoming[index] = light;
}
