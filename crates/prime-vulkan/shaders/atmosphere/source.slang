// Adapted from Sky Tracer b66b163 (GPL-3.0-only); see THIRD_PARTY_NOTICES.md.

#language slang 2026
module "atmosphere/source.slang";
import "atmosphere/resources.slang";
import "atmosphere/sky.slang";
import "atmosphere/parameters.slang";

import "atmosphere/spectrum.slang";

public int2 atmTextureSize(Sampler2D<float4> image) {
    uint w, h;
    image.GetDimensions(w, h);
    return int2(w, h);
}
public float atmSolarLayer(AtmModel model, uint layer, float e) {
    let offset = ATM_MAPPING.y + 4u * layer;
    let base = model.data[offset];
    let d = max(ATM_PI * 0.5 - e, 0.0);
    var y = base.x * e + base.y - base.z * sqrt(d / (d + 0.0872664626));
    for (var j = 1u; j <= 3u; j++) {
        let c = model.data[offset + j];
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

public float4 atmShape(AtmModel model, uint hi, uint si, float2 uv) {
    let pixel = float2(float(si * 20u), float(hi * 12u)) +
                uv * float2(float(20u - 1u), float(12u - 1u)) + 0.5;
    return model.scatteringSource.SampleLevel(
            pixel / float2(atmTextureSize(model.scatteringSource)), 0.0);
}

public float4 atmLogMean(AtmModel model, uint hi, uint si) {
    return model.incidentMean[int2(int(si), int(hi))];
}

public float4 atmHighBasis(float mu, float sunMu, float nu) {
    let y = clamp(mu, -1.0, 1.0);
    let radial = sqrt(max(1.0 - y * y, 0.0));
    // Near a zenith Sun, cancellation in nu-mu*sunMu must not produce a
    // non-unit view vector. Azimuth is immaterial at the exact polar state.
    let z = clamp((nu - y * sunMu) / sqrt(max(1.0 - sunMu * sunMu, 1e-20)), -radial, radial);
    return float4(1.0, y * y, z * z, y * z);
}

public float4 atmHighShape(AtmModel model, uint hi, uint si, float4 b) {
    int2 p = int2(int(si * 5u), int(hi + 1u - ATM_OFFSETS1.y));
    // Consume each fixed coefficient at its load; no dynamically indexed local array.
    float4 value = model.rayleighSource[p];
    value += model.rayleighSource[p + int2(1, 0)] * b.y;
    value += model.rayleighSource[p + int2(2, 0)] * b.z;
    value += model.rayleighSource[p + int2(3, 0)] * b.w;
    return value / max(dot(model.rayleighSource[p + int2(4, 0)], b), 1e-20);
}

public float4 atmIndirect(AtmModel model, float h, float mu, float sunMu, float nu, AtmMedium c) {

    var lo = 0u;
    var hi = ATM_DIMS.x - 1u;
    while (hi - lo > 1u) {
        let mid = (lo + hi) / 2u;
        if (model.data[mid].x <= h) {
            lo = mid;
        } else {
            hi = mid;
        }
    }
    let h0 = model.data[lo].x;
    let h1 = model.data[hi].x;
    let th = clamp((h - h0) / (h1 - h0), 0.0, 1.0);
    let elevation = asin(clamp(sunMu, -1.0, 1.0));
    let s0 = atmSolarLayer(model, lo, elevation) * float(ATM_DIMS.y - 1u);
    let s1 = atmSolarLayer(model, hi, elevation) * float(ATM_DIMS.y - 1u);
    let i0 = min(uint(s0), ATM_DIMS.y - 2u);
    let i1 = min(uint(s1), ATM_DIMS.y - 2u);
    let t0 = s0 - float(i0);
    let t1 = s1 - float(i1);
    let brightness =
            exp(lerp(lerp(atmLogMean(model, lo, i0), atmLogMean(model, lo, i0 + 1u), t0),
                     lerp(atmLogMean(model, hi, i1), atmLogMean(model, hi, i1 + 1u), t1), th));
    if (h >= 35.0 && ATM_OFFSETS1.y < ATM_DIMS.x) {
        let b = atmHighBasis(mu, sunMu, nu);
        let v = lerp(lerp(atmHighShape(model, lo, i0, b), atmHighShape(model, lo, i0 + 1u, b), t0),
                     lerp(atmHighShape(model, hi, i1, b), atmHighShape(model, hi, i1 + 1u, b), t1),
                     th);
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
    let v = lerp(lerp(atmShape(model, lo, i0, uv), atmShape(model, lo, i0 + 1u, uv), t0),
                 lerp(atmShape(model, hi, i1, uv), atmShape(model, hi, i1 + 1u, uv), t1), th);
    var sigma = float4(0.0);
    for (var k = 0u; k < 5u; k++) {
        sigma += c.scattering[k];
    }
    return max(v * brightness * sigma, float4(0.0));
}

public float4 atmGroundLight(AtmModel model, float sunMu) {
    let x = atmSolarLayer(model, 0u, asin(clamp(sunMu, -1.0, 1.0))) * float(ATM_DIMS.y - 1u);
    let i = min(uint(x), ATM_DIMS.y - 2u);
    return lerp(model.groundRadiance[int2(int(i), 0)], model.groundRadiance[int2(int(i + 1u), 0)],
                x - float(i));
}
