#language slang 2026
module "service/reconstruct/streamline_input.slang";

import "service/reconstruct/fsr_input.slang";
import "service/reconstruct/nrd/common.slang";
import "prime_coordinate_contract.slang";

public float primeStreamlineReversedDepth(float viewZ) {
    return primeFsrReversedInfiniteDepth(viewZ);
}

public float2 primeStreamlineCanonicalMotion(float2 canonicalMotion) {
    return canonicalMotion;
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

public bool primeStreamlineVisibleHistoryPosition(
        bool objectMotion,
        bool historyPositionAvailable,
        float3 currentPosition,
        float3 historyPosition,
        out float3 targetPosition) {
    targetPosition = currentPosition;
    if (!objectMotion) {
        return primeNrdIsFinite(currentPosition);
    }
    if (!historyPositionAvailable) {
        return false;
    }
    targetPosition = historyPosition;
    return primeNrdIsFinite(historyPosition);
}

public bool primeStreamlinePreviousUv(float4 previousClip, out float2 previousUv) {
    previousUv = float2(0.0);
    if (!(previousClip.w > 1.0e-6) || !primeNrdIsFinite(previousClip)) {
        return false;
    }
    previousUv = primeCanonicalClipToUv(previousClip.xy / previousClip.w);
    return primeNrdIsFinite(previousUv);
}
