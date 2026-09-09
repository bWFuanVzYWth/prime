// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

#language slang 2026
module "math/lambert_transport.slang";

public uint primeLambertBounceLimit(uint minimum, uint maximum) { return max(minimum, maximum); }
public bool primeLambertNeedsRoulette(uint completed, uint minimum, uint maximum) {
    return completed >= minimum && completed < primeLambertBounceLimit(minimum, maximum);
}
public bool primeLambertIncludesEmission(uint completed, bool registered) {
    return completed != 1u || !registered;
}
public float primeLambertFresnel(float cosine, bool water) {
    float x = 1.0 - saturate(abs(cosine));
    float f0 = water ? 0.02 : 0.04;
    return f0 + (1.0 - f0) * x*x*x*x*x;
}
public float primeLambertBranchWeight(float fresnel, bool reflection) {
    if (fresnel == 1.0) return reflection ? 1.0 : 0.0;
    if (fresnel == 0.0) return reflection ? 0.0 : 1.0;
    return 2.0 * (reflection ? fresnel : 1.0 - fresnel);
}
public bool primeLambertReflect(float fresnel, float sample) {
    return fresnel == 1.0 || (fresnel > 0.0 && sample < 0.5);
}
public float3 primeLambertMirrorPoint(float3 point, float3 planePoint, float3 normal) {
    return point - 2.0 * dot(point - planePoint, normal) * normal;
}
