#language slang 2026
module "service/material/tint.slang";

import glsl;
import "math/color_space.slang";

// Each immutable TintId names one source-linear sRGB RGBA16F modulation sample.
// Binding 50 is reviewed in shaders/abi.json.
[[vk::binding(50, 0)]]
public ByteAddressBuffer primeTintSamples;

static const uint PRIME_TINT_SAMPLE_SIZE = 8u;

/** Source-linear sRGB modulation plus non-color alpha, stored as one RGBA16F sample. */
public struct PrimeLinearTintModulation {
    public float4 value;
};

public PrimeLinearTintModulation primeUnpackLinearTintModulation(uint2 packed) {
    PrimeLinearTintModulation result;
    result.value = float4(
            unpackHalf2x16ToFloat(packed.x),
            unpackHalf2x16ToFloat(packed.y));
    return result;
}

/** Applies Minecraft's source-linear modulation and immediately returns to linear Rec.2020. */
public float4 primeApplyLinearTintModulation(
        float4 linearRec2020Base,
        PrimeLinearTintModulation tint) {
    float3 sourceLinear = primeLinearRec2020ToLinearBt709(linearRec2020Base.rgb);
    float3 result = primeLinearSrgbToLinearRec2020(sourceLinear * tint.value.rgb);
    return float4(clamp(result, float3(0.0), float3(1.0)),
            linearRec2020Base.a * tint.value.a);
}

/** Adapts an inline encoded-sRGB vertex color that has not entered the global sample table. */
public float3 primeInlineSrgbTintColor(float3 encodedSrgb) {
    return primeLinearSrgbToLinearRec2020(primeDecodeSrgb(encodedSrgb));
}

public PrimeLinearTintModulation primeTintSample(uint tintIdentity) {
    uint tintId = tintIdentity & 0xffffu;
    return primeUnpackLinearTintModulation(
            primeTintSamples.Load2(tintId * PRIME_TINT_SAMPLE_SIZE));
}

public float4 primeStaticTintColor(uint tintIdentity) {
    return primeApplyLinearTintModulation(
            float4(1.0), primeTintSample(tintIdentity));
}

public float4 primeApplyStaticTint(
        float4 linearRec2020, PrimeLinearTintModulation tint) {
    return primeApplyLinearTintModulation(linearRec2020, tint);
}

public float4 primeApplyStaticTint(
        float4 linearRec2020, uint tintIdentity) {
    return primeApplyStaticTint(linearRec2020, primeTintSample(tintIdentity));
}

public float3 primeApplyStaticTint(
        float3 linearRec2020, uint tintIdentity) {
    return primeApplyStaticTint(
            float4(linearRec2020, 1.0), tintIdentity).rgb;
}
