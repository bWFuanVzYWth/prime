#language slang 2026
module "reconstruct/rr_guides.slang";
__exported import "reconstruct/rr_format.slang";

// 80-byte binding 17 uniform ABI. Previous position is relative to the CURRENT scene anchor.
// Projection bases have the same convention as Frame. Jitter is a pixel offset from center.
public struct RrCamera {
    public float4 previousPosition;
    public float4 previousForward;
    public float4 previousRight;
    public float4 previousUp;
    public float4 jitter; // xy current pixel jitter; z history valid (0/1); w reserved
};

public float2 primeRrMotion(RrCamera camera, float2 currentSampleUv, float3 target,
                            bool directional, bool knownPreviousPosition) {
    if (camera.jitter.z == 0.0 || !knownPreviousPosition)
        return float2(PRIME_RR_INVALID_MOTION);
    float3 relative = directional ? target : target - camera.previousPosition.xyz;
    float depth = dot(relative, camera.previousForward.xyz);
    float2 denominator =
            float2(camera.previousForward.w, 1.0) * (depth * camera.previousPosition.w);
    if (!(depth > 1e-6) || any(denominator <= 0.0))
        return float2(PRIME_RR_INVALID_MOTION);
    float2 projected =
            float2(dot(relative, camera.previousRight.xyz), -dot(relative, camera.previousUp.xyz)) /
            denominator;
    // Both projections are unjittered: the traced point projects to center + current jitter.
    // Subtract its actual sample UV to avoid leaking Halton jitter into static motion.
    float2 motion = 0.5 * (projected + 1.0) - currentSampleUv;
    return any(isnan(motion)) || any(isinf(motion)) || any(abs(motion) >= 65504.0)
                   ? float2(PRIME_RR_INVALID_MOTION)
                   : motion;
}
