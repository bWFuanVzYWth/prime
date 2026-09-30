#language slang 2026
module "atmosphere/prepare.slang";
import "atmosphere/constants.slang";
import "atmosphere/geometry.slang";
import "atmosphere/sky_view.slang";
import "atmosphere/view.slang";
import "atmosphere/epipolar.slang";
import "atmosphere/shadow_math.slang";
struct Input {
    float4 sun;
    float4 camera;
    float4 forward;
    float4 right;
    float4 up;
    float4 padding;
};
[[vk::push_constant]] ConstantBuffer<Input> input;
[[vk::binding(0, 1)]] RWStructuredBuffer<AtmFrame> frames;
[numthreads(1, 1, 1)] void main() {
    AtmFrame f;
    f.sun = input.sun;
    float3 helper = abs(f.sun.y) < 0.999 ? float3(0, 1, 0) : float3(1, 0, 0);
    f.tangent = float4(normalize(cross(helper, f.sun.xyz)), 0);
    f.bitangent = float4(cross(f.sun.xyz, f.tangent.xyz), 0);
    float h = input.camera.w - ATM_BOTTOM_RADIUS_KM;
    float elevation = asin(clamp(f.sun.y, -1.0, 1.0));
    f.geometry = float4(h, atmHorizon(h), elevation, length(f.sun.xz));
    f.camera = input.camera;
    f.forward = input.forward;
    f.right = input.right;
    f.up = input.up;
    float z = dot(f.sun.xyz, f.forward.xyz);
    float2 clip = float2(dot(f.sun.xyz, f.right.xyz) / (f.forward.w * f.right.w),
                         dot(f.sun.xyz, f.up.xyz) / f.forward.w);
    float2 epipole = abs(z) > 1e-6 ? clip / z
                                   : (dot(clip, clip) > 1e-6 ? normalize(clip) * ATM_EPIPOLAR_LIMIT
                                                             : float2(0));
    f.epipole = float4(clamp(epipole, float2(-ATM_EPIPOLAR_LIMIT), float2(ATM_EPIPOLAR_LIMIT)),
                       input.padding.xy);
    f.up.w = input.padding.y;
    AtmEpipolarRange range = atmEpipolarVisibleRange(f.epipole.xy);
    f.range = float4(range.start, range.span, range.axis);
    f.rangeExtra = float4(range.extent, range.parallel ? 1.0 : 0.0, 0, 0);
    float3 u, v;
    sunShadowBasis(normalize(f.sun.xyz), u, v);
    f.shadowU = float4(u, input.padding.z);
    f.shadowV = float4(v, input.padding.w);
    for (uint i = 0; i < 3; i++) {
        AtmSkyChart c = atmSkyChart(h, elevation, i);
        f.charts[i].bounds = float4(c.bounds, c.weights.x / max(c.bounds.y - c.bounds.x, 1e-10), 0);
        f.charts[i].centers = float4(c.centers, 0);
        f.charts[i].weights = float4(0);
        f.charts[i].widths = float4(c.widths, 0);
        for (uint j = 0; j < 3; j++) {
            float center = c.centers[j], width = c.widths[j];
            if (center < c.bounds.x || center > c.bounds.y) {
                f.charts[i].weights[j] = c.weights[j + 1] / max(c.bounds.y - c.bounds.x, 1e-10) *
                                         (abs(c.bounds.y - center) + width);
            } else {
                float a = (c.bounds.x - center) / (abs(c.bounds.x - center) + width);
                float b = (c.bounds.y - center) / (abs(c.bounds.y - center) + width);
                float scale = c.weights[j + 1] / max(b - a, 1e-10);
                f.charts[i].weights[j] = scale;
                f.charts[i].weights.w -= scale * a;
            }
        }
    }
    frames[0] = f;
}
