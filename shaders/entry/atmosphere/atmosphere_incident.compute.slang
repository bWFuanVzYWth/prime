// Adapted from Sky Tracer b66b163 (GPL-3.0-only); see THIRD_PARTY_LICENSES/SKY-TRACER-NOTICE.md.

#language slang 2026
module "entry/atmosphere/atmosphere_incident.compute.slang";

import "model/atmosphere/spectrum.slang";
import "model/atmosphere/parameters.slang";
import "model/atmosphere/sky.slang";
import "model/atmosphere/integrate.slang";
import "contract/atmosphere/solve.slang";

[[vk::binding(29, 0)]] RWStructuredBuffer<float4> directions;
[[vk::binding(30, 0)]] RWStructuredBuffer<float4> incoming;

[numthreads(64, 1, 1)] void main(uint3 id : SV_DispatchThreadID) {
    if (id.x >= 1536u || id.y >= solve.heightCount * ATM_DIMS.y) {
        return;
    }
    let stateIndex = solve.firstHeight * ATM_DIMS.y + id.y;
    let state = atmData[ATM_EXTRA.x + stateIndex];
    let q = directions[id.y * 1536u + id.x];
    if (q.w == 0.0) {
        incoming[id.y * 1536u + id.x] = float4(0.0);
        return;
    }
    let sun = float3(0.0, state.y, state.z);
    let index = id.y * 1536u + id.x;
    let result = atmIntegrateAt(q.xyz, sun, state.x, 0.0, 64u, solve.iteration != 1u);
    let light = result.light;
    incoming[index] = light;
}
