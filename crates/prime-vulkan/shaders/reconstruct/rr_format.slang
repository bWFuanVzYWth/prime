#language slang 2026
module "reconstruct/rr_format.slang";
public static const float PRIME_RR_INVALID_MOTION = -65504.0;
public static const float PRIME_RR_SKY_DEPTH = 3.402823466e38f;

public float3 primeRrFiniteHalfColor(float3 color) {
    return float3(isnan(color.x) || isinf(color.x) ? 0.0 : clamp(color.x, 0.0, 65504.0),
                  isnan(color.y) || isinf(color.y) ? 0.0 : clamp(color.y, 0.0, 65504.0),
                  isnan(color.z) || isinf(color.z) ? 0.0 : clamp(color.z, 0.0, 65504.0));
}
