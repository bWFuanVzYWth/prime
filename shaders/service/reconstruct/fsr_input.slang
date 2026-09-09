// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

#language slang 2026
module "service/reconstruct/fsr_input.slang";

import "service/reconstruct/nrd/common.slang";
import "prime_fsr_contract.slang";

public float primeFsrSurfaceViewZ(float3 position, float3 cameraForward) {
    float viewZ = dot(position, cameraForward);
    return primeNrdIsFinite(viewZ) && viewZ > 0.0
            ? min(viewZ, PRIME_NRD_FP16_MAX)
            : PRIME_NRD_FP16_MAX;
}
public float primeFsrReversedInfiniteDepth(float viewZ) {
    return primeNrdIsFinite(viewZ) && viewZ > 0.0
            ? clamp(PRIME_FSR_NEAR_PLANE / viewZ, 0.0, 1.0)
            : 0.0;
}

public float2 primeFsrNormalizedMotion(float2 previousUv, float2 currentUv) {
    float2 motion = previousUv - currentUv;
    return primeNrdIsFinite(motion)
            ? clamp(motion, float2(-1.0), float2(1.0))
            : float2(0.0);
}

public float primeFsrSanitizeMask(float value) {
    return primeNrdSanitizeUnit(value, 0.0);
}
