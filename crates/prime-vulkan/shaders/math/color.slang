#language slang 2026
module "math/color.slang";

// Encoded source textures/tints are sRGB. Transport and accumulation are linear Rec.2020 (D65).
float decodeChannel(float value) {
    return value <= 0.04045 ? value / 12.92 : pow((value + 0.055) / 1.055, 2.4);
}
float encodeChannel(float value) {
    value = max(value, 0.0);
    return value <= 0.0031308 ? value * 12.92 : 1.055 * pow(value, 1.0 / 2.4) - 0.055;
}
public float3 decodeSrgb(float3 value) {
    return float3(decodeChannel(value.r), decodeChannel(value.g), decodeChannel(value.b));
}
public float3 encodeSrgb(float3 value) {
    return float3(encodeChannel(value.r), encodeChannel(value.g), encodeChannel(value.b));
}
public float3 linear709ToWorking(float3 value) {
    return float3(dot(float3(0.627403896, 0.329283038, 0.043313066), value),
                  dot(float3(0.069097289, 0.919540395, 0.011362316), value),
                  dot(float3(0.016391439, 0.088013308, 0.895595253), value));
}
public float3 workingToLinear709(float3 value) {
    return float3(dot(float3(1.660491002, -0.587641139, -0.072849863), value),
                  dot(float3(-0.124550475, 1.132899897, -0.008349422), value),
                  dot(float3(-0.018150763, -0.100578898, 1.118729661), value));
}
public float workingLuminance(float3 value) {
    return dot(float3(0.262700212, 0.677998072, 0.059301716), value);
}
public float3 sourceToWorking(float3 encoded) {
    return linear709ToWorking(decodeSrgb(encoded));
}
