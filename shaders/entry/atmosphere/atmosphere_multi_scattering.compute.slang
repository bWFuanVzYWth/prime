// Adapted from Sky Tracer b66b163 (GPL-3.0-only); see THIRD_PARTY_LICENSES/SKY-TRACER-NOTICE.md.

#language slang 2026
module "entry/atmosphere/atmosphere_multi_scattering.compute.slang";

import "model/atmosphere/parameters.slang";
import "model/atmosphere/sky.slang";
import "model/atmosphere/spectrum.slang";
import "contract/atmosphere/solve.slang";

[[vk::binding(29, 0)]] RWStructuredBuffer<float4> directions;
[[vk::binding(30, 0)]] RWStructuredBuffer<float4> incoming;
[[vk::binding(31, 0)]] RWStructuredBuffer<float4> moments;
[[vk::binding(25, 0)]] [[vk::image_format("rgba16f")]] RWTexture2D<float4> scatteringOutput;
[[vk::binding(26, 0)]] [[vk::image_format("rgba32f")]] RWTexture2D<float4> meanOutput;

[numthreads(8, 8, 1)] void main(uint3 id : SV_DispatchThreadID) {
    let pixel = id.xy + uint2(0u, solve.firstHeight * 12u);
    if (any(pixel >= int2(3200, 240)) || id.y >= solve.heightCount * 12u) {
        return;
    }
    let hi = pixel.y / 12u;
    let si = id.x / 20u;
    let stateIndex = hi * ATM_DIMS.y + si;
    let localState = (hi - solve.firstHeight) * ATM_DIMS.y + si;
    let state = atmData[ATM_EXTRA.x + stateIndex];
    let h = state.x;
    let sunMu = state.y;
    let nu = atmData[ATM_EXTRA.z + id.x % 20u].x;
    let cone = atmData[ATM_MAPPING.w + id.y % 12u];
    let sun = float3(0.0, sunMu, state.z);
    let tangent = float3(0.0, sun.z, -sun.y);
    let v =
        sun * nu + sqrt(max(1.0 - nu * nu, 0.0)) * (tangent * cone.x + float3(cone.y, 0.0, 0.0));
    let coeff = ATM_OFFSETS0.x + hi * 6u;
    let mi = localState * 7u;
    let ray = moments[mi] + moments[mi + 1u] * v.x * v.x + moments[mi + 2u] * v.y * v.y +
              moments[mi + 3u] * v.z * v.z + 2.0 * moments[mi + 4u] * v.y * v.z;
    let weights = moments[mi + 5u];
    let normalization = weights.x + weights.y * v.x * v.x + weights.z * v.y * v.y +
                        weights.w * v.z * v.z + 2.0 * moments[mi + 6u].x * v.y * v.z;
    var source = atmData[coeff] * ray / max(normalization, 1e-20);
    // Rayleigh is exactly degree two: no directional phase loop above aerosols.
    if (h < 35.0) {
        float4 sums[4] = {};
        float4 norms[4] = {};
        for (var j = 0u; j < 1536u; j++) {
            let q = directions[localState * 1536u + j];
            let l = incoming[localState * 1536u + j];
            let a = q.y * v.y + q.z * v.z;
            let b = q.x * v.x;
            // Geometry is common to all four species and spectral lanes.
            let positive = atmPhaseStencil(clamp(a + b, -1.0, 1.0));
            let negative = atmPhaseStencil(clamp(a - b, -1.0, 1.0));
            for (var species = 0u; species < 4u; species++) {
                let weight =
                    (atmTabulatedPhase(positive, species) + atmTabulatedPhase(negative, species)) *
                    (0.5 * q.w);
                sums[species] += l * weight;
                norms[species] += weight;
            }
        }
        // Match the independently integrated mass of the tabulated phase.
        // This removes energy drift but not angular undersampling.
        for (var species = 0u; species < 4u; species++) {
            source += atmData[coeff + species + 1u] * atmData[ATM_OFFSETS1.x + species] *
                      sums[species] / max(norms[species], float4(1e-20));
        }
    }
    let sigma = atmData[coeff + 5u];
    let mean = exp(meanOutput[int2(si, hi)]);
    scatteringOutput[int2(pixel)] = max(source / max(mean * sigma, float4(1e-30)), float4(0.0));
}
