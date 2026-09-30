#language slang 2026
module "test_atmosphere";
import "atmosphere/view.slang";
import "atmosphere/spectrum.slang";
import "atmosphere/integrate.slang";
import "atmosphere/constants.slang";
import "atmosphere/epipolar.slang";
import "atmosphere/sky_view.slang";
#include "atmosphere/model_bindings.slang"
[[vk::binding(6, 0)]] StructuredBuffer<float4> input;
[[vk::binding(7, 0)]] RWStructuredBuffer<float4> output;
[[vk::binding(8, 0)]] [[vk::image_format("rgba32f")]] readonly RWTexture2D<float4> legacySky;
[[vk::binding(0, 1)]] StructuredBuffer<AtmFrame> frames;
[[vk::binding(1, 1)]] Sampler2D<float4> sky;
[[vk::binding(2, 1)]] Sampler2D<float4> transmittance;
[[vk::binding(3, 1)]] Sampler3D<float4> aerialS;
[[vk::binding(4, 1)]] Sampler3D<float4> aerialT;
struct Params {
    uint mode;
    uint count;
    uint2 padding;
};
[[vk::push_constant]] ConstantBuffer<Params> params;
[numthreads(64, 1, 1)] void main(uint3 id : SV_DispatchThreadID) {
    if (id.x >= params.count)
        return;
    AtmEnvironment environment = {frames, sky, transmittance, aerialS, aerialT};
    if (params.mode == 8 || params.mode == 9) {
        float2 uv = (float2(id.x % 1920, id.x / 1920) + 0.5) / float2(1920, 1080);
        float3 ray = normalize(float3(uv.x * 3.0 - 1.5, uv.y * 2.0 - 1.0, -1.0));
        float3 color = params.mode == 8 ? atmSky(environment, ray)
                                        : atmSampleSky(legacySky, ray, frames[0].sun.xyz,
                                                       frames[0].geometry.x);
        output[id.x] = float4(color, 1);
        return;
    }
    if (params.mode == 0) {
        output[id.x] = float4(atmSky(environment, input[id.x * 2].xyz), 1);
    } else if (params.mode == 1) {
        float4 a = input[id.x * 2], b = input[id.x * 2 + 1];
        AtmTransport result = atmIntegrateAt(atmosphereModel(), a.xyz, b.xyz, a.w, b.w, 96, true);
        output[id.x * 2] = result.light;
        output[id.x * 2 + 1] = result.transmittance;
    } else if (params.mode == 2 || params.mode == 6) {
        float3 direction = atmSampleSun(environment, input[id.x].xy);
        output[id.x * 2] = float4(direction, atmSunPdf());
        output[id.x * 2 + 1] =
                float4(params.mode == 6 ? atmSunRadiance(environment, direction)
                                        : atmDistantTransmittance(environment, direction),
                       atmSunContains(environment, direction) ? 1.0 : 0.0);
    } else if (params.mode == 3) {
        float4 a = input[id.x];
        float3 s, t;
        atmAerial(environment, a.xy, a.z, s, t);
        output[id.x * 2] = float4(s, 1);
        output[id.x * 2 + 1] = float4(t, 1);
    } else if (params.mode == 4 || params.mode == 7) {
        uint3 cell = uint3(input[id.x].xyz);
        AtmFrame f = frames[0];
        float3 direction = atmAerialDirection(f, int2(cell.xy));
        if (params.mode == 7) {
            float2 ndc = float2(2.0 * (float(cell.x) + 0.5) / 128.0 - 1.0,
                                1.0 - 2.0 * (float(cell.y) + 0.5) / 64.0);
            direction = normalize(f.forward.xyz + f.right.xyz * (ndc.x * f.right.w * f.forward.w) +
                                  f.up.xyz * (ndc.y * f.forward.w));
        }
        float z = (float(cell.z) + 0.5) / 128.0;
        AtmTransport reference =
                atmIntegrateAt(atmosphereModel(), direction, f.sun.xyz, f.geometry.x,
                               ATM_AERIAL_MAX_DISTANCE_KM * z * z, 512, true);
        output[id.x * 2] =
                params.mode == 4 ? aerialS.Load(int4(cell, 0)) : aerialT.Load(int4(cell, 0));
        output[id.x * 2 + 1] =
                float4(params.mode == 4 ? atmRadiance(reference.light)
                                        : atmRec2020Transmittance(reference.transmittance),
                       1);
    } else if (params.mode == 5) {
        float4 a = input[id.x];
        AtmFrame f = frames[0];
        AtmEpipolarRange range = atmEpipolarVisibleRange(f.epipole.xy);
        float2 ndc = float2(2 * a.x - 1, 1 - 2 * a.y);
        float slice = atmEpipolarRangeCoordinate(range, f.epipole.xy, ndc) * 256 - 0.5;
        int base = int(floor(slice));
        float3 values[2];
        for (int j = 0; j < 2; j++) {
            int s = range.span >= 6.283185307 - 1e-6 ? ((base + j) % 256 + 256) % 256
                                                     : clamp(base + j, 0, 255);
            AtmEpipolarSegment segment = atmEpipolarSliceSegment(f.epipole.xy, range, s, 256);
            float2 pixel = clamp(float2(atmEpipolarRadialCoordinate(ndc, segment), a.z) * 128 - 0.5,
                                 float2(0), float2(127));
            int2 lo = int2(floor(pixel)), hi = min(lo + 1, int2(127));
            float2 t = frac(pixel);
            values[j] = lerp(lerp(aerialS.Load(int4(lo.x, s, lo.y, 0)).rgb,
                                  aerialS.Load(int4(hi.x, s, lo.y, 0)).rgb, t.x),
                             lerp(aerialS.Load(int4(lo.x, s, hi.y, 0)).rgb,
                                  aerialS.Load(int4(hi.x, s, hi.y, 0)).rgb, t.x),
                             t.y);
        }
        output[id.x * 2] =
                atmSampleEpipolarWithRange(aerialS, a.xy, a.z, f.epipole.xy, atmPreparedRange(f));
        output[id.x * 2 + 1] = float4(lerp(values[0], values[1], frac(slice)), 1);
    }
}
