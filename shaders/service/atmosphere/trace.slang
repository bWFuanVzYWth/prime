#language slang 2026
module "service/atmosphere/trace.slang";

import "prime_abi.slang";
import "prime_abi_types.slang";
import "model/atmosphere/sky.slang";
import "math/atmosphere_direction.slang";
import "model/atmosphere/epipolar.slang";
import "policy/runtime/config.slang";


public float3 primeAtmosphereSky(float3 direction, float3 sunDirection) {
    uint width;
    uint height;
    primeSkyView.GetDimensions(width, height);
    float2 dimensions = float2(width, height);
    float2 uv = atmSkyUvFromDirection(
            direction, sunDirection, dimensions, primePush.atmosphereEyeRadiusKm);
    return max(atmSample2D(primeSkyView, uv).rgb, float3(0.0));
}

public bool primeAtmosphereDistantDirectionVisible(float3 direction) {
    return atmDistantDirectionVisible(
            primePush.atmosphereEyeRadiusKm, direction);
}

public float3 primeAtmosphereDistantTransmittance(float3 direction) {
    if (!primeAtmosphereDistantDirectionVisible(direction)) {
        return float3(0.0);
    }
    float coordinate = primeAtmosphereDirectionCoordinate(direction.y)
            * float(PRIME_ATMOSPHERE_DIRECTION_TRANSMITTANCE_WIDTH - 1u);
    int first = int(coordinate);
    int second = min(first + 1, int(PRIME_ATMOSPHERE_DIRECTION_TRANSMITTANCE_WIDTH) - 1);
    return lerp(primeCameraTransmittance[int2(first, 0)].rgb,
            primeCameraTransmittance[int2(second, 0)].rgb, frac(coordinate));
}

public float3 primeApplyAerialPerspective(
        uint2 pixel,
        float2 cameraSample,
        float primaryDistance,
        float3 radiance) {
    if (isnan(primaryDistance) || isinf(primaryDistance) || primaryDistance < 0.0) {
        return radiance;
    }
    float distanceKm = primaryDistance * ATM_WORLD_UNIT_SCALE_KM;
    uint aerialWidth;
    uint aerialHeight;
    uint aerialDepth;
    primeAerialRadiance.GetDimensions(
            aerialWidth, aerialHeight, aerialDepth);
    int3 aerialDimensions = int3(aerialWidth, aerialHeight, aerialDepth);
    float firstSliceDepth = 0.5 / float(max(aerialDimensions.z, 1));
    float firstSliceDistanceKm = ATM_AERIAL_MAX_DISTANCE_KM
            * firstSliceDepth * firstSliceDepth;
    float normalizedDepth = max(firstSliceDepth, sqrt(clamp(
            distanceKm / max(ATM_AERIAL_MAX_DISTANCE_KM, 1.0e-6), 0.0, 1.0)));
    float nearWeight = clamp(
            distanceKm / max(firstSliceDistanceKm, 1.0e-6), 0.0, 1.0);
    // Offline accumulation integrates the pixel filter. Sampling this direction-dependent
    // term at pixel centre would leave a deterministic sub-pixel bias in the reference image.
    float2 screenUv = (float2(pixel) + cameraSample) / float2(primePush.outputExtent);
    float3 uvw = float3(screenUv, normalizedDepth);
    float2 epipoleNdc = atmEpipolarProjectDirection(
            primePush.inverseViewProjection,
            primePush.sunDirection);
    float3 inscatter = max(
            atmSampleEpipolar(
                    primeAerialRadiance,
                    screenUv,
                    normalizedDepth,
                    epipoleNdc).rgb,
            float3(0.0));
    float3 transmittance = clamp(
            atmSample3D(primeAerialTransmittance, uvw).rgb,
            float3(0.0),
            float3(1.0));
    // The LUT has centre-sampled Z slices and therefore has no stored zero-distance value.
    // Blend the first slice back to the exact identity boundary instead of applying a finite fog
    // segment to nearby geometry. Invalid LUT components must also be neutral here: propagating a
    // NaN reaches raygen's last-resort guard and turns the complete pixel black.
    bool3 invalidInscatter = bool3(
            isnan(inscatter.x) || isinf(inscatter.x),
            isnan(inscatter.y) || isinf(inscatter.y),
            isnan(inscatter.z) || isinf(inscatter.z));
    inscatter = select(invalidInscatter, float3(0.0), inscatter);
    bool3 invalidTransmittance = bool3(
            isnan(transmittance.x) || isinf(transmittance.x),
            isnan(transmittance.y) || isinf(transmittance.y),
            isnan(transmittance.z) || isinf(transmittance.z));
    transmittance = select(
            invalidTransmittance,
            float3(1.0),
            transmittance);
    // Atmosphere LUT construction is linear in the extraterrestrial source. Match sky and direct
    // sun evaluation by applying the same runtime EV scale before this deterministic term enters
    // an offline sample; otherwise non-zero sun EV would change terrain and sky but leave
    // aerial in-scattering at the calibrated base intensity.
    inscatter *= nearWeight * primeSunRadianceMultiplier();
    transmittance = lerp(float3(1.0), transmittance, nearWeight);
    return radiance * transmittance + inscatter;
}
