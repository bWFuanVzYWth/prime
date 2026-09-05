#language slang 2026
module "math/triangle_uv.slang";

import "math/packed_geometry.slang";

public float2 primeTriangleUv(uint3 packedUv, float2 barycentrics) {
    float3 barycentric = float3(1.0 - barycentrics.x - barycentrics.y,
            barycentrics.x, barycentrics.y);
    return primeUnpackUv(packedUv.x) * barycentric.x
            + primeUnpackUv(packedUv.y) * barycentric.y
            + primeUnpackUv(packedUv.z) * barycentric.z;
}

public float2 primeRepeatedUv(uint3 packedUv, float3 localPosition, float3 geometricNormal) {
    float3 normal = abs(geometricNormal);
    float2 projectedPosition = normal.x > normal.y && normal.x > normal.z
            ? localPosition.yz : (normal.y > normal.z ? localPosition.xz : localPosition.xy);
    float2 repeatedPosition = frac(projectedPosition);
    float2 uv0 = primeUnpackUv(packedUv.x);
    float2 uv1 = primeUnpackUv(packedUv.y);
    float2 uv2 = primeUnpackUv(packedUv.z);
    return uv0 + repeatedPosition.x * (uv1 - uv0) + repeatedPosition.y * (uv2 - uv0);
}

public float3 primeTriangleUnitNormal(float3 normal) {
    // Translation has removed exact degenerates. Preserve the full hit reconstruction's
    // evaluation order, including normalization before dominant-axis comparisons.
    float normalScale = max(abs(normal.x), max(abs(normal.y), abs(normal.z)));
    normal /= normalScale;
    return normalize(normal);
}
