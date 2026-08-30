#language slang 2026
module "service/reconstruct/image_diagnostic.slang";

public struct PrimeImageDiagnosticPushConstants
{
    public uint2 sourceExtent;
    public uint2 outputExtent;
    public uint2 regionOrigin;
    public uint2 regionExtent;
    public uint presentation;
    public uint clear;
};

[[vk::binding(0, 0)]] public Texture2D<float4> primeImageDiagnosticInput;
[[vk::push_constant]]
public ConstantBuffer<PrimeImageDiagnosticPushConstants> primeImageDiagnosticPush;

public float3 primeDiagnosticFinite(float3 value)
{
    if (any(isnan(value)) || any(isinf(value))) return float3(1.0, 0.0, 1.0);
    return value;
}

public float3 primeDiagnosticMotion(float3 value)
{
    if (any(isnan(value)) || any(isinf(value))) return float3(1.0, 0.0, 1.0);
    float2 scaled = value.xy * 0.05;
    return clamp(float3(0.5 + scaled.x, 0.5 + scaled.y, 0.5 + value.z * 0.05), 0.0, 1.0);
}

public float3 primeDiagnosticValue(float4 source)
{
    uint mode = primeImageDiagnosticPush.presentation;
    if (mode == 1u) {
        float3 value = max(primeDiagnosticFinite(source.rgb), float3(0.0));
        return value / (float3(1.0) + value);
    }
    if (mode == 2u) {
        float3 normal = primeDiagnosticFinite(source.xyz);
        float lengthSquared = dot(normal, normal);
        return lengthSquared > 1.0e-12
                ? normal * rsqrt(lengthSquared) * 0.5 + 0.5
                : float3(0.0);
    }
    if (mode == 4u) return float3(clamp(source.a, 0.0, 1.0));
    if (mode == 6u) {
        float depth = max(abs(source.r), 0.0);
        return float3(clamp(log2(1.0 + depth) / 16.0, 0.0, 1.0));
    }
    if (mode == 7u) return primeDiagnosticMotion(source.xyz);
    if (mode == 8u || mode == 9u) {
        float distance = mode == 8u ? source.r : source.a;
        return float3(clamp(log2(1.0 + max(distance, 0.0)) / 16.0, 0.0, 1.0));
    }
    if (mode == 10u) return clamp(primeDiagnosticFinite(source.rgb) * 0.5 + 0.5, 0.0, 1.0);
    if (mode == 11u) return clamp(primeDiagnosticFinite(source.rgb), 0.0, 1.0);
    if (mode == 12u) return float3(clamp(source.a, 0.0, 1.0));
    if (mode == 13u) {
        if (isnan(source.r) || isinf(source.r)) return float3(1.0, 0.0, 1.0);
        return float3(clamp(source.r * 0.5 + 0.5, 0.0, 1.0));
    }
    return clamp(primeDiagnosticFinite(source.rgb), 0.0, 1.0);
}

public float4 primeImageDiagnosticPixel(uint2 local)
{
    if (primeImageDiagnosticPush.clear != 0u) return float4(0.0, 0.0, 0.0, 1.0);
    float2 scale = float2(primeImageDiagnosticPush.regionExtent)
            / float2(primeImageDiagnosticPush.sourceExtent);
    float fit = min(scale.x, scale.y);
    uint2 fitted = min(
            max(
                    uint2(float2(primeImageDiagnosticPush.sourceExtent) * fit),
                    uint2(1)),
            primeImageDiagnosticPush.regionExtent);
    uint2 bars = (primeImageDiagnosticPush.regionExtent - fitted) / 2u;
    if (any(local < bars) || any(local >= bars + fitted)) {
        return float4(0.0, 0.0, 0.0, 1.0);
    }
    uint2 sourcePixel = min(
            ((local - bars) * primeImageDiagnosticPush.sourceExtent) / fitted,
            primeImageDiagnosticPush.sourceExtent - uint2(1));
    return float4(
            primeDiagnosticValue(primeImageDiagnosticInput.Load(int3(sourcePixel, 0))),
            1.0);
}
