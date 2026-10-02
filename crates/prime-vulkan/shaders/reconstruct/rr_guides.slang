#language slang 2026
module "reconstruct/rr_guides.slang";
__exported import "reconstruct/rr_format.slang";

// 144-byte binding 17 uniform ABI. Both cameras use the CURRENT scene anchor.
// Projection bases have the same convention as Frame. Jitter is a pixel offset from center.
public struct RrCamera {
    public float4 previousPosition;
    public float4 previousForward;
    public float4 previousRight;
    public float4 previousUp;
    public float4 jitter;          // xy current pixel jitter; z history valid (0/1); w reserved
    public float4 currentPosition; // xyz position; w tan(vertical FOV / 2)
    public float4 currentForward;  // xyz forward; w aspect
    public float4 currentRight;
    public float4 currentUp;
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

// The scalar helper's invalid marker is local math only. NGX DLSSD does not consume SL's
// motionVectorsInvalidValue declaration. SDK images contain finite input-pixel motion;
// a failed projection is separately handled by the engine's completion state.
public bool primeRrMotionPixels(RrCamera camera, float2 sampleUv, float3 target, bool directional,
                                bool knownPreviousPosition, uint2 extent, out float2 motion) {
    motion = float2(0.0);
    if (!knownPreviousPosition)
        return false;
    if (camera.jitter.z == 0.0)
        return true; // Whole viewport is reset; no previous-frame correspondence is consumed.
    float2 value = primeRrMotion(camera, sampleUv, target, directional, true);
    if (any(value == PRIME_RR_INVALID_MOTION))
        return false;
    value *= float2(extent);
    if (any(isnan(value)) || any(isinf(value)) || any(abs(value) >= 65504.0))
        return false;
    motion = value;
    return true;
}

public float3 primeRrCurrentDirection(RrCamera camera, float2 sampleUv) {
    float2 screen = sampleUv * 2.0 - 1.0;
    return normalize(camera.currentForward.xyz +
                     camera.currentRight.xyz *
                             (screen.x * camera.currentForward.w * camera.currentPosition.w) -
                     camera.currentUp.xyz * (screen.y * camera.currentPosition.w));
}

// Ordinary/rough reflection retains the existing sampled first-segment distance contract.
// Unfold its local reflection into a point on the view ray. For a planar ideal reflector
// this is the reflected virtual point; rough/curved reflection uses a local distance proxy.
// Zero distance reduces to the primary point, and sky has no translational parallax.
public bool primeRrDistanceMotion(RrCamera camera, float2 sampleUv, float depth, float distance,
                                  uint2 extent, out float2 motion) {
    float3 direction = primeRrCurrentDirection(camera, sampleUv);
    if (depth == PRIME_RR_SKY_DEPTH)
        return primeRrMotionPixels(camera, sampleUv, direction, true, true, extent, motion);
    motion = float2(0.0);
    float cosine = dot(direction, camera.currentForward.xyz);
    if (!(depth > 0.0) || !(cosine > 0.0) || isnan(depth) || isinf(depth) || isnan(distance) ||
        isinf(distance) || distance < 0.0)
        return false;
    float3 point = camera.currentPosition.xyz + direction * (depth / cosine + distance);
    return primeRrMotionPixels(camera, sampleUv, point, false, true, extent, motion);
}
