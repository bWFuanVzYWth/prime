// Adapted from Sky Tracer b66b163 (GPL-3.0-only); see THIRD_PARTY_LICENSES/SKY-TRACER-NOTICE.md.

#language slang 2026
module "model/atmosphere/source.slang";
import "model/atmosphere/sky.slang";
import "model/atmosphere/parameters.slang";

import "model/atmosphere/spectrum.slang";

[[vk::binding(1, 0)]] public Sampler2D<float4> scatteringSource;
[[vk::binding(2, 0)]] [[vk::image_format("rgba32f")]] public readonly RWTexture2D<float4> incidentMean;
[[vk::binding(3, 0)]] [[vk::image_format("rgba32f")]] public readonly RWTexture2D<float4> groundRadiance;
[[vk::binding(24, 0)]] [[vk::image_format("rgba32f")]] public readonly RWTexture2D<float4> rayleighSource;
public int2 atmTextureSize(Sampler2D<float4> image) {
    uint w, h;
    image.GetDimensions(w, h);
    return int2(w, h);
}
public float atmSolarLayer(uint layer, float e) {
    let offset = ATM_MAPPING.y + 4u * layer;
    let base = atmData[offset];
    let d = max(ATM_PI * 0.5 - e, 0.0);
    var y = base.x * e + base.y - base.z * sqrt(d / (d + 0.0872664626));
    for (var j = 1u; j <= 3u; j++) {
        let c = atmData[offset + j];
        let x = e - c.x;
        y += c.z * x / (abs(x) + c.y);
    }
    return clamp(y, 0.0, 1.0);
}

public float atmPhaseCoord(float nu) {
    let a = 0.61;
    return a * acos(clamp(nu, -1.0, 1.0)) / ATM_PI +
           (1.0 - a) * pow(max((1.0 - nu) * 0.5, 0.0), 1.0 / 3.0);
}

public float4 atmShape(uint hi, uint si, float2 uv) {
    let pixel = float2(float(si * 20u), float(hi * 12u)) +
                uv * float2(float(20u - 1u), float(12u - 1u)) + 0.5;
    return scatteringSource.SampleLevel(pixel / float2(atmTextureSize(scatteringSource)), 0.0);
}

public float4 atmLogMean(uint hi, uint si) { return incidentMean[int2(int(si), int(hi))]; }

public float4 atmHighBasis(float mu, float sunMu, float nu) {
    let y = clamp(mu, -1.0, 1.0);
    let radial = sqrt(max(1.0 - y * y, 0.0));
    // Near a zenith Sun, cancellation in nu-mu*sunMu must not produce a
    // non-unit view vector. Azimuth is immaterial at the exact polar state.
    let z = clamp((nu - y * sunMu) / sqrt(max(1.0 - sunMu * sunMu, 1e-20)), -radial, radial);
    return float4(1.0, y * y, z * z, y * z);
}

public float4 atmHighShape(uint hi, uint si, float4 b) {
    float4 m[5] = {};
    for (var k = 0u; k < 5u; k++) {
        m[k] = rayleighSource[int2(int(si * 5u + k), int(hi + 1u - ATM_OFFSETS1.y))];
    }
    return (m[0] + m[1] * b.y + m[2] * b.z + m[3] * b.w) / max(dot(m[4], b), 1e-20);
}

public float4 atmIndirect(float h, float mu, float sunMu, float nu, AtmMedium c) {

    var lo = 0u;
    var hi = ATM_DIMS.x - 1u;
    while (hi - lo > 1u) {
        let mid = (lo + hi) / 2u;
        if (atmData[mid].x <= h) {
            lo = mid;
        } else {
            hi = mid;
        }
    }
    let h0 = atmData[lo].x;
    let h1 = atmData[hi].x;
    let th = clamp((h - h0) / (h1 - h0), 0.0, 1.0);
    let elevation = asin(clamp(sunMu, -1.0, 1.0));
    let s0 = atmSolarLayer(lo, elevation) * float(ATM_DIMS.y - 1u);
    let s1 = atmSolarLayer(hi, elevation) * float(ATM_DIMS.y - 1u);
    let i0 = min(uint(s0), ATM_DIMS.y - 2u);
    let i1 = min(uint(s1), ATM_DIMS.y - 2u);
    let t0 = s0 - float(i0);
    let t1 = s1 - float(i1);
    let brightness = exp(lerp(lerp(atmLogMean(lo, i0), atmLogMean(lo, i0 + 1u), t0),
                              lerp(atmLogMean(hi, i1), atmLogMean(hi, i1 + 1u), t1), th));
    if (h >= 35.0 && ATM_OFFSETS1.y < ATM_DIMS.x) {
        let b = atmHighBasis(mu, sunMu, nu);
        let v = lerp(lerp(atmHighShape(lo, i0, b), atmHighShape(lo, i0 + 1u, b), t0),
                     lerp(atmHighShape(hi, i1, b), atmHighShape(hi, i1 + 1u, b), t1), th);
        return max(v * brightness * c.scattering[0], float4(0.0));
    }
    let cp = clamp((mu - sunMu * nu) / sqrt(max((1.0 - sunMu * sunMu) * (1.0 - nu * nu), 1e-20)),
                   -1.0, 1.0);
    var angle = 0.0;
    {
        let t = (1.0 - cp) * 0.5;
        angle = t + 0.164285714 * t * (1.0 - t) * (2.0 * t - 1.0);
    }
    let uv = float2(atmPhaseCoord(nu), angle);
    let v = lerp(lerp(atmShape(lo, i0, uv), atmShape(lo, i0 + 1u, uv), t0),
                 lerp(atmShape(hi, i1, uv), atmShape(hi, i1 + 1u, uv), t1), th);
    var sigma = float4(0.0);
    for (var k = 0u; k < 5u; k++) {
        sigma += c.scattering[k];
    }
    return max(v * brightness * sigma, float4(0.0));
}

public float4 atmGroundLight(float sunMu) {
    let x = atmSolarLayer(0u, asin(clamp(sunMu, -1.0, 1.0))) * float(ATM_DIMS.y - 1u);
    let i = min(uint(x), ATM_DIMS.y - 2u);
    return lerp(groundRadiance[int2(int(i), 0)], groundRadiance[int2(int(i + 1u), 0)],
                x - float(i));
}
