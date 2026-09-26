// Adapted from legacy Prime's display.slang, copyright (c) 2026 linlin.
// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.
#language slang 2026
module "display/prime_drt.slang";
import "math/color.slang";

// Replaceable display policy, not a math foundation or a stable parameter ABI.

// Only values actually varying in legacy Prime survive as parameters. The host
// derives curve/output peaks once per calibration change, not once per pixel.
public struct PrimeDRTParameters {
    public float exposureMultiplier;
    public float curvePeak;
    public float encodedOutputPeak;
    public float hueCompensation;
    public float saturationCompensation;
};
static const float3 NEUTRAL_WEIGHTS =
        float3(0.2120053547549465, 0.3921825078090138, 0.3958121374360396);
static const float GAMUT_COMPRESSION = 0.04;
static const float COMPRESSION_START = 0.18;
float3 toVirtualRgb(float3 color) {
    float neutral = dot(color, NEUTRAL_WEIGHTS);
    return lerp(color, neutral.xxx, GAMUT_COMPRESSION);
}

float3 fromVirtualRgb(float3 color) {
    // The neutral projection is invariant, so this is the exact linear inverse.
    float neutral = dot(color, NEUTRAL_WEIGHTS);
    return (color - GAMUT_COMPRESSION * neutral.xxx) / (1.0 - GAMUT_COMPRESSION);
}

float curveComponent(float value, PrimeDRTParameters parameters) {
    if (value <= COMPRESSION_START)
        return value;
    float distance = value - COMPRESSION_START;
    float extent = parameters.curvePeak - COMPRESSION_START;
    // Equivalent reciprocal form avoids distance/extent overflow and a large final
    // denominator whose GPU reciprocal can flush to zero. Extent is bounded by headroom.
    return COMPRESSION_START + extent / (1.0 + extent / distance);
}

float3 curve(float3 value, PrimeDRTParameters parameters) {
    return float3(curveComponent(value.x, parameters), curveComponent(value.y, parameters),
                  curveComponent(value.z, parameters));
}

float3 encodeSrgb(float3 linearRgb) {
    // Preserve negative outset values through hue compensation, as in the reference.
    bool3 cutoff = linearRgb < 0.0031308;
    float3 higher = 1.055 * pow(max(linearRgb, 0.0), 1.0 / 2.4) - 0.055;
    float3 lower = linearRgb * 12.92;
    return float3(cutoff.x ? lower.x : higher.x, cutoff.y ? lower.y : higher.y,
                  cutoff.z ? lower.z : higher.z);
}

float3 rgbToHsv(float3 color) {
    float maximum = max(color.x, max(color.y, color.z));
    float minimum = min(color.x, min(color.y, color.z));
    float chroma = maximum - minimum;
    float hue = 0.0;
    // Nonzero chroma still has a hue: strong saturation can make near-neutral hue loss visible.
    if (chroma > 0.0 && maximum > 1.0e-7) {
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

float3 hsvToRgb(float3 hsv) {
    float3 primary =
            clamp(abs(frac(hsv.x + float3(0.0, 2.0 / 3.0, 1.0 / 3.0)) * 6.0 - 3.0) - 1.0, 0.0, 1.0);
    return hsv.z * lerp(1.0.xxx, primary, hsv.y);
}

float3 compensateHsv(float3 originalLinear, float3 mappedDisplay, float hueCompensation,
                     float saturationCompensation) {
    if (hueCompensation == 0.0 && saturationCompensation == 0.0 && all(mappedDisplay >= 0.0))
        return mappedDisplay;
    float3 mappedHsv = rgbToHsv(mappedDisplay);
    if (mappedHsv.y == 0.0)
        return mappedDisplay;
    if (hueCompensation > 0.0 && mappedHsv.y > 1.0e-7) {
        float3 originalHsv = rgbToHsv(encodeSrgb(originalLinear));
        if (originalHsv.y > 1.0e-7) {
            float hueOffset = originalHsv.x - mappedHsv.x;
            hueOffset -= floor(hueOffset + 0.5);
            mappedHsv.x = frac(mappedHsv.x + hueCompensation * hueOffset);
        }
    }
    // Prime artistic adjustment after hue compensation; leave H/V and neutral endpoints intact.
    // Saturation overflow, including negative outset channels, is clipped even at zero boost.
    mappedHsv.y = min(mappedHsv.y * (1.0 + saturationCompensation), 1.0);
    return hsvToRgb(mappedHsv);
}

float3 tonemap(float3 linearBt709, PrimeDRTParameters parameters) {
    float3 nonnegative = max(linearBt709, float3(0.0));
    float3 working = toVirtualRgb(nonnegative);
    float3 mappedLinear = fromVirtualRgb(curve(working, parameters));
    float3 mappedDisplay = encodeSrgb(mappedLinear);
    return compensateHsv(nonnegative, mappedDisplay, parameters.hueCompensation,
                         parameters.saturationCompensation);
}

// Linear Rec.2020 scene radiance -> encoded sRGB display values. Negative outset
// channels stay intact until HSV compensation, exactly as in the original DRT.
public float3 primeDRT(float3 hdrRec2020, PrimeDRTParameters parameters) {
    if (any(isnan(hdrRec2020)) || any(isinf(hdrRec2020)))
        return float3(0.0);
    float3 exposed = max(hdrRec2020, float3(0.0)) * parameters.exposureMultiplier;
    float3 linear709 = max(workingToLinear709(exposed), float3(0.0));
    return clamp(tonemap(linear709, parameters), 0.0, parameters.encodedOutputPeak);
}
