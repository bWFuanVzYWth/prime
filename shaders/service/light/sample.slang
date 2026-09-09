// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

#language slang 2026
module "service/light/sample.slang";

import "prime_abi_types.slang";
import "contract/light_sample.slang";
import "model/atmosphere/sky.slang";
import "service/atmosphere/trace.slang";
import "policy/runtime/config.slang";
import "math/basis.slang";
import "math/transport.slang";
import "service/atmosphere/starmap.slang";

static const float PRIME_LIGHT_PI = 3.141592653589793;

// All radiance values in this adapter are linear Rec.2020 D65. pdf is the complete f32 sampling
// density, including light-selection probability when a future registry introduces one. Reverse
// PDF queries must reuse that exact quantized value. The environment is evaluated only when a BSDF
// path escapes; the sun and area-light adapters are sampled explicitly and perform no selection.
// The submitted sun direction and all path directions are unit vectors at this boundary.
// Renormalizing them per vertex would only repeat a square root and three divisions.

public float primePowerHeuristic(float firstPdf, float secondPdf) {
    return primePowerHeuristicValue(firstPdf, secondPdf);
}
public float primePowerHeuristicOverPdf(float sampledPdf, float otherPdf) {
    float result = primePowerHeuristicOverPdfValue(sampledPdf, otherPdf);
    return result;
}

public float3 primeEnvironmentRadiance(IntegratorRecord integrator, float3 direction) {
    // Atmosphere LUT construction is linear in its extraterrestrial source. Reusing the calibrated
    // base LUT and applying the same scale as direct sun is exact for this single-source model.
    float sunScale = max(integrator.sunDirectionIntensity.w, 0.0)
            / ATM_SPACE_SUN_INTENSITY;
    return primeAtmosphereSky(direction, integrator.sunDirectionIntensity.xyz) * sunScale;
}

float primeSunCosAngularRadius() {
    return cos(ATM_SUN_ANGULAR_RADIUS_RADIANS);
}

float primeSunSolidAngle() {
    // 4*pi*sin(radius/2)^2 is algebraically identical to 2*pi*(1-cos(radius))
    // but avoids subtracting two nearly equal f32 values for the real solar radius.
    float sineHalfRadius = sin(0.5 * ATM_SUN_ANGULAR_RADIUS_RADIANS);
    return 4.0 * PRIME_LIGHT_PI * sineHalfRadius * sineHalfRadius;
}

public float primeSunPdf() {
    return 1.0 / primeSunSolidAngle();
}

bool primeSunContainsDirection(IntegratorRecord integrator, float3 direction) {
    return dot(direction, integrator.sunDirectionIntensity.xyz)
            >= primeSunCosAngularRadius();
}

float3 primeSunRadiance(
        IntegratorRecord integrator,
        float3 direction) {
    return float3(max(integrator.sunDirectionIntensity.w, 0.0) / primeSunSolidAngle())
            * primeAtmosphereDistantTransmittance(direction);
}

public float3 primeResolveSampledSunRadiance(
        IntegratorRecord integrator,
        LightSample light) {
    return primeSunRadiance(integrator, light.direction);
}

public LightEvaluation primeEvaluateSun(
        IntegratorRecord integrator,
        float3 direction) {
    LightEvaluation result;
    bool containsDirection = primeSunContainsDirection(integrator, direction);
    result.radiance = containsDirection
            ? primeSunRadiance(integrator, direction)
            : float3(0.0);
    result.pdf = containsDirection ? primeSunPdf() : 0.0;
    return result;
}

public LightSample primeSampleSun(
        IntegratorRecord integrator,
        float2 sampleValue) {
    float cosine = lerp(primeSunCosAngularRadius(), 1.0, sampleValue.x);
    float sine = sqrt(1.0 - cosine * cosine);
    float azimuth = 2.0 * PRIME_LIGHT_PI * sampleValue.y;
    float3 localDirection = float3(sine * cos(azimuth), sine * sin(azimuth), cosine);
    LightSample result;
    result.direction = primeLocalToWorld(
            localDirection,
            integrator.sunDirectionIntensity.xyz);
    result.distance = 1000000.0;
    // Visibility must be known before evaluating the atmosphere transmittance.
    result.radiance = float3(0.0);
    result.pdf = primeSunPdf();
    result.isDelta = 0u;
    return result;
}
