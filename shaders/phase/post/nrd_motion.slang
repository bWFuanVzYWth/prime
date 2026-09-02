#language slang 2026
module "phase/post/nrd_motion.slang";

import "service/image/access.slang";

import "model/material/defaults.slang";
import "service/reconstruct/fsr_input.slang";
import "service/reconstruct/nrd/common.slang";
import "prime_nrd_motion_contract.slang";
import "prime_coordinate_contract.slang";

[[vk::binding(0, 0)]] [[vk::image_format("rgba16f")]] writeonly RWTexture2D<float4> primeMotion;
[[vk::binding(1, 0)]] [[vk::image_format("r32f")]] RWTexture2D<float4> primeViewZ;
[[vk::binding(2, 0)]] [[vk::image_format("rgba32f")]] readonly RWTexture2D<float4> primePrimaryPosition;
[[vk::binding(3, 0)]] [[vk::image_format("r32f")]] writeonly RWTexture2D<float4> primeFsrDepth;
[[vk::binding(4, 0)]] [[vk::image_format("rgba16f")]] RWTexture2D<float4> primeNoisyDiffuse;
[[vk::binding(5, 0)]] [[vk::image_format("rgba16f")]] RWTexture2D<float4> primeNoisySpecular;
[[vk::binding(6, 0)]] [[vk::image_format("rgba32f")]] RWTexture2D<float4> primeNormalRoughness;
[[vk::binding(7, 0)]] [[vk::image_format("rgba16f")]] RWTexture2D<float4> primeMaterial;
[[vk::binding(8, 0)]] [[vk::image_format("rgba16f")]] RWTexture2D<float4> primeSpecularMaterial;
[[vk::binding(9, 0)]] [[vk::image_format("rgba16f")]] RWTexture2D<float4> primeDiffuseDirection;
[[vk::binding(10, 0)]] [[vk::image_format("rgba16f")]] RWTexture2D<float4> primeSpecularDirection;

[[vk::binding(11, 0)]] [[vk::image_format("rgba16f")]] writeonly RWTexture2D<float4> primeReflectionMotion;
[[vk::binding(12, 0)]] [[vk::image_format("r32f")]] RWTexture2D<float4> primeReflectionViewZ;
[[vk::binding(13, 0)]] [[vk::image_format("rgba32f")]] readonly RWTexture2D<float4> primeReflectionPosition;
[[vk::binding(14, 0)]] [[vk::image_format("rgba16f")]] RWTexture2D<float4> primeReflectionNoisyDiffuse;
[[vk::binding(15, 0)]] [[vk::image_format("rgba16f")]] RWTexture2D<float4> primeReflectionNoisySpecular;
[[vk::binding(16, 0)]] [[vk::image_format("rgba32f")]] RWTexture2D<float4> primeReflectionNormalRoughness;
[[vk::binding(17, 0)]] [[vk::image_format("rgba16f")]] RWTexture2D<float4> primeReflectionMaterial;
[[vk::binding(18, 0)]] [[vk::image_format("rgba16f")]] RWTexture2D<float4> primeReflectionSpecularMaterial;
[[vk::binding(19, 0)]] [[vk::image_format("rgba16f")]] RWTexture2D<float4> primeReflectionDiffuseDirection;
[[vk::binding(20, 0)]] [[vk::image_format("rgba16f")]] RWTexture2D<float4> primeReflectionSpecularDirection;
[[vk::binding(21, 0)]] [[vk::image_format("rgba32f")]] readonly RWTexture2D<float4> primeDisplayPosition;
[[vk::binding(22, 0)]] [[vk::image_format("rgba16f")]] writeonly RWTexture2D<float4> primeFsrMotion;
[[vk::binding(23, 0)]] [[vk::image_format("r8ui")]] RWTexture2D<uint> primeReconstructionControl;

