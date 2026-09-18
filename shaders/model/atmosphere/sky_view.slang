// Adapted from Sky Tracer b66b163 (GPL-3.0-only); see THIRD_PARTY_LICENSES/SKY-TRACER-NOTICE.md.

#language slang 2026
module "model/atmosphere/sky_view.slang";

import "model/atmosphere/sky.slang";
import "model/atmosphere/geometry.slang";

public struct AtmSkyChart {
    public float2 bounds;
    public float3 centers;
    public float4 weights;
    public float3 widths;
};
public AtmSkyChart atmSkyChart(float h, float sunElevation, uint chart) {
    float hor = asin(atmHorizon(h));
    bool space = h >= ATM_THICKNESS_KM;
    float outer =
        space ? -acos(clamp(ATM_TOP_RADIUS_KM / (ATM_BOTTOM_RADIUS_KM + h), 0.0, 1.0)) : hor;
    AtmSkyChart c;
    c.bounds = chart == 2u   ? float2(-ATM_PI * 0.5, hor)
               : chart == 1u ? float2(0.0, ATM_PI * 0.5)
                             : float2(hor, space ? outer : 0.0);
    c.centers = float3(hor, sunElevation, outer);
    if (chart == 2u) {
        c.weights = float4(0.20124773681163788, 0.301099956035614, 0.051217593252658844,
                           0.44643473625183105);
        c.widths = float3(0.08166702091693878, 0.015121965669095516, 0.05319792777299881);
    } else if (space) {
        c.weights =
            float4(0.10427150130271912, 0.5162510275840759, 0.302908718585968, 0.07656880468130112);
        c.widths = float3(0.03553646430373192, 0.0036403569392859936, 0.0031041009351611137);
    } else if (chart == 1u) {
        c.weights = float4(0.23954732716083527, 0.02231575734913349, 0.5578426122665405,
                           0.18029437959194183);
        c.widths = float3(0.0869770422577858, 0.0739380493760109, 0.14374907314777374);
    } else {
        c.weights = float4(0.28717395663261414, 0.044344790279865265, 0.2831573188304901,
                           0.38532397150993347);
        c.widths = float3(0.0033508469350636005, 0.0030857601668685675, 0.029294535517692566);
    }
    if (chart == 2u && !space) {
        float t = 1.0 - smoothstep(1.0, 4.0, h);
        c.weights = lerp(c.weights,
                         float4(0.07399826496839523, 0.01126958429813385, 0.49432915449142456,
                                0.42040303349494934),
                         t);
        c.widths = lerp(c.widths,
                        float3(0.010801837779581547, 0.11641104519367218, 0.01404983177781105), t);
    }
    return c;
}

public float atmSkyElevationCoordinate(float e, AtmSkyChart c) {
    float lo = c.bounds.x, hi = c.bounds.y;
    float x = clamp(e, lo, hi);
    float y = c.weights.x * (x - lo) / max(hi - lo, 1e-10);
    for (uint j = 0; j < 3; j++) {
        float center = c.centers[j], width = c.widths[j];
        if (center < lo || center > hi) {
            // Stable softsign difference when the sun lies outside a thin chart.
            y += c.weights[j + 1] * (x - lo) / max(hi - lo, 1e-10) * (abs(hi - center) + width) /
                 (abs(x - center) + width);
        } else {
            float a = (lo - center) / (abs(lo - center) + width);
            float b = (hi - center) / (abs(hi - center) + width);
            float d = (x - center) / (abs(x - center) + width);
            y += c.weights[j + 1] * (d - a) / max(b - a, 1e-10);
        }
    }
    return clamp(y, 0.0, 1.0);
}
public float3 atmSampleSky(readonly RWTexture2D<float4> image, float3 ray, float3 sun, float h) {
    if (h >= ATM_THICKNESS_KM) {
        float radius = ATM_BOTTOM_RADIUS_KM + h;
        float b = radius * ray.y;
        float c = (h - ATM_THICKNESS_KM) * (radius + ATM_TOP_RADIUS_KM);
        if (b >= 0.0 || b * b <= c)
            return float3(0.0);
    }
    float horizontal = length(ray.xz) * length(sun.xz);
    float cp = horizontal > 1e-10 ? clamp(dot(ray.xz, sun.xz) / horizontal, -1.0, 1.0) : 1.0;
    float x = sqrt(sqrt(max((1.0 - cp) * 0.5, 0.0))) * 255.0;
    bool hit = atmGround(h, ray.y);
    uint lower = h >= ATM_THICKNESS_KM ? 192u : 96u;
    uint3 layout = hit            ? uint3(2, 192, 64)
                   : ray.y >= 0.0 ? uint3(1, lower, 192u - lower)
                                  : uint3(0, 0, lower);
    float sunElevation = asin(clamp(sun.y, -1.0, 1.0));
    float y = atmSkyElevationCoordinate(asin(clamp(ray.y, -1.0, 1.0)),
                                        atmSkyChart(h, sunElevation, layout.x));
    float row = float(layout.y) + y * float(layout.z - 1);
    uint ix = min(uint(x), 254u), iy = clamp(uint(row), layout.y, layout.y + layout.z - 2u);
    float3 a = lerp(image[int2(ix, iy)].xyz, image[int2(ix + 1u, iy)].xyz, x - float(ix));
    float3 b = lerp(image[int2(ix, iy + 1u)].xyz, image[int2(ix + 1u, iy + 1u)].xyz, x - float(ix));
    if (h >= ATM_THICKNESS_KM && !hit && iy == lower - 2u) {
        float previous = image[int2(0, lower - 2u)].w;
        float previous2 = image[int2(0, lower - 3u)].w;
        float radius = ATM_BOTTOM_RADIUS_KM + h;
        float c = (h - ATM_THICKNESS_KM) * (radius + ATM_TOP_RADIUS_KM);
        float d = max(radius * radius * ray.y * ray.y - c, 0.0);
        float d0 = max(radius * radius * previous * previous - c, 1e-20);
        float d1 = max(radius * radius * previous2 * previous2 - c, d0 + 1e-10);
        float3 earlier =
            lerp(image[int2(ix, iy - 1u)].xyz, image[int2(ix + 1u, iy - 1u)].xyz, x - float(ix));
        float3 change = exp(earlier - a) * sqrt(d0 / d1) - 1.0;
        return max(exp(a) * sqrt(clamp(d / d0, 0.0, 1.0)) * (1.0 + change * ((d - d0) / (d1 - d0))),
                   float3(0.0));
    }
    return max(exp(lerp(a, b, row - float(iy))) - float3(1e-30), float3(0.0));
}
