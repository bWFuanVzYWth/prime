// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

#language slang 2026
module "service/reconstruct/display.slang";

import "prime_abi_types.slang";
import "math/color_space.slang";

// Ported from drt shaders/rgb_reinhard.wgsl and src/gpu.rs at
// a15e90947965f93c71e943fe27caf2ea56e08c10. Prime supplies linear Rec.2020, not AP0.
public struct PrimeRgbReinhardParameters
{
    public float outputPeak;
    public float curvePeak;
};

static const float3 PRIME_REINHARD_NEUTRAL_WEIGHTS = float3(
        0.2120053547549465, 0.3921825078090138, 0.3958121374360396);
static const float PRIME_REINHARD_GAMUT_COMPRESSION = 0.04;
static const float PRIME_REINHARD_COMPRESSION_START = 0.18;

public PrimeRgbReinhardParameters primeDefaultRgbReinhardParameters()
{
    PrimeRgbReinhardParameters parameters;
    parameters.outputPeak = 1.0;
    // RgbReinhardOutput derives the asymptote for the requested +8 EV highlight reach.
    parameters.curvePeak = 1.0149157054126;
    return parameters;
}

public float3 primeReinhardToVirtualRgb(float3 color)
{
    float neutral = dot(color, PRIME_REINHARD_NEUTRAL_WEIGHTS);
    return lerp(color, neutral.xxx, PRIME_REINHARD_GAMUT_COMPRESSION);
}

public float3 primeReinhardFromVirtualRgb(float3 color)
{
    // The neutral projection is invariant, so this is the exact linear inverse.
    float neutral = dot(color, PRIME_REINHARD_NEUTRAL_WEIGHTS);
    return (color - PRIME_REINHARD_GAMUT_COMPRESSION * neutral.xxx)
            / (1.0 - PRIME_REINHARD_GAMUT_COMPRESSION);
}

public float primeReinhardCurveComponent(float value, PrimeRgbReinhardParameters parameters)
{
    if (value <= PRIME_REINHARD_COMPRESSION_START) return value;
    float distance = value - PRIME_REINHARD_COMPRESSION_START;
    float extent = parameters.curvePeak - PRIME_REINHARD_COMPRESSION_START;
    // Equivalent reciprocal form avoids distance/extent overflow and a large final
    // denominator whose GPU reciprocal can flush to zero. Extent is bounded by headroom.
    return PRIME_REINHARD_COMPRESSION_START + extent / (1.0 + extent / distance);
}

public float3 primeReinhardCurve(float3 value, PrimeRgbReinhardParameters parameters)
{
    return float3(
            primeReinhardCurveComponent(value.x, parameters),
            primeReinhardCurveComponent(value.y, parameters),
            primeReinhardCurveComponent(value.z, parameters));
}

public float3 primeReinhardEncodeSrgb(float3 linearRgb)
{
    // Preserve negative outset values through hue repair, as in the reference.
    bool3 cutoff = linearRgb < 0.0031308;
    float3 higher = 1.055 * pow(max(linearRgb, 0.0), 1.0 / 2.4) - 0.055;
    float3 lower = linearRgb * 12.92;
    return float3(cutoff.x ? lower.x : higher.x,
            cutoff.y ? lower.y : higher.y, cutoff.z ? lower.z : higher.z);
}

public float3 primeDisplayRgbToHsv(float3 color)
{
    float maximum = max(color.x, max(color.y, color.z));
    float minimum = min(color.x, min(color.y, color.z));
    float chroma = maximum - minimum;
    float hue = 0.0;
    if (chroma > 1.0e-7)
    {
        if (maximum == color.x)
            hue = (color.y - color.z) / chroma;
        else if (maximum == color.y)
            hue = (color.z - color.x) / chroma + 2.0;
        else
            hue = (color.x - color.y) / chroma + 4.0;
        hue = frac(hue / 6.0);
    }
    float saturation = maximum > 1.0e-7 ? chroma / max(maximum, 1.0e-7) : 0.0;
    return float3(hue, saturation, maximum);
}

public float3 primeDisplayHsvToRgb(float3 hsv)
{
    float3 primary = clamp(
            abs(frac(hsv.x + float3(0.0, 2.0 / 3.0, 1.0 / 3.0)) * 6.0 - 3.0) - 1.0,
            0.0, 1.0);
    return hsv.z * lerp(1.0.xxx, primary, hsv.y);
}

public float3 primeReinhardProtectHsvHue(float3 originalLinear, float3 mappedDisplay)
{
    float3 originalHsv = primeDisplayRgbToHsv(primeReinhardEncodeSrgb(originalLinear));
    float3 mappedHsv = primeDisplayRgbToHsv(mappedDisplay);
    if (originalHsv.y <= 1.0e-7 || mappedHsv.y <= 1.0e-7) return mappedDisplay;
    float hueOffset = originalHsv.x - mappedHsv.x;
    hueOffset -= floor(hueOffset + 0.5);
    mappedHsv.x = frac(mappedHsv.x + 0.5 * hueOffset);
    return primeDisplayHsvToRgb(mappedHsv);
}

public float3 primeRgbReinhardTonemap(float3 linearBt709, PrimeRgbReinhardParameters parameters)
{
    float3 nonnegative = max(linearBt709, float3(0.0));
    float3 working = primeReinhardToVirtualRgb(nonnegative);
    float3 mappedLinear = primeReinhardFromVirtualRgb(primeReinhardCurve(working, parameters));
    float3 mappedDisplay = primeReinhardEncodeSrgb(mappedLinear);
    return primeReinhardProtectHsvHue(nonnegative, mappedDisplay);
}

public float3 primeDisplayTransformToSrgb(
        float3 hdrRec2020,
        float exposureMultiplier,
        float outputHeadroom,
        float curvePeak)
{
    float3 exposedRec2020 = max(hdrRec2020, float3(0.0))
            * (PRIME_DISPLAY_EXPOSURE * exposureMultiplier);
    float3 linearBt709 = max(primeLinearRec2020ToLinearBt709(exposedRec2020), float3(0.0));
    float encodedOutputPeak = outputHeadroom == 1.0 ? 1.0 : primeEncodeSrgbChannel(outputHeadroom);
    PrimeRgbReinhardParameters parameters;
    parameters.outputPeak = outputHeadroom;
    parameters.curvePeak = curvePeak;
    return clamp(primeRgbReinhardTonemap(linearBt709, parameters), 0.0, encodedOutputPeak);
}

public float3 primeDisplayTransformToSrgb(float3 hdrRec2020, float exposureMultiplier)
{
    PrimeRgbReinhardParameters parameters = primeDefaultRgbReinhardParameters();
    return primeDisplayTransformToSrgb(hdrRec2020, exposureMultiplier,
            1.0, parameters.curvePeak);
}
