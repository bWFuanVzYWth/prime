// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.
#language slang 2026
module "atmosphere/starmap.slang";
__exported import "atmosphere/celestial.slang";
__exported import "atmosphere/starmap_filter.slang";

public float2 primeStarmapUv(PrimeCelestialFrame frame, float3 direction) {
    float2 equatorial = primeCelestialEquatorialCoordinates(frame, direction);
    return float2(frac(0.5 - equatorial.x / (2.0 * 3.141592653589793)),
                  clamp(0.5 - equatorial.y / 3.141592653589793, 0.0, 1.0));
}
public float3 primeSampleStarmap(Sampler2D<float4> texture, float2 uv,
                                 PrimeStarmapFootprint footprint) {
    float3 value = float3(0.0);
    for (uint tap = 0; tap < footprint.taps; ++tap) {
        float offset = float(tap) + 0.5 - 0.5 * float(footprint.taps);
        value += texture.SampleLevel(uv + footprint.stepUv * offset, footprint.lod).rgb;
    }
    return value / float(footprint.taps);
}
