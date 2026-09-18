// Adapted from Sky Tracer b66b163 (GPL-3.0-only); see THIRD_PARTY_LICENSES/SKY-TRACER-NOTICE.md.

#language slang 2026
module "entry/atmosphere/atmosphere_sky.compute.slang";

import "prime_abi_types.slang";
import "model/atmosphere/sky.slang";
import "model/atmosphere/geometry.slang";
import "model/atmosphere/spectrum.slang";
import "model/atmosphere/transmittance.slang";
import "model/atmosphere/integrate.slang";
import "model/atmosphere/sky_rows.slang";
import "math/atmosphere_direction.slang";

[[vk::binding(4, 0)]] [[vk::image_format("rgba32f")]] RWTexture2D<float4> skyView;
[[vk::binding(23, 0)]] [[vk::image_format("rgba16f")]] RWTexture2D<float4> cameraTransmittance;
struct AtmospherePushConstants {
    float4x4 inverseViewProjection;
    float eyeRadiusKm;
    float maximumAerialDistanceKm;
    float2 padding;
    float4 sunDirectionIntensity;
};
[[vk::push_constant]] ConstantBuffer<AtmospherePushConstants> atmospherePush;
groupshared float rowCosine;

// A row owns one inverse CDF, shared by its 256 azimuth samples.
[numthreads(256, 1, 1)] void main(uint3 id : SV_DispatchThreadID) {
    float h = atmospherePush.eyeRadiusKm - ATM_BOTTOM_RADIUS_KM;
    float sunElevation = asin(clamp(atmospherePush.sunDirectionIntensity.y, -1.0, 1.0));
    if (id.x == 0u) {
        float mu = atmSkyRowCosine(h, sunElevation, id.y);
        rowCosine = id.y >= 192u ? min(mu, atmHorizon(h) - 1e-7) : max(mu, atmHorizon(h) + 1e-7);
    }
    GroupMemoryBarrierWithGroupSync();
    uint cameraIndex = id.y * 256u + id.x;
    if (cameraIndex < PRIME_ATMOSPHERE_DIRECTION_TRANSMITTANCE_WIDTH) {
        float mu = primeAtmosphereDirectionCosine(
            float(cameraIndex) / float(PRIME_ATMOSPHERE_DIRECTION_TRANSMITTANCE_WIDTH - 1u));
        cameraTransmittance[int2(cameraIndex, 0)] =
            float4(atmRec2020Transmittance(atmOpticalTransmittance(h, mu)), 1.0);
    }
    float u = float(id.x) / 255.0;
    float a = u * u * u * u;
    float r = sqrt(max(1.0 - rowCosine * rowCosine, 0.0));
    float3 ray = float3(r * 2.0 * sqrt(max(a * (1.0 - a), 0.0)), rowCosine, r * (1.0 - 2.0 * a));
    float3 sun = float3(0.0, sin(sunElevation), cos(sunElevation));
    AtmTransport result = atmIntegrateAt(ray, sun, h, 0.0, 96u, true);
    skyView[int2(id.xy)] = float4(log(max(atmRadiance(result.light), float3(1e-30))), rowCosine);
}