static const float PRIME_MOTION_SKY_VIEW_Z = 65504.0;
static const uint PRIME_MOTION_SURFACE_VALID = 0x04u;
static const uint PRIME_MOTION_HISTORY_VALID = 0x08u;
static const uint PRIME_MOTION_VECTOR_VALID = 0x10u;

float3 primeMotionRayDirection(float2 screenUv) {
    float2 clipXY = primeCanonicalUvToClip(screenUv);
    float4 nearPoint = primeMotionPush.currentClipToWorld * float4(clipXY, 1.0, 1.0);
    float4 farPoint = primeMotionPush.currentClipToWorld * float4(clipXY, 0.0, 1.0);
    return normalize(farPoint.xyz / farPoint.w - nearPoint.xyz / nearPoint.w);
}

float2 primeMotionSampleUv(int2 pixel, int2 extent) {
    return (float2(pixel) + float2(0.5) + primeMotionPush.currentJitterPixels)
            / float2(extent);
}

float4 primeLoadMaterial(int2 pixel, bool reflection) {
    return reflection
            ? primeImageLoad(primeReflectionMaterial, pixel)
            : primeImageLoad(primeMaterial, pixel);
}

float4 primeLoadSpecularMaterial(int2 pixel, bool reflection) {
    return reflection
            ? primeImageLoad(primeReflectionSpecularMaterial, pixel)
            : primeImageLoad(primeSpecularMaterial, pixel);
}

float4 primeLoadPosition(int2 pixel, bool reflection) {
    return reflection
            ? primeReadonlyImageLoad(primeReflectionPosition, pixel)
            : primeReadonlyImageLoad(primePrimaryPosition, pixel);
}

float4 primeLoadDiffuse(int2 pixel, bool reflection) {
    return reflection
            ? primeImageLoad(primeReflectionNoisyDiffuse, pixel)
            : primeImageLoad(primeNoisyDiffuse, pixel);
}

float4 primeLoadSpecular(int2 pixel, bool reflection) {
    return reflection
            ? primeImageLoad(primeReflectionNoisySpecular, pixel)
            : primeImageLoad(primeNoisySpecular, pixel);
}

float4 primeLoadNormalRoughness(int2 pixel, bool reflection) {
    return reflection
            ? primeImageLoad(primeReflectionNormalRoughness, pixel)
            : primeImageLoad(primeNormalRoughness, pixel);
}

float3 primeLoadDiffuseDirection(int2 pixel, bool reflection) {
    return reflection
            ? primeImageLoad(primeReflectionDiffuseDirection, pixel).xyz
            : primeImageLoad(primeDiffuseDirection, pixel).xyz;
}

float3 primeLoadSpecularDirection(int2 pixel, bool reflection) {
    return reflection
            ? primeImageLoad(primeReflectionSpecularDirection, pixel).xyz
            : primeImageLoad(primeSpecularDirection, pixel).xyz;
}

void primeStoreMotion(int2 pixel, bool reflection, float4 value) {
    if (reflection) primeWriteImageStore(primeReflectionMotion, pixel, value);
    else primeWriteImageStore(primeMotion, pixel, value);
}

void primeStoreViewZ(int2 pixel, bool reflection, float value) {
    if (reflection) primeImageStore(primeReflectionViewZ, pixel, float4(value));
    else primeImageStore(primeViewZ, pixel, float4(value));
}

void primeStoreDiffuse(int2 pixel, bool reflection, float4 value) {
    if (reflection) primeImageStore(primeReflectionNoisyDiffuse, pixel, value);
    else primeImageStore(primeNoisyDiffuse, pixel, value);
}

void primeStoreSpecular(int2 pixel, bool reflection, float4 value) {
    if (reflection) primeImageStore(primeReflectionNoisySpecular, pixel, value);
    else primeImageStore(primeNoisySpecular, pixel, value);
}

void primeStoreNormal(int2 pixel, bool reflection, float4 value) {
    if (reflection) primeImageStore(primeReflectionNormalRoughness, pixel, value);
    else primeImageStore(primeNormalRoughness, pixel, value);
}

