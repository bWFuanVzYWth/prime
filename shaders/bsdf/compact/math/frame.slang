// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

#language slang 2026
module "bsdf/compact/math/frame.slang";

import "bsdf/compact/contract/material.slang";

public PrimeOpenPbrOnb primeOpenPbrOnbFromNormal(float3 normal) {
    float signZ = normal.z >= 0.0 ? 1.0 : -1.0;
    float a = -1.0 / (signZ + normal.z);
    float b = normal.x * normal.y * a;
    PrimeOpenPbrOnb result;
    result.tangent = float3(
            1.0 + signZ * normal.x * normal.x * a,
            signZ * b,
            -signZ * normal.x);
    result.bitangent = float3(b, signZ + normal.y * normal.y * a, -normal.y);
    result.normal = normal;
    return result;
}

public float3 primeOpenPbrOnbToWorld(PrimeOpenPbrOnb onb, float3 value) {
    return value.x * onb.tangent + value.y * onb.bitangent + value.z * onb.normal;
}

public float3 primeOpenPbrOnbToLocal(PrimeOpenPbrOnb onb, float3 value) {
    return float3(
            dot(onb.tangent, value),
            dot(onb.bitangent, value),
            dot(onb.normal, value));
}
