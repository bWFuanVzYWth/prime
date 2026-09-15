// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

#language slang 2026
module "model/material/transparent.slang";

import "math/color_space.slang";

public static const float3 PRIME_PURE_WATER_ABSORPTION_M_INV =
        float3(0.2916, 0.04444, 0.010182);
public static const float PRIME_THIN_WALLED_THICKNESS_M = 0.0625;
public static const float PRIME_STAINED_GLASS_ABSORPTION_OPACITY = 0.4;
public static const float PRIME_GLASS_ROUGH_OPACITY_THRESHOLD = 0.5;
public static const float PRIME_GLASS_STAINED_REFERENCE_THRESHOLD = 0.5 / 255.0;

public bool primeGlassReferenceIsStained(float opacity)
{
    return opacity >= PRIME_GLASS_STAINED_REFERENCE_THRESHOLD;
}

public bool primeGlassTexelIsRough(float opacity, bool seamlessGlass)
{
    return !seamlessGlass && opacity > PRIME_GLASS_ROUGH_OPACITY_THRESHOLD;
}

public float3 primeShadowCanonicalExtinction(float3 extinction)
{
    float2 extinction01 = unpackHalf2x16ToFloat(packHalf2x16(extinction.xy));
    float extinction2 = unpackHalf2x16ToFloat(packHalf2x16(float2(extinction.z, 0.0))).x;
    return float3(extinction01, extinction2);
}

public float3 primeGlassVolumeTransmittance(float3 baseColor, float opacity)
{
    float3 linearSrgb = clamp(
            primeLinearRec2020ToLinearBt709(baseColor), float3(0.0), float3(1.0));
    float3 encodedSrgb = primeEncodeSrgb(linearSrgb);
    float coverage = clamp(opacity, 0.0, 1.0);
    float3 encodedOverWhite = lerp(float3(1.0), encodedSrgb, coverage);
    return clamp(
            primeLinearSrgbToLinearRec2020(primeDecodeSrgb(encodedOverWhite)),
            float3(0.0),
            float3(1.0));
}

public float3 primeGlassVolumeExtinction(float3 baseColor, float opacity)
{
    return -log(max(float3(1.0e-3), primeGlassVolumeTransmittance(baseColor, opacity)));
}

public float3 primeGlassShadowExtinction(float3 referenceBaseColor, float referenceOpacity)
{
    return primeGlassReferenceIsStained(referenceOpacity)
            ? primeGlassVolumeExtinction(
                    referenceBaseColor, PRIME_STAINED_GLASS_ABSORPTION_OPACITY)
            : float3(0.0);
}

public float3 primeShadowBoundaryOpticalDepthMoment(
        float3 currentMoment,
        float3 extinction,
        float hitDistance,
        bool entering)
{
    return currentMoment + extinction * (entering ? -hitDistance : hitDistance);
}

public float3 primeShadowThinOpticalDepth(
        float3 currentMoment,
        float3 extinction,
        float rayDistance,
        float cosine)
{
    float distance = min(
            PRIME_THIN_WALLED_THICKNESS_M / max(abs(cosine), 1.0e-3), rayDistance);
    return currentMoment + extinction * distance;
}

public float3 primeShadowOpticalDepth(
        float3 opticalDepthMoment, float3 terminalExtinction, float rayDistance) {
    return max(opticalDepthMoment + terminalExtinction * rayDistance, float3(0.0));
}

// Signed boundary moments integrate the piecewise medium field independently of traversal order.
// Select the terminal medium by distance, without adding/subtracting extinction to recover it.
public void primeShadowCrossBoundary(
        inout float3 moment, inout float3 terminal, inout float lastBoundary,
        uint startingId, float3 startingExtinction,
        uint insideId, float3 inside, uint outsideId, float3 outside,
        bool entering, float distance) {
    if (insideId == startingId) inside = startingExtinction;
    if (outsideId == startingId) outside = startingExtinction;
    moment = primeShadowBoundaryOpticalDepthMoment(moment, inside, distance, entering);
    moment = primeShadowBoundaryOpticalDepthMoment(moment, outside, distance, !entering);
    if (distance > lastBoundary) {
        lastBoundary = distance;
        terminal = entering ? inside : outside;
    }
}
