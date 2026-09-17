// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

#language slang 2026
module "math/surface_normal.slang";

public float3 primeOutwardShadingNormal(float3 shadingNormal, float3 geometricNormal) {
    return dot(shadingNormal, geometricNormal) >= 0.0 ? shadingNormal : -shadingNormal;
}

public float3 primeIncidentShadingNormal(float3 shadingNormal, float3 geometricNormal, float3 viewDirection) {
    float3 outward = primeOutwardShadingNormal(shadingNormal, geometricNormal);
    return dot(geometricNormal, viewDirection) >= 0.0 ? outward : -outward;
}
