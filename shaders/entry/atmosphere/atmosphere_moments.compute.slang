// Adapted from Sky Tracer b66b163 (GPL-3.0-only); see THIRD_PARTY_LICENSES/SKY-TRACER-NOTICE.md.

#language slang 2026
module "entry/atmosphere/atmosphere_moments.compute.slang";

import "model/atmosphere/parameters.slang";
import "model/atmosphere/sky.slang";
import "contract/atmosphere/solve.slang";

[[vk::binding(29, 0)]] RWStructuredBuffer<float4> directions;
[[vk::binding(30, 0)]] RWStructuredBuffer<float4> incoming;
[[vk::binding(31, 0)]] RWStructuredBuffer<float4> moments;
[[vk::binding(26, 0)]] [[vk::image_format("rgba32f")]] RWTexture2D<float4> meanOutput;
[[vk::binding(28, 0)]] [[vk::image_format("rgba32f")]] RWTexture2D<float4> rayleighOutput;

[numthreads(64, 1, 1)] void main(uint3 id : SV_DispatchThreadID) {
    if (id.x >= solve.heightCount * ATM_DIMS.y) {
        return;
    }
    let stateIndex = solve.firstHeight * ATM_DIMS.y + id.x;
    float4 m[7] = {};
    for (var j = 0u; j < 1536u; j++) {
        let q = directions[id.x * 1536u + j];
        let l = incoming[id.x * 1536u + j] * q.w;
        m[0] += l;
        m[1] += l * q.x * q.x;
        m[2] += l * q.y * q.y;
        m[3] += l * q.z * q.z;
        m[4] += l * q.y * q.z;
        m[5] += q.w * float4(1.0, q.x * q.x, q.y * q.y, q.z * q.z);
        m[6].x += q.w * q.y * q.z;
    }
    for (var k = 0u; k < 7u; k++) {
        moments[id.x * 7u + k] = m[k];
    }
    let hi = stateIndex / ATM_DIMS.y;
    if (hi + 1u >= ATM_OFFSETS1.y) {
        let scale = max(m[0] / (4.0 * ATM_PI), float4(1e-30));
        // x²=1-y²-z². Retain the exact Rayleigh quadratic in five texels.
        float4 packed[5] = {
            (m[0] + m[1]) / scale, (m[2] - m[1]) / scale, (m[3] - m[1]) / scale, 2.0 * m[4] / scale,
            float4(m[5].x + m[5].y, m[5].z - m[5].y, m[5].w - m[5].y, 2.0 * m[6].x)};
        for (var k = 0u; k < 5u; k++) {
            rayleighOutput[int2(int((id.x % ATM_DIMS.y) * 5u + k), int(hi + 1u - ATM_OFFSETS1.y))] =
                packed[k];
        }
    }
    meanOutput[int2(int(stateIndex % ATM_DIMS.y), int(hi))] =
        log(max(m[0] / (4.0 * ATM_PI), float4(1e-30)));
}