void primeStoreMaterial(int2 pixel, bool reflection, float4 value) {
    if (reflection) primeImageStore(primeReflectionMaterial, pixel, value);
    else primeImageStore(primeMaterial, pixel, value);
}

void primeStoreSpecularMaterial(int2 pixel, bool reflection, float4 value) {
    if (reflection) primeImageStore(primeReflectionSpecularMaterial, pixel, value);
    else primeImageStore(primeSpecularMaterial, pixel, value);
}

void primeStoreDiffuseDirection(int2 pixel, bool reflection, float4 value) {
    if (reflection) primeImageStore(primeReflectionDiffuseDirection, pixel, value);
    else primeImageStore(primeDiffuseDirection, pixel, value);
}

void primeStoreSpecularDirection(int2 pixel, bool reflection, float4 value) {
    if (reflection) primeImageStore(primeReflectionSpecularDirection, pixel, value);
    else primeImageStore(primeSpecularDirection, pixel, value);
}

void primeClearBranch(int2 pixel, bool reflection) {
    primeStoreDiffuse(pixel, reflection, float4(0.0));
    primeStoreSpecular(pixel, reflection, float4(0.0));
    primeStoreDiffuseDirection(pixel, reflection, float4(0.0));
    primeStoreSpecularDirection(pixel, reflection, float4(0.0));
    primeStoreNormal(pixel, reflection, float4(
            0.0, 0.0, 1.0, PRIME_DEFAULT_REFERENCE_LINEAR_ROUGHNESS));
    primeStoreViewZ(pixel, reflection, PRIME_MOTION_SKY_VIEW_Z);
    primeStoreMotion(pixel, reflection, float4(0.0));
    primeStoreMaterial(pixel, reflection, float4(1.0, 1.0, 1.0, -1.0));
    primeStoreSpecularMaterial(pixel, reflection, float4(1.0, 1.0, 1.0, 0.0));
}

