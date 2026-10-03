// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.
#language slang 2026
module "atmosphere/starmap.slang";
__exported import "atmosphere/celestial.slang";
__exported import "atmosphere/starmap_filter.slang";

public float2 primeStarmapUv(PrimeCelestialFrame frame, float3 direction) {
    float east = dot(direction, frame.east);
    float meridian = dot(direction, frame.meridian);
    float pole = dot(direction, frame.pole);
    float rightAscension = frame.siderealPhase - atan2(-east, meridian);
    // Colatitude retains transverse precision at either pole, including non-unit rays.
    // asin(pole) loses that precision as pole rounds to +/-1.
    return float2(frac(0.5 - rightAscension / (2.0 * 3.141592653589793)),
                  atan2(length(float2(east, meridian)), pole) / 3.141592653589793);
}

// A longitude footprint near a pole can cover half the atlas while remaining within one
// screen pixel. Do not widen the latitude mip to satisfy the atlas's 8:1 anisotropy cap.
// Negative means the ordinary UV ellipse remains appropriate.
public float primeStarmapPolarLod(PrimeCelestialFrame frame, float3 ray, float3 dx, float3 dy,
                                  float ordinaryLod, float height) {
    float2 transverse = float2(dot(ray, frame.east), dot(ray, frame.meridian));
    float pole = dot(ray, frame.pole);
    if (dot(transverse, transverse) * 63.0 >= pole * pole)
        return -1.0;
    float inverseLength = rsqrt(dot(ray, ray));
    float3 unit = ray * inverseLength;
    float3 tangentX = (dx - unit * dot(unit, dx)) * inverseLength;
    float3 tangentY = (dy - unit * dot(unit, dy)) * inverseLength;
    float radialTexels =
            height / 3.141592653589793 * sqrt(dot(tangentX, tangentX) + dot(tangentY, tangentY));
    if (ordinaryLod <= log2(max(radialTexels, 1.0)))
        return -1.0;
    return log2(max(radialTexels * 0.3535533905932738, 1.0));
}

public float2 primeStarmapPolarOffset(uint tap) {
    // Equal weights, zero first moment and square-pixel second moments x^2=y^2=1/12.
    float2 offset = (tap & 1u) == 0 ? float2(0.408248290463863, 0)
                                    : float2(0.288675134594813, 0.288675134594813);
    uint quadrant = tap >> 1u;
    if ((quadrant & 1u) != 0)
        offset = float2(-offset.y, offset.x);
    if ((quadrant & 2u) != 0)
        offset = -offset;
    return offset;
}

public float2 primeStarmapPolarUv(PrimeCelestialFrame frame, float3 ray, float3 dx, float3 dy,
                                  uint tap) {
    float2 offset = primeStarmapPolarOffset(tap);
    return primeStarmapUv(frame, ray + dx * offset.x + dy * offset.y);
}

public float3 primeSampleStarmapPolar(Sampler2D<float4> texture, PrimeCelestialFrame frame,
                                      float3 ray, float3 dx, float3 dy, float lod) {
    float3 value = float3(0.0);
    for (uint tap = 0; tap < 8; ++tap) {
        float4 sampled = texture.SampleLevel(primeStarmapPolarUv(frame, ray, dx, dy, tap), lod);
        value += sampled.rgb;
    }
    return value * 0.125;
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
