#language slang 2026
module "service/reconstruct/streamline_input.slang";

import "service/reconstruct/fsr_input.slang";
import "service/reconstruct/nrd/common.slang";

public float primeStreamlineReversedDepth(float viewZ) {
    return primeFsrReversedInfiniteDepth(viewZ);
}

public float2 primeStreamlineTopLeftMotion(float2 sourceMotion) {
    return float2(sourceMotion.x, -sourceMotion.y);
}

public bool primeStreamlineHistoryValid(
        uint control,
        uint historyMask,
        bool localHistoryValid) {
    return localHistoryValid && (control & historyMask) != 0u;
}

public bool primeStreamlineVisiblePosition(
        float3 rayDirection,
        float3 cameraForward,
        float viewZ,
        out float3 position) {
    position = float3(0.0);
    float denominator = dot(rayDirection, cameraForward);
    bool valid = primeNrdIsFinite(rayDirection)
            && primeNrdIsFinite(cameraForward)
            && primeNrdIsFinite(viewZ)
            && viewZ > 0.0
            && primeNrdIsFinite(denominator)
            && denominator > 1.0e-6;
    if (valid) {
        position = rayDirection * (viewZ / denominator);
        valid = primeNrdIsFinite(position);
        if (!valid) {
            position = float3(0.0);
        }
    }
    return valid;
}

public bool primeStreamlinePreviousUv(float4 previousClip, out float2 previousUv) {
    previousUv = float2(0.0);
    if (!(previousClip.w > 1.0e-6) || !primeNrdIsFinite(previousClip)) {
        return false;
    }
    previousUv = previousClip.xy / previousClip.w
            * float2(0.5, -0.5) + float2(0.5);
    return primeNrdIsFinite(previousUv);
}