void primePrepareBranch(
        int2 pixel,
        int2 extent,
        float2 currentSampleUv,
        float3 cameraForward,
        bool historyValid,
        bool reflection) {
    float4 rawMaterial = primeLoadMaterial(pixel, reflection);
    float primaryDistance = rawMaterial.a;
    if (!primeNrdIsFinite(primaryDistance) || primaryDistance < 0.0) {
        primeClearBranch(pixel, reflection);
        return;
    }

    float4 rawPosition = primeLoadPosition(pixel, reflection);
    float3 position = rawPosition.xyz;
    bool directional = reflection && rawPosition.w > 0.5;
    bool primaryHasMotion = !reflection && rawPosition.w < 0.0;
    // A collapsed reflection target lies on the current pixel ray. Its position image carries
    // previous virtual geometry for reprojection, so current depth must come from the independent
    // path-distance guide instead of that previous point.
    float currentViewZ = directional || reflection || primaryHasMotion
            ? dot(primeMotionRayDirection(currentSampleUv) * primaryDistance, cameraForward)
            : dot(position, cameraForward);
    currentViewZ = primeNrdIsFinite(currentViewZ) && currentViewZ > 0.0
            ? min(currentViewZ, PRIME_MOTION_SKY_VIEW_Z)
            : PRIME_MOTION_SKY_VIEW_Z;

    // Finish matrix work before loading material and lighting vectors. Position and clip
    // temporaries then die before demodulation, keeping the two largest live ranges disjoint.
    float4 previousClip = primeMotionPush.previousWorldToClip
            * float4(position, directional ? 0.0 : 1.0);
    bool validPreviousClip = historyValid
            && previousClip.w > 1.0e-6
            && !any(isnan(previousClip)) && !any(isinf(previousClip));
    float2 previousUv = float2(0.0);
    if (validPreviousClip) {
        previousUv = primeCanonicalClipToUv(previousClip.xy / previousClip.w);
        validPreviousClip = !any(isnan(previousUv)) && !any(isinf(previousUv));
    }
    if (validPreviousClip) {
        primeStoreMotion(pixel, reflection, float4(primeNrdSanitizeMotion(float3(
                primeCanonicalVisibleMotionUv(previousUv, currentSampleUv)
                        * float2(extent),
                directional ? 0.0 : previousClip.w - currentViewZ)), 0.0));
    } else {
        primeStoreMotion(pixel, reflection, float4(0.0));
    }
    float4 rawSpecularMaterial = primeLoadSpecularMaterial(pixel, reflection);
    float3 normal;
    float roughness;
    primeNrdReadNormalRoughness(
            primeLoadNormalRoughness(pixel, reflection), normal, roughness);
    // Raygen owns this private pre-NRD contract: these albedos, hit distances, normals and
    // direction moments are already finite and range-limited. Revalidating them here doubles the
    // full-screen scalar work and extends every raw vector's lifetime.
    float3 diffuseAlbedo = rawMaterial.rgb;
    float3 specularAlbedo = rawSpecularMaterial.rgb;
    float4 rawDiffuse = primeLoadDiffuse(pixel, reflection);
    float4 rawSpecular = primeLoadSpecular(pixel, reflection);
    float diffuseHitT = rawDiffuse.a;
    float specularHitT = rawSpecular.a;
    float3 diffuse = primeNrdDemodulateSanitized(rawDiffuse.rgb, diffuseAlbedo);
    float3 specular = primeNrdDemodulateSanitized(rawSpecular.rgb, specularAlbedo);
    primeNrdClampSanitizedRadiancePair(diffuse, specular, PRIME_NRD_FP16_MAX);
    primeStoreDiffuse(pixel, reflection, primeNrdPackSanitizedRadianceAndHitDistance(
            diffuse,
            diffuseHitT <= 0.0 ? 0.0
                    : primeNrdNormalizedSanitizedHitDistance(
                            diffuseHitT, currentViewZ, 1.0)));
    float3 diffuseDirection = primeLoadDiffuseDirection(pixel, reflection);
    primeStoreDiffuseDirection(pixel, reflection, float4(
            diffuseDirection * primeNrdY(diffuse), 0.0));
    primeStoreSpecular(pixel, reflection, primeNrdPackSanitizedRadianceAndHitDistance(
            specular,
            specularHitT <= 0.0 ? 0.0
                    : primeNrdNormalizedSanitizedHitDistance(
                            specularHitT, currentViewZ, roughness)));
    float3 specularDirection = primeLoadSpecularDirection(pixel, reflection);
    primeStoreSpecularDirection(pixel, reflection, float4(
            specularDirection * primeNrdY(specular), 0.0));
    primeStoreViewZ(pixel, reflection, currentViewZ);
    primeStoreSpecularMaterial(
            pixel, reflection, float4(specularAlbedo, rawSpecularMaterial.a));
}

