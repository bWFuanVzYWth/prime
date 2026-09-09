// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

#language slang 2026
module "math/color_space.slang";

public float primeDecodeSrgbChannel(float encoded)
{
    return encoded <= 0.04045
            ? encoded / 12.92
            : pow((encoded + 0.055) / 1.055, 2.4);
}

public float3 primeDecodeSrgb(float3 encoded)
{
    return float3(
            primeDecodeSrgbChannel(encoded.r),
            primeDecodeSrgbChannel(encoded.g),
            primeDecodeSrgbChannel(encoded.b));
}

public float primeEncodeSrgbChannel(float linearValue)
{
    float nonNegative = max(linearValue, 0.0);
    return nonNegative <= 0.0031308
            ? 12.92 * nonNegative
            : 1.055 * pow(nonNegative, 1.0 / 2.4) - 0.055;
}

public float3 primeEncodeSrgb(float3 linearValue)
{
    return float3(
            primeEncodeSrgbChannel(linearValue.r),
            primeEncodeSrgbChannel(linearValue.g),
            primeEncodeSrgbChannel(linearValue.b));
}

public float3 primeLinearSrgbToLinearRec2020(float3 color)
{
    return float3(
            dot(float3(0.6274039, 0.3292830, 0.0433131), color),
            dot(float3(0.0690973, 0.9195404, 0.0113623), color),
            dot(float3(0.0163914, 0.0880133, 0.8955953), color));
}

public float3 primeLinearRec2020ToLinearBt709(float3 color)
{
    return float3(
            dot(float3(1.6604910, -0.5876411, -0.0728499), color),
            dot(float3(-0.1245505, 1.1328999, -0.0083494), color),
            dot(float3(-0.0181508, -0.1005789, 1.1187297), color));
}
