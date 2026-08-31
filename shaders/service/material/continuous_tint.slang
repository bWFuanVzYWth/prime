#language slang 2026
module "service/material/continuous_tint.slang";

import glsl;
import "math/color_space.slang";

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
