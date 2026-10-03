#language slang 2026
module "reconstruct/visible_guides.slang";

// FG consumes the actual first visible surface, independently of RR's unfolded PSR proxy.
// Matches reconstruction_history::projection's positive-Z, non-inverted Vulkan depth.
public float primeVisibleDeviceDepth(float viewZ) {
    const float nearPlane = 0.01, farPlane = 1000000.0;
    if (!(viewZ > 0.0) || isnan(viewZ) || isinf(viewZ))
        return 1.0;
    float a = farPlane / (farPlane - nearPlane);
    return saturate(a - nearPlane * a / viewZ);
}
