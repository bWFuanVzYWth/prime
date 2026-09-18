// Adapted from Sky Tracer b66b163 (GPL-3.0-only); see THIRD_PARTY_LICENSES/SKY-TRACER-NOTICE.md.

#language slang 2026
module "entry/atmosphere/atmosphere_directions.compute.slang";

import "model/atmosphere/parameters.slang";
import "model/atmosphere/sky.slang";
import "model/atmosphere/spectrum.slang";
import "model/atmosphere/geometry.slang";
import "contract/atmosphere/solve.slang";

[[vk::binding(29, 0)]] RWStructuredBuffer<float4> directions;

[numthreads(64, 1, 1)] void main(uint3 id : SV_DispatchThreadID) {
    if (id.x >= 1536u || id.y >= solve.heightCount * ATM_DIMS.y) {
        return;
    }
    let state = atmData[ATM_EXTRA.x + solve.firstHeight * ATM_DIMS.y + id.y];
    let h = state.x;
    let sunMu = state.y;
    let sun = float3(0.0, sunMu, state.z);
    let n = 1536u / 3u;
    float3 v;
    float w;
    let sampleIndex = (id.x < n ? id.x : n + id.x % n);
    let q = atmData[ATM_EXTRA.y + sampleIndex];
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
        let u = pow(q.x, 3.0);
        var mu = lerp(hor, low, u);
        if (id.x >= 2u * n) {
            mu = lerp(hor, high, u);
        }
        let r = sqrt(max(1.0 - mu * mu, 0.0));
        v = float3(r * q.z, mu, r * q.y);
        w = q.w * (high - low) * 3.0 * pow(q.x, 3.0 - 1.0);
    }
    let d = 0.0004 + max(1.0 - dot(v, sun), 0.0);
    let sw = 0.01 / (0.01 + d * d) * lerp(1.0, 0.0, smoothstep(12.0, 35.0, h));
    w *= (id.x < n ? sw : 1.0 - sw);
    // Only one reflection half is traced. The convolution evaluates both signs.
    directions[id.y * 1536u + id.x] = float4(v, w);
}
