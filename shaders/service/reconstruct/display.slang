#language slang 2026
module "service/reconstruct/display.slang";

import "prime_abi_types.slang";
import "math/color_space.slang";

// Ported from C:/WorkSpace/drt at commit 09c162b2e4647b1c6e56ff6028baa1048a57c1cb.
// Prime keeps its Rec.2020 input boundary, so the source bench's AP0 conversion is omitted.

public struct PrimeReinhardAgxParameters
{
    public float gamutExpansion;
    public float compressionStart;
    // Linear display headroom; encoding happens after the tone curve.
    public float outputPeak;
    public float hueRetention;
    public float shoulderCoefficient;
};

static const float3 PRIME_REINHARD_NEUTRAL_WEIGHTS = float3(
        0.2120053547549465,
        0.3921825078090138,
        0.3958121374360396);
static const float PRIME_REINHARD_MIDDLE_GRAY = 0.18;
static const float PRIME_REINHARD_COMPRESSION_START = 0.18;
static const float PRIME_REINHARD_HIGHLIGHT_REACH_EV = 8.0;
static const float PRIME_REINHARD_SHOULDER_POWER = 5.0;

public float primeReinhardShoulderCoefficient(float outputPeak)
{
    // The bench's default input scale gives a unit linear slope.
    float reachInput = PRIME_REINHARD_MIDDLE_GRAY
            * exp2(PRIME_REINHARD_HIGHLIGHT_REACH_EV) * outputPeak;
    float outputDistance = outputPeak - PRIME_REINHARD_COMPRESSION_START;
    float logReach = 0.69314718056 * log2(
            1.0 + (reachInput - PRIME_REINHARD_COMPRESSION_START) / outputDistance);
    return 1.0 - pow(logReach, -PRIME_REINHARD_SHOULDER_POWER);
}

public PrimeReinhardAgxParameters primeDefaultReinhardAgxParameters()
{
    PrimeReinhardAgxParameters parameters;
    parameters.gamutExpansion = 0.04;
    parameters.compressionStart = PRIME_REINHARD_COMPRESSION_START;
    parameters.outputPeak = 1.0;
    parameters.hueRetention = 0.5;
    parameters.shoulderCoefficient = primeReinhardShoulderCoefficient(parameters.outputPeak);
    return parameters;
}

public float3 primeReinhardToExpandedGamut(
        float3 color,
        PrimeReinhardAgxParameters parameters)
{
    // Moving the virtual primaries outward contracts unchanged Rec.709 coordinates
    // toward the neutral axis.
    float neutral = dot(color, PRIME_REINHARD_NEUTRAL_WEIGHTS);
    return lerp(color, neutral.xxx, parameters.gamutExpansion);
}

public float3 primeReinhardFromExpandedGamut(
        float3 color,
        PrimeReinhardAgxParameters parameters)
{
    // The neutral projection is invariant, so this is the exact linear inverse.
    float neutral = dot(color, PRIME_REINHARD_NEUTRAL_WEIGHTS);
    return (color - parameters.gamutExpansion * neutral.xxx)
            / (1.0 - parameters.gamutExpansion);
}

float primeReinhardLogDistance(float distance)
{
    // ln(1+x), with the bench's cancellation-safe series near the linear join.
    if (distance < 0.001)
        return distance * (1.0 + distance * (-0.5 + distance / 3.0));
    return 0.69314718056 * log2(1.0 + distance);
}

public float primeReinhardCurveComponent(
        float color,
        PrimeReinhardAgxParameters parameters)
{
    float start = parameters.compressionStart;
    if (color <= start) return color;
    float extent = parameters.outputPeak - start;
    // Unit input slope and normalized output room preserve the join tangent in SDR/HDR.
    float distance = primeReinhardLogDistance((color - start) / extent);
    float power = PRIME_REINHARD_SHOULDER_POWER;
    return start + extent * distance
            * pow(1.0 + parameters.shoulderCoefficient * pow(distance, power), -1.0 / power);
}

public float3 primeReinhardCurve(
        float3 color,
        PrimeReinhardAgxParameters parameters)
{
    return float3(
            primeReinhardCurveComponent(color.x, parameters),
            primeReinhardCurveComponent(color.y, parameters),
            primeReinhardCurveComponent(color.z, parameters));
}

