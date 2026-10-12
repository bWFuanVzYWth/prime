#language slang 2026
module "test_atmosphere";
import "atmosphere/view.slang";
import "atmosphere/spectrum.slang";
import "atmosphere/integrate.slang";
import "atmosphere/constants.slang";
import "atmosphere/epipolar.slang";
import "atmosphere/sky_view.slang";
import "realtime/primary_environment.slang";
import "realtime/post.slang";
import "realtime/post_parameters.slang";
import "trace/closest.slang";
import "bsdf/full/energy.slang";
import "restir/scene.slang";
#include "atmosphere/model_bindings.slang"
[[vk::binding(6, 0)]] StructuredBuffer<float4> input;
[[vk::binding(7, 0)]] RWStructuredBuffer<float4> output;
[[vk::binding(8, 0)]] [[vk::image_format("rgba32f")]] readonly RWTexture2D<float4> legacySky;
[[vk::binding(9, 0)]] StructuredBuffer<uint4> textures;
[[vk::binding(10, 0)]] StructuredBuffer<uint> texels;
[[vk::binding(11, 0)]] StructuredBuffer<InstanceMaterial> instanceMaterials;
[[vk::binding(12, 0)]] StructuredBuffer<TrianglePage> staticTrianglePages;
[[vk::binding(0, 1)]] StructuredBuffer<AtmFrame> frames;
[[vk::binding(1, 1)]] Sampler2D<float4> sky;
[[vk::binding(2, 1)]] Sampler2D<float4> transmittance;
[[vk::binding(3, 1)]] Sampler3D<float4> aerialS;
[[vk::binding(4, 1)]] Sampler3D<float4> aerialT;
[[vk::binding(5, 1)]] Sampler2D<float4> stars;
struct Params {
    uint mode;
    uint count;
    uint2 padding;
    float4 *prefixAddress;
    float4 *tailAddress;
};
[[vk::push_constant]] ConstantBuffer<Params> params;
[numthreads(64, 1, 1)] void main(uint3 id : SV_DispatchThreadID) {
    if (id.x >= params.count)
        return;
    AtmEnvironment environment = {frames, sky, transmittance, aerialS, aerialT, stars, float4(0)};
    if (params.mode == 10) {
        output[id.x * 2] = float4(atmLinearRec2020FromSpectral(input[id.x]), 1);
        output[id.x * 2 + 1] = float4(atmRadiance(input[id.x]), 1);
        return;
    }
    if (params.mode == 11) {
        float3 direction = atmSampleSun(environment, input[id.x].xy);
        float3 t = atmDistantTransmittance(environment, direction);
        float3 integral = atmSunRadiance(environment, direction) / t *
                          dot(direction, frames[0].sun.xyz) / atmSunPdf();
        output[id.x * 2] = float4(integral, atmSunContains(environment, direction) ? 1.0 : 0.0);
        output[id.x * 2 + 1] = float4(t, atmSunPdf());
        return;
    }
    if (params.mode == 12) {
        float4 controls = input[id.x];
        environment.starControls = float4(0.2, 0.7, controls.z, 0);
        AtmLighting lighting = atmLighting(environment);
        PrimaryEnvironment primary = {lighting, controls.x, true};
        float3 skyDirection = normalize(float3(-0.4, 0.8, -0.45));
        float3 sunDirection = frames[0].sun.xyz;
        TraceScene resources = {textures, texels, instanceMaterials, staticTrianglePages,
                                float2(0.5, 1)};
        // Environment queries do not consume geometry or BSDF energy. Every handle is nevertheless
        // bound to a live owned resource; aerialT is an unused valid 3D energy handle here.
        PrimeFullTransmissionGgxEnergy energy = {aerialT};
        RestirScene scene = {resources, environment, energy};
        scene.frame.right.w = controls.x;
        scene.frame.up.w = controls.y; // Poison the former independent sky-intensity lane.
        float3 s, t;
        atmAerial(environment, float2(0.5), controls.w, s, t);
        PostParameters post = {};
        post.extra = float4(0, 0, controls.x, controls.y);
        post.sampling = uint4(1, 1, 0, 0);
        post.sample.w = asfloat(1u); // Deterministic pixel center, without Sobol jitter.
        post.prefixAddress = params.prefixAddress;
        post.tailAddress = params.tailAddress;
        uint base = id.x * 12;
        output[base] = float4(primary.escape(skyDirection, true), 1);
        output[base + 1] = float4(primary.escape(sunDirection, true), 1);
        output[base + 2] =
                float4(restirEnvironmentRadiance(scene, RESTIR_LIGHT_SKY, skyDirection), 1);
        output[base + 3] =
                float4(restirEnvironmentRadiance(scene, RESTIR_LIGHT_SUN, sunDirection), 1);
        AtmAerial aerial = {frames, aerialS, aerialT};
        output[base + 4] = float4(primeComposeRealtime(aerial, post, uint2(0)), 1);
        output[base + 5] = float4(t, 1);
        output[base + 6] = float4(atmStars(lighting, skyDirection), 1);
        output[base + 7] = float4(primary.escape(skyDirection, false), 1);
        output[base + 8] = float4(atmSkyWithoutStars(lighting, sunDirection), 1);
        output[base + 9] = float4(atmDistantTransmittance(lighting, skyDirection), 1);
        output[base + 10] = float4(atmSolarIrradiance(), 1);
        output[base + 11] = float4(s, 1);
        return;
    }
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
