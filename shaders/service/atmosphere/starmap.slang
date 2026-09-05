#language slang 2026
module "service/atmosphere/starmap.slang";

import "prime_abi.slang";
import "prime_abi_types.slang";
import "service/atmosphere/trace.slang";
import "model/atmosphere/celestial.slang";
import "math/color_space.slang";
import "policy/runtime/config.slang";

static const float PRIME_STARMAP_PI = 3.141592653589793;

// NASA's plate-carree source is ICRF/J2000, centred on RA 0h with RA increasing left.
public float2 primeStarmapUv(IntegratorRecord integrator, float3 direction) {
    PrimeCelestialFrame frame = primeCelestialFrame(
            integrator.sunDirectionIntensity.xyz,
            primeObserverLatitudeRadians(),
            primeSolarLongitudeRadians());
    float2 equatorial =
            primeCelestialEquatorialCoordinates(frame, direction);
    return float2(
            frac(0.5 - equatorial.x / (2.0 * PRIME_STARMAP_PI)),
            clamp(0.5 - equatorial.y / PRIME_STARMAP_PI, 0.0, 1.0));
}

public float3 primeStarmapRadianceUv(
        float3 direction,
        float2 uv,
        float scale) {
    float3 transmittance = primeAtmosphereDistantTransmittance(direction);
    if (all(transmittance <= float3(0.0))) {
        return float3(0.0);
    }
    float3 source = max(primeStarmap.SampleLevel(uv, 0.0).rgb, float3(0.0));
    return primeLinearSrgbToLinearRec2020(source) * scale * transmittance;
}

public float3 primeStarmapRadiance(
        IntegratorRecord integrator,
        float3 direction) {
    float scale =
            PRIME_STARMAP_BASE_RADIANCE_SCALE * primeStarRadianceMultiplier();
    return scale > 0.0
            ? primeStarmapRadianceUv(
                    direction,
                    primeStarmapUv(integrator, direction),
                    scale)
            : float3(0.0);
}
