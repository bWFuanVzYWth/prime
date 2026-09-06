#language slang 2026
module "service/reconstruct/lambert_receiver.slang";
import "model/material/defaults.slang";
import "service/reconstruct/control.slang";
import "service/reconstruct/nrd/common.slang";
import "policy/runtime/config.slang";
import "state/lambert.slang";
import "contract/lambert.slang";
import "prime_realtime_abi.slang";
import "prime_abi_types.slang";
import "prime_abi.slang";
import "service/reconstruct/lambert_guides.slang";
import "state/lambert_interface.slang";
import "math/lambert_transport.slang";
import "service/camera/projection.slang";

public void primeLambertWriteVisible(uint index, float2 cameraSample, PrimeLambertPayload hit, float fresnel) {
    uint2 pixel = primeLambertPixel(index);
    bool transparent = (hit.flags & PRIME_LAMBERT_DELTA) != 0u && hit.distance >= 0.0;
    if (transparent) hit.albedo = float3(0.0);
    primeLambertWriteGuides(pixel, cameraSample, hit);
    primeImageStore(primeStableRadiance, int2(pixel), float4(0.0, 0.0, 0.0, 1.0));
    if (!transparent) return;
    int2 p = int2(pixel);
    uint material = PRIME_MATERIAL_FAMILY_DIELECTRIC;
    primeImageStore(primeNrdNormalRoughness, p, primeNrdDirectNormalRoughness(hit.normal, 0.0));
    primeImageStore(primeNrdSpecularMaterial, p, float4(float3(fresnel),
            primeWritesNrdShInputs() ? float(material) : 0.0));
    primeImageStore(primeReconstructionControl, p, primePackReconstructionControl(2u, true,
            (primePush.path.y & 0x80000000u) != 0u, hit.motion != 0u));
    if (primeWritesNrdShInputs())
        primeImageStore(primeNrdDisplayPosition, p, float4(hit.previousPosition - primePush.cameraPosition,
                asfloat(material | (hit.motion != 0u ? PRIME_DISPLAY_MOTION_FLAG : 0u))));
}

public void primeLambertWriteVisibleEmission(uint index, float3 radiance) {
    primeImageStore(primeStableRadiance, int2(primeLambertPixel(index)), float4(primeNrdSanitizeRadiance(radiance), 1.0));
}

public void primeLambertWriteReceiver(uint index, PrimeLambertPayload hit, float3 direction,
        bool reflection, bool exhausted) {
    int2 pixel = int2(primeLambertPixel(index));
    bool sh = primeWritesNrdShInputs();
    bool rr = primeTransparentGuideMode() == PRIME_PATH_TRANSPARENT_GUIDE_MODE_DLSS_RR;
    if (exhausted) {
        // Keep the visible-interface fallback for transmission. Never publish a fabricated miss.
        if (reflection && sh) primeImageStore(primeNrdReflectionMaterial, pixel, float4(1.0, 1.0, 1.0, -1.0));
        if (reflection && rr) primeImageStore(primeNrdReflectionPosition, pixel, float4(0.0, 0.0, 0.0, -1.0));
        return;
    }
    float4 plane = primeLambertInterfaceRow(index, 0u);
    float3 normal = primeLambertPaths[index].firstDirection.xyz;
    float3 previousPlane = primeLambertInterfaceRow(index, 1u).xyz;
    float4 previousNormal = primeLambertInterfaceRow(index, 2u);
    bool surface = hit.distance >= 0.0;
    float3 position = surface ? hit.position : plane.xyz + direction * PRIME_NRD_FP16_MAX;
    float3 previous = surface ? hit.previousPosition : position;
    float3 guideNormal = surface ? hit.normal : -direction;
    if (reflection) {
        position = primeLambertMirrorPoint(position, plane.xyz, normal);
        previous = primeLambertMirrorPoint(previous, previousPlane, previousNormal.xyz);
        guideNormal = reflect(guideNormal, normal);
    }
    position -= primePush.cameraPosition;
    previous -= primePush.cameraPosition;
    float distance = surface ? primeNrdSanitizePrimaryDistance(length(position), true) : PRIME_NRD_FP16_MAX;
    float3 albedo = surface ? hit.albedo : float3(1.0);
    if (reflection) {
        if (sh) {
            primeImageStore(primeNrdReflectionMaterial, pixel, float4(albedo, distance));
            primeImageStore(primeNrdReflectionNormalRoughness, pixel, primeNrdDirectNormalRoughness(guideNormal, 1.0));
            primeImageStore(primeNrdReflectionSpecularMaterial, pixel, float4(0.0));
            primeImageStore(primeNrdReflectionPosition, pixel, float4(surface ? previous : reflect(direction, previousNormal.xyz), surface ? 0.0 : 1.0));
            primeImageStore(primeNrdReflectionNoisyDiffuse, pixel, float4(0.0));
            primeImageStore(primeNrdReflectionNoisySpecular, pixel, float4(0.0));
            primeImageStore(primeNrdReflectionDiffuseDirection, pixel, float4(0.0));
            primeImageStore(primeNrdReflectionSpecularDirection, pixel, float4(0.0));
        } else {
            // RR consumes interface-to-reflected-target distance even on the unselected branch.
            primeImageStore(primeNrdNoisySpecular, pixel, float4(0.0, 0.0, 0.0,
                    surface ? primeNrdSanitizeHitDistance(hit.distance) : PRIME_NRD_FP16_MAX));
            if (rr) primeImageStore(primeNrdReflectionPosition, pixel,
                    float4(surface ? previous : reflect(direction, previousNormal.xyz), surface ? 0.0 : 1.0));
        }
        return;
    }
    bool motion = surface && hit.motion != 0u;
    primeImageStore(primeNrdNormalRoughness, pixel, primeNrdDirectNormalRoughness(guideNormal, 1.0));
    float visibleDistance = length(plane.xyz - primePush.cameraPosition);
    primeImageStore(primeNrdMaterial, pixel, float4(albedo, sh ? distance : visibleDistance));
    primeImageStore(primeNrdSpecularMaterial, pixel, float4(sh ? float3(0.0) : float3(plane.w),
            sh ? float(PRIME_MATERIAL_FAMILY_DIELECTRIC) : 0.0));
    // Preserve the visible surface's viewZ/control/display position. Only reconstruction anchors move.
    float w = motion ? -distance - 1.0 : distance;
    primeImageStore(primeNrdPrimaryPosition, pixel, float4(previous, w));
}
