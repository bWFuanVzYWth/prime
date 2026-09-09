// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

#language slang 2026
module "bsdf/compact/math/spectrum.slang";

public static const float PRIME_OPENPBR_PI = 3.14159265358979323846;
public static const float PRIME_OPENPBR_INV_PI = 0.31830988618379067154;
public static const float PRIME_OPENPBR_DENOM_TOLERANCE = 1.0e-10;

public float primeOpenPbrSquare(float value) { return value * value; }
public float2 primeOpenPbrSquare(float2 value) { return value * value; }
public float3 primeOpenPbrSquare(float3 value) { return value * value; }
public float primeOpenPbrPow4(float value) { float x = value * value; return x * x; }
public float primeOpenPbrPow5(float value) { return primeOpenPbrPow4(value) * value; }
public float primeOpenPbrPow6(float value) { float x = value * value * value; return x * x; }
public float primeOpenPbrReduceSum(float2 value) { return value.x + value.y; }
public float primeOpenPbrReduceSum(float3 value) { return value.x + value.y + value.z; }
public float primeOpenPbrReduceMax(float2 value) { return max(value.x, value.y); }
public float primeOpenPbrReduceMax(float3 value) { return max(value.x, max(value.y, value.z)); }
public float primeOpenPbrReduceMin(float3 value) { return min(value.x, min(value.y, value.z)); }
public float primeOpenPbrSpectrumToWeight(float3 value) {
    return primeOpenPbrReduceSum(value) / 3.0;
}

public float3 primeOpenPbrUniformSampleHemisphere(float2 sampleValue) {
    float sine = sqrt(1.0 - sampleValue.x * sampleValue.x);
    float phi = 2.0 * PRIME_OPENPBR_PI * sampleValue.y;
    return float3(sine * cos(phi), sine * sin(phi), sampleValue.x);
}

public float3 primeOpenPbrCosineSampleHemisphere(float2 sampleValue) {
    float radius = sqrt(sampleValue.x);
    float phi = 2.0 * PRIME_OPENPBR_PI * sampleValue.y;
    return float3(radius * cos(phi), radius * sin(phi), sqrt(1.0 - sampleValue.x));
}

public float primeOpenPbrF0ToIor(float f0) {
    float root = sqrt(clamp(f0, 0.0, 0.999));
    return (1.0 + root) / (1.0 - root);
}

public float3 primeOpenPbrF0ToIor(float3 f0) {
    float3 root = sqrt(clamp(f0, float3(0.0), float3(0.999)));
    return (float3(1.0) + root) / (float3(1.0) - root);
}

public float primeOpenPbrIorToF0(float ior) {
    return primeOpenPbrSquare((ior - 1.0) / (ior + 1.0));
}

public float2 primeOpenPbrSpecularNdfRoughnesses(float roughness, float anisotropy) {
    float2 alpha;
    if (anisotropy == 0.0) return float2(primeOpenPbrSquare(roughness));
    alpha.x = primeOpenPbrSquare(roughness)
            * sqrt(2.0 / (1.0 + primeOpenPbrSquare(1.0 - anisotropy)));
    alpha.y = (1.0 - anisotropy) * alpha.x;
    if (primeOpenPbrReduceMax(alpha) < 1.0e-4) return float2(0.0);
    return max(alpha, float2(1.0e-4));
}

public float primeOpenPbrIorAdjustment(float ior, float coatIor, float coatWeight) {
    if (coatWeight == 0.0) return ior;
    float boundary = coatIor > ior ? coatIor / ior : ior / coatIor;
    return lerp(ior, boundary, coatWeight);
}

public float primeOpenPbrIorAdjustment(float ior, float specularWeight) {
    float e = sign(ior - 1.0)
            * sqrt(clamp(specularWeight * primeOpenPbrIorToF0(ior), 0.0, 0.999));
    return (1.0 + e) / (1.0 - e);
}

public float primeOpenPbrThinDielectricRoughnessScaler2(float ior) {
    float eta = ior >= 1.0 ? ior : 1.0 / ior;
    return 3.7 * (eta - 1.0) * primeOpenPbrSquare(eta - 0.5) / (eta * eta * eta);
}