bool primePrepareFsrGuide(
        int2 pixel,
        int2 extent,
        float3 currentPosition,
        float3 previousPosition,
        bool hasMotion,
        bool surfaceValid,
        bool historyValid,
        float2 currentSampleUv,
        float3 cameraForward) {
    if (!surfaceValid) {
        float3 rayDirection = primeMotionRayDirection(currentSampleUv);
        float4 previousClip = primeMotionPush.previousWorldToClip * float4(rayDirection, 0.0);
        float2 motion = float2(0.0);
        bool valid = historyValid
                && previousClip.w > 1.0e-6
                && !any(isnan(previousClip)) && !any(isinf(previousClip));
        if (valid) {
            float2 previousUv = primeCanonicalClipToUv(
                    previousClip.xy / previousClip.w);
            valid = !any(isnan(previousUv)) && !any(isinf(previousUv));
            if (valid) {
                motion = primeFsrNormalizedMotion(previousUv, currentSampleUv);
            }
        }
        primeWriteImageStore(primeFsrMotion, pixel, float4(motion, 0.0, 0.0));
        primeWriteImageStore(primeFsrDepth, pixel, float4(0.0));
        return valid;
    }

    float viewZ = primeFsrSurfaceViewZ(currentPosition, cameraForward);
    primeWriteImageStore(primeFsrDepth, pixel, float4(primeFsrReversedInfiniteDepth(viewZ)));
    float4 previousClip = primeMotionPush.previousWorldToClip
            * float4(hasMotion ? previousPosition : currentPosition, 1.0);
    float2 motion = float2(0.0);
    bool valid = historyValid
            && previousClip.w > 1.0e-6
            && !any(isnan(previousClip)) && !any(isinf(previousClip));
    if (valid) {
        float2 previousUv = primeCanonicalClipToUv(
                previousClip.xy / previousClip.w);
        valid = !any(isnan(previousUv)) && !any(isinf(previousUv));
        if (valid) {
            motion = primeFsrNormalizedMotion(previousUv, currentSampleUv);
        }
    }
    primeWriteImageStore(primeFsrMotion, pixel, float4(motion, 0.0, 0.0));
    return valid;
}

public void primePhase(uint3 dispatchThreadId : SV_DispatchThreadID, uint groupIndex : SV_GroupIndex)
{
    int2 pixel = int2(dispatchThreadId.xy);
    int2 extent = primeImageSize(primeViewZ);
    if (any(pixel >= extent)) return;
    uint reconstructionControl = primeReconstructionControl[pixel];
    bool surfaceValid = (reconstructionControl & PRIME_MOTION_SURFACE_VALID) != 0u;
    bool historyValid = (reconstructionControl & PRIME_MOTION_HISTORY_VALID) != 0u;
    float2 currentSampleUv = primeMotionSampleUv(pixel, extent);
    float visibleViewZ = primeImageLoad(primeViewZ, pixel).x;
    float4 display = primeReadonlyImageLoad(primeDisplayPosition, pixel);
    bool displayHasMotion = (asuint(display.w)
            & PRIME_DISPLAY_MOTION_FLAG) != 0u;
    bool sky = !surfaceValid;
    // Surface branches and the FSR depth guide share the same center ray. Sky only needs its
    // per-pixel direction, avoiding an otherwise unused matrix reconstruction.
    float3 cameraForward = sky
            ? float3(0.0, 0.0, 1.0)
            : primeMotionRayDirection(float2(0.5));
    float3 currentDisplayPosition = float3(0.0);
    if (surfaceValid) {
        float3 rayDirection = primeMotionRayDirection(currentSampleUv);
        float denominator = dot(rayDirection, cameraForward);
        surfaceValid = visibleViewZ > 0.0
                && primeNrdIsFinite(visibleViewZ)
                && denominator > 1.0e-6
                && primeNrdIsFinite(denominator);
        if (surfaceValid) {
            currentDisplayPosition = rayDirection * (visibleViewZ / denominator);
        }
    }
    primePrepareBranch(
            pixel,
            extent,
            currentSampleUv,
            cameraForward,
            historyValid,
            false);
    primePrepareBranch(
            pixel,
            extent,
            currentSampleUv,
            cameraForward,
            historyValid,
            true);
    bool visibleMotionValid = primePrepareFsrGuide(
            pixel,
            extent,
            currentDisplayPosition,
            displayHasMotion ? display.xyz : currentDisplayPosition,
            displayHasMotion,
            surfaceValid,
            historyValid,
            currentSampleUv,
            cameraForward);
    reconstructionControl = surfaceValid
            ? reconstructionControl
            : reconstructionControl & ~PRIME_MOTION_SURFACE_VALID;
    primeReconstructionControl[pixel] = visibleMotionValid
            ? reconstructionControl | PRIME_MOTION_VECTOR_VALID
            : reconstructionControl & ~PRIME_MOTION_VECTOR_VALID;
}

