#language slang 2026
module "phase/post/streamline_input.slang";

import "service/reconstruct/streamline_input.slang";
import "prime_coordinate_contract.slang";

struct PrimeStreamlineInputPushConstants
{
    float4x4 currentClipToWorld;
    float4x4 previousWorldToClip;
    float2 currentJitterPixels;
    uint historyValid;
    uint transmissiveHistoryExact;
};
[[vk::push_constant]]
ConstantBuffer<PrimeStreamlineInputPushConstants> primeStreamlineInputPush;

[[vk::binding(0, 0)]] Texture2D<float> primeStreamlineViewZ;
[[vk::binding(1, 0)]] Texture2D<float4> primeStreamlineHistoryPosition;
[[vk::binding(2, 0)]]
[[vk::image_format("r8ui")]]
RWTexture2D<uint> primeStreamlineReconstructionControl;
[[vk::binding(3, 0)]]
[[vk::image_format("r32f")]]
WTexture2D<float> primeStreamlineDepth;
[[vk::binding(4, 0)]]
[[vk::image_format("rg32f")]]
WTexture2D<float2> primeStreamlineMotion;

static const uint PRIME_STREAMLINE_SURFACE_VALID = 0x04u;
static const uint PRIME_STREAMLINE_HISTORY_VALID = 0x08u;
static const uint PRIME_STREAMLINE_MOTION_VALID = 0x10u;
static const uint PRIME_STREAMLINE_OBJECT_MOTION = 0x20u;
static const uint PRIME_STREAMLINE_MATERIAL_CLASS_MASK = 0x03u;
static const uint PRIME_STREAMLINE_MATERIAL_CLASS_TRANSMISSIVE = 2u;

float3 primeStreamlineRayDirection(float2 screenUv)
{
    float2 clipXY = primeCanonicalUvToClip(screenUv);
    float4 nearPoint = primeStreamlineInputPush.currentClipToWorld
            * float4(clipXY, 1.0, 1.0);
    float4 farPoint = primeStreamlineInputPush.currentClipToWorld
            * float4(clipXY, 0.0, 1.0);
    return normalize(farPoint.xyz / farPoint.w - nearPoint.xyz / nearPoint.w);
}

bool primeStreamlinePreviousUv(float3 target, bool directional, out float2 previousUv)
{
    float4 previousClip = primeStreamlineInputPush.previousWorldToClip
            * float4(target, directional ? 0.0 : 1.0);
    return primeStreamlinePreviousUv(previousClip, previousUv);
}

public void primePhase(uint3 dispatchThreadId : SV_DispatchThreadID)
{
    uint2 pixel = dispatchThreadId.xy;
    uint2 extent;
    primeStreamlineDepth.GetDimensions(extent.x, extent.y);
    if (any(pixel >= extent)) return;
    uint control = primeStreamlineReconstructionControl[pixel];
    bool surfaceValid = (control & PRIME_STREAMLINE_SURFACE_VALID) != 0u;
    bool historyValid = primeStreamlineHistoryValid(
            control,
            PRIME_STREAMLINE_HISTORY_VALID,
            primeStreamlineInputPush.historyValid != 0u);
    float viewZ = primeStreamlineViewZ.Load(int3(pixel, 0));
    float2 currentSampleUv = (float2(pixel) + float2(0.5)
            + primeStreamlineInputPush.currentJitterPixels) / float2(extent);
    float3 rayDirection = primeStreamlineRayDirection(currentSampleUv);
    float3 target = rayDirection;
    bool directional = !surfaceValid;
    bool valid = historyValid;
    if (surfaceValid) {
        float3 cameraForward = primeStreamlineRayDirection(float2(0.5));
        float3 currentPosition;
        valid = valid && primeStreamlineVisiblePosition(
                rayDirection, cameraForward, viewZ, currentPosition);
        if (valid) {
            bool objectMotion = (control & PRIME_STREAMLINE_OBJECT_MOTION) != 0u;
            bool transmissive = (control & PRIME_STREAMLINE_MATERIAL_CLASS_MASK)
                    == PRIME_STREAMLINE_MATERIAL_CLASS_TRANSMISSIVE;
            valid = primeStreamlineVisibleHistoryPosition(
                    objectMotion,
                    !transmissive
                            || primeStreamlineInputPush.transmissiveHistoryExact != 0u,
                    currentPosition,
                    primeStreamlineHistoryPosition.Load(
                            int3(pixel, 0)).xyz,
                    target);
        }
    }
    float2 previousUv;
    valid = valid && primeStreamlinePreviousUv(target, directional, previousUv);
    float2 motion = valid
            ? primeCanonicalVisibleMotionUv(previousUv, currentSampleUv)
            : float2(0.0);
    if (any(isnan(motion)) || any(isinf(motion))) {
        motion = float2(0.0);
        valid = false;
    }
    primeStreamlineDepth.Store(
            pixel,
            surfaceValid ? primeStreamlineReversedDepth(viewZ) : 0.0);
    primeStreamlineMotion.Store(pixel, primeStreamlineCanonicalMotion(motion));
    primeStreamlineReconstructionControl[pixel] = valid
            ? control | PRIME_STREAMLINE_MOTION_VALID
            : control & ~PRIME_STREAMLINE_MOTION_VALID;
}