public float3 primeReinhardEncodeSrgb(float3 linearRgb)
{
    // Preserve negative results through hue repair; the display boundary clamps them later.
    bool3 cutoff = linearRgb < 0.0031308;
    float3 higher = 1.055 * pow(max(linearRgb, 0.0), 1.0 / 2.4) - 0.055;
    float3 lower = linearRgb * 12.92;
    return float3(
            cutoff.x ? lower.x : higher.x,
            cutoff.y ? lower.y : higher.y,
            cutoff.z ? lower.z : higher.z);
}

public float3 primeReinhardRgbToHsv(float3 color)
{
    float maximum = max(color.x, max(color.y, color.z));
    float minimum = min(color.x, min(color.y, color.z));
    float chroma = maximum - minimum;
    float hue = 0.0;
    if (chroma > 1.0e-7)
    {
        if (maximum == color.x)
        {
            hue = (color.y - color.z) / chroma;
        }
        else if (maximum == color.y)
        {
            hue = (color.z - color.x) / chroma + 2.0;
        }
        else
        {
            hue = (color.x - color.y) / chroma + 4.0;
        }
        hue = frac(hue / 6.0);
    }
    float saturation = maximum > 1.0e-7 ? chroma / maximum : 0.0;
    return float3(hue, saturation, maximum);
}

public float3 primeReinhardHsvToRgb(float3 hsv)
{
    float3 primary = clamp(
            abs(frac(hsv.x + float3(0.0, 2.0 / 3.0, 1.0 / 3.0)) * 6.0 - 3.0) - 1.0,
            0.0,
            1.0);
    return hsv.z * lerp(1.0.xxx, primary, hsv.y);
}

public float3 primeReinhardProtectHsvHue(
        float3 originalLinear,
        float3 mappedDisplay,
        PrimeReinhardAgxParameters parameters)
{
    if (parameters.hueRetention <= 0.0) return mappedDisplay;

    float3 originalHsv = primeReinhardRgbToHsv(
            primeReinhardEncodeSrgb(originalLinear));
    float3 mappedHsv = primeReinhardRgbToHsv(mappedDisplay);
    if (originalHsv.y <= 1.0e-7 || mappedHsv.y <= 1.0e-7) return mappedDisplay;

    float hueOffset = originalHsv.x - mappedHsv.x;
    hueOffset -= floor(hueOffset + 0.5);
    mappedHsv.x = frac(mappedHsv.x + parameters.hueRetention * hueOffset);
    return primeReinhardHsvToRgb(mappedHsv);
}

public float3 primeReinhardAgxTonemap(
        float3 linearBt709,
        PrimeReinhardAgxParameters parameters)
{
    float3 nonnegative = max(linearBt709, float3(0.0));
    float3 working = primeReinhardToExpandedGamut(nonnegative, parameters);
    float3 mappedLinear = primeReinhardFromExpandedGamut(
            primeReinhardCurve(working, parameters),
            parameters);
    float3 mappedDisplay = primeReinhardEncodeSrgb(mappedLinear);
    return primeReinhardProtectHsvHue(nonnegative, mappedDisplay, parameters);
}

public float3 primeDisplayTransformToSrgb(
        float3 hdrRec2020,
        float exposureMultiplier,
        float outputPeak,
        float shoulderCoefficient)
{
    float3 exposedRec2020 = max(hdrRec2020, float3(0.0))
            * (PRIME_DISPLAY_EXPOSURE * exposureMultiplier);
    float3 linearBt709 = max(
            primeLinearRec2020ToLinearBt709(exposedRec2020),
            float3(0.0));
    PrimeReinhardAgxParameters parameters = primeDefaultReinhardAgxParameters();
    parameters.outputPeak = outputPeak;
    parameters.shoulderCoefficient = shoulderCoefficient;
    float encodedOutputPeak = primeReinhardEncodeSrgb(outputPeak.xxx).x;
    return clamp(
            primeReinhardAgxTonemap(linearBt709, parameters),
            float3(0.0),
            encodedOutputPeak.xxx);
}

public float3 primeDisplayTransformToSrgb(
        float3 hdrRec2020,
        float exposureMultiplier)
{
    PrimeReinhardAgxParameters parameters = primeDefaultReinhardAgxParameters();
    return primeDisplayTransformToSrgb(
            hdrRec2020,
            exposureMultiplier,
            parameters.outputPeak,
            parameters.shoulderCoefficient);
}
