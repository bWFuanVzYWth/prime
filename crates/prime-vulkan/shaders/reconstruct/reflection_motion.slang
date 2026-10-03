#language slang 2026
module "reconstruct/reflection_motion.slang";
import "reconstruct/rr_guides.slang";
import "reconstruct/rr_status.slang";

// Post-only operation: complete the dense field without any geometry/BSDF dependencies.
// Delta split pixels already own a deterministic K1 reflection endpoint and are preserved.
public void primeCompleteReflectionMotion(RrCamera camera, uint2 pixel, uint2 extent,
                                          float2 sampleUv, RWTexture2D<float> statusImage,
                                          RWTexture2D<float2> reflectionMotion,
                                          readonly RWTexture2D<float2> primaryMotion, float depth,
                                          float distance) {
    float packed = statusImage[pixel];
    uint status = primeRrGuideStatus(packed);
    if ((status & PRIME_RR_EXPLICIT_REFLECTION) != 0)
        return;
    float2 motion = float2(0.0);
    bool valid = primeRrGuideComplete(packed) &&
                 primeRrDistanceMotion(camera, sampleUv, depth, distance,
                                       distance == 0.0 && depth != PRIME_RR_SKY_DEPTH
                                               ? primaryMotion[pixel]
                                               : float2(0.0),
                                       extent, motion);
    reflectionMotion[pixel] = valid ? motion : float2(0.0);
    if (!valid)
        statusImage[pixel] = float(status | PRIME_RR_REFLECTION_PENDING) / 255.0;
}
