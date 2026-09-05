import glsl;
import "model/atmosphere/spectrum.slang";

import "prime_abi_types.slang";
import "model/atmosphere/sky.slang";
import "math/atmosphere_direction.slang";
[[vk::binding(0, 0)]] StructuredBuffer<float4> spectralTable;
[[vk::binding(1, 0)]] RWStructuredBuffer<float4> output;
struct Push { float radius; uint count; };
[[vk::push_constant]] ConstantBuffer<Push> push;

float4 texel(int2 pixel, uint group) {
    pixel = clamp(pixel, int2(0), int2(255, 63));
    return spectralTable[(uint(pixel.y) * 256u + uint(pixel.x)) * 2u + group];
}

float4 sampleSpectral(float2 uv, uint group) {
    float2 coordinate = clamp(uv, float2(0), float2(1)) * float2(256, 64) - 0.5;
    int2 first = int2(floor(coordinate));
    float2 weight = frac(coordinate);
    return lerp(lerp(texel(first, group), texel(first + int2(1, 0), group), weight.x),
            lerp(texel(first + int2(0, 1), group), texel(first + int2(1, 1), group), weight.x), weight.y);
}

float3 quantize(float3 value) {
    return float3(unpackHalf2x16ToFloat(packHalf2x16(value.xy)),
            unpackHalf2x16ToFloat(packHalf2x16(float2(value.z, 0.0))).x);
}

float3 reference(float mu) {
    float radius = clamp(push.radius, ATM_BOTTOM_RADIUS_KM + ATM_WORLD_UNIT_SCALE_KM,
            ATM_TOP_RADIUS_KM - ATM_WORLD_UNIT_SCALE_KM);
    float2 uv = atmTransmittanceUv(mu, (radius - ATM_BOTTOM_RADIUS_KM) / ATM_THICKNESS_KM);
    return atmRec2020Transmittance(sampleSpectral(uv, 0u), sampleSpectral(uv, 1u));
}

[numthreads(64, 1, 1)]
void main(uint3 thread : SV_DispatchThreadID) {
    uint index = thread.x;
    if (index >= push.count) return;
    // Alternate uniform directions and a dense horizon grid, including both endpoint nodes.
    float u = float(index / 2u) / float((push.count - 1u) / 2u);
    float mu = (index & 1u) == 0u ? 2.0 * u - 1.0 : primeAtmosphereDirectionCosine(u);
    float coordinate = primeAtmosphereDirectionCoordinate(mu)
            * float(PRIME_ATMOSPHERE_DIRECTION_TRANSMITTANCE_WIDTH - 1u);
    float first = floor(coordinate);
    float second = min(first + 1.0, float(PRIME_ATMOSPHERE_DIRECTION_TRANSMITTANCE_WIDTH - 1u));
    float3 a = quantize(reference(primeAtmosphereDirectionCosine(
            first / float(PRIME_ATMOSPHERE_DIRECTION_TRANSMITTANCE_WIDTH - 1u))));
    float3 b = quantize(reference(primeAtmosphereDirectionCosine(
            second / float(PRIME_ATMOSPHERE_DIRECTION_TRANSMITTANCE_WIDTH - 1u))));
    float3 approximate = lerp(a, b, frac(coordinate));
    float3 expected = reference(mu);
    bool visible = atmDistantDirectionVisible(push.radius, float3(sqrt(max(1.0 - mu * mu, 0.0)), mu, 0.0));
    output[2u * index] = float4(visible ? expected : float3(0), mu);
    output[2u * index + 1u] = float4(visible ? approximate : float3(0),
            primeAtmosphereDirectionCosine(primeAtmosphereDirectionCoordinate(mu)));
}
