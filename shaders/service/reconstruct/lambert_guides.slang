#language slang 2026
module "service/reconstruct/lambert_guides.slang";
import "prime_abi_types.slang";

import "prime_realtime_abi.slang";
import "prime_abi.slang";
import "contract/lambert.slang";
import "service/reconstruct/control.slang";
import "service/reconstruct/nrd/common.slang";
import "policy/runtime/config.slang";

public void primeLambertWriteGuides(uint2 pixel, float2 cameraSample, PrimeLambertPayload hit) {
    int2 p = int2(pixel);
    bool valid = hit.distance >= 0.0;
    bool motion = valid && hit.motion != 0u;
    bool sh = primeWritesNrdShInputs();
    float3 position = valid ? hit.position - primePush.cameraPosition : float3(0.0);
    float3 previous = motion ? hit.previousPosition - primePush.cameraPosition : position;
    float distance = primeNrdSanitizePrimaryDistance(length(position), valid);
    float3 normal = valid ? hit.normal : float3(0.0, 0.0, 1.0);
    float4 nearPoint = primePush.inverseViewProjection * float4(0.0, 0.0, 1.0, 1.0);
    float4 farPoint = primePush.inverseViewProjection * float4(0.0, 0.0, 0.0, 1.0);
    float3 forward = normalize(farPoint.xyz / farPoint.w - nearPoint.xyz / nearPoint.w);
    primeImageStore(primeNrdViewZ, p, float4(valid ? dot(position, forward) : -1.0));
    primeImageStore(primeNrdMaterial, p, float4(valid ? hit.albedo : float3(0.0), distance));
    primeImageStore(primeNrdSpecularMaterial, p, float4(0.0, 0.0, 0.0, sh ? 0.0 : 1.0));
    primeImageStore(primeNrdNormalRoughness, p, primeNrdDirectNormalRoughness(normal, 1.0));
    // Both backends accept previous geometry plus signed current distance for moving targets.
    primeImageStore(primeNrdPrimaryPosition, p, float4(previous, motion ? -distance - 1.0 : distance));
    primeImageStore(primeReconstructionControl, p, primePackReconstructionControl(0u, valid,
            (primePush.path.y & 0x80000000u) != 0u, motion));
    primeImageStore(primeNrdNoisySpecular, p, float4(0.0));
    primeImageStore(primeNrdSunLighting, p, float4(0.0));
    primeImageStore(primeNrdSunPenumbra, p, float4(PRIME_NRD_FP16_MAX));
    if (sh) {
        primeImageStore(primeNrdSpecularDirection, p, float4(0.0));
        primeImageStore(primeNrdDisplayPosition, p, float4(previous,
                asfloat(motion ? PRIME_DISPLAY_MOTION_FLAG : 0u)));
        // This lane disables the reflection branch before the reconstruction pass reads its inputs.
        primeImageStore(primeNrdReflectionMaterial, p, float4(1.0, 1.0, 1.0, -1.0));
    } else if (primeTransparentGuideMode() == PRIME_PATH_TRANSPARENT_GUIDE_MODE_DLSS_RR) {
        primeImageStore(primeNrdReflectionPosition, p, float4(0.0, 0.0, 0.0, -1.0));
    }
}

