// Adapted from Sky Tracer b66b163 (GPL-3.0-only); see THIRD_PARTY_NOTICES.md.

#language slang 2026
module "atmosphere/bake/directions.slang";
#include "atmosphere/model_bindings.slang"

import "atmosphere/parameters.slang";
import "atmosphere/sky.slang";
import "atmosphere/spectrum.slang";
import "atmosphere/geometry.slang";
struct AtmSolve {
    uint firstHeight;
    uint heightCount;
    uint iteration;
    uint padding;
};
[[vk::push_constant]] ConstantBuffer<AtmSolve> solve;

[[vk::binding(8, 0)]] RWStructuredBuffer<float4> directions;

[numthreads(64, 1, 1)] void main(uint3 id : SV_DispatchThreadID) {
    if (id.x >= 1536u || id.y >= solve.heightCount * ATM_DIMS.y) {
        return;
    }
    let state = mediumInput[ATM_EXTRA.x + solve.firstHeight * ATM_DIMS.y + id.y];
    let h = state.x;
    let sunMu = state.y;
    let sun = float3(0.0, sunMu, state.z);
    let n = 1536u / 3u;
    float3 v;
    float w;
    let sampleIndex = (id.x < n ? id.x : n + id.x % n);
    let q = mediumInput[ATM_EXTRA.y + sampleIndex];
    if (q.w == 0.0) {
        directions[id.y * 1536u + id.x] = float4(0.0);
        return;
    }
    if (id.x < n) {
        let tangent = float3(0.0, sun.z, -sun.y);
        v = q.x * tangent + q.y * float3(1.0, 0.0, 0.0) + q.z * sun;
        w = q.w;
    } else {
        let hor = atmHorizon(h);
        var low = -1.0;
        var high = hor;
        if (id.x >= 2u * n) {
            low = hor;
            high = 1.0;
        }
        let q2 = q.x * q.x;
        let u = q2 * q.x;
        var mu = lerp(hor, low, u);
        if (id.x >= 2u * n) {
            mu = lerp(hor, high, u);
        }
        let r = sqrt(max(1.0 - mu * mu, 0.0));
        v = float3(r * q.z, mu, r * q.y);
        w = q.w * (high - low) * 3.0 * q2;
    }
    let d = 0.0004 + max(1.0 - dot(v, sun), 0.0);
    let sw = 0.01 / (0.01 + d * d) * lerp(1.0, 0.0, smoothstep(12.0, 35.0, h));
    w *= (id.x < n ? sw : 1.0 - sw);
    // Only one reflection half is traced. The convolution evaluates both signs.
    directions[id.y * 1536u + id.x] = float4(v, w);
}
