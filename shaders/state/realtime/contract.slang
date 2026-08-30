#language slang 2026
module "state/realtime/contract.slang";

import "math/direction.slang";

import "prime_realtime_abi.slang";
import "prime_abi.slang";
import "prime_abi_types.slang";
import "contract/realtime/guide.slang";
import "service/reconstruct/nrd/common.slang";
import "math/packed_geometry.slang";
import "policy/runtime/config.slang";
import "contract/path.slang";
import "model/realtime/guide.slang";
import "contract/realtime/ordinary.slang";

// Two signal slots belong to each realtime pixel so transparent reflection and transmission
// keep independent reconstruction histories. Normals that affect visibility, MIS or reconstruction
// remain exact f32 vectors across dispatch boundaries.
public struct PrimeWavefrontTransportRecord {
    public float4 physicalOriginAndPreviousBsdfPdf;
    public uint4 sourceAndPathControl;
    public float4 rayDirectionAndDenoiserControl;
    public float4 throughputAndReserved;
    public uint4 medium0;
    public uint4 medium1;
    public float4 previousLightNormal;
};

// 112 bytes of transport are followed by exact queued PSR/previous-position data and the branch
// guide normal/roughness: 144 bytes in total. Visible-interface data is shared once per pixel.
public struct PrimeWavefrontPathRecord {
    public PrimeWavefrontTransportRecord transport;
    public uint4 psrPacked;
    public float4 guideNormalRoughness;
};

[[vk::binding(PRIME_DESCRIPTOR_WAVEFRONT_PATHS, PRIME_RENDERER_DESCRIPTOR_SET)]]
public RWStructuredBuffer<PrimeWavefrontPathRecord> primeWavefrontPaths;

[[vk::binding(PRIME_DESCRIPTOR_WAVEFRONT_QUEUE, PRIME_RENDERER_DESCRIPTOR_SET)]]
public RWStructuredBuffer<uint> primeWavefrontQueue;

public static const uint PRIME_WAVEFRONT_REACHED_SOLID_ANGLE = 2u;
public static const uint PRIME_WAVEFRONT_DIFFUSE_PATH = 4u;
public static const uint PRIME_WAVEFRONT_TRANSPARENT_BRANCH = 8u;
public static const uint PRIME_WAVEFRONT_TRANSMISSION_BRANCH = 16u;
public static const uint PRIME_WAVEFRONT_GUIDE_ENABLED = 32u;
public static const uint PRIME_WAVEFRONT_DIRECTIONAL_GUIDE = 64u;
public static const uint PRIME_WAVEFRONT_PRIMARY_REFLECTION_ONLY = 128u;
public static const uint PRIME_WAVEFRONT_BRANCH_CONTROL_MASK = 0xf8u;
public static const uint PRIME_WAVEFRONT_BOUNCE_SHIFT = 0u;
public static const uint PRIME_WAVEFRONT_ETA_SCALE_SHIFT = 8u;
public static const uint PRIME_WAVEFRONT_ETA_SCALE_MASK = 0xffffu;
public static const uint PRIME_WAVEFRONT_PATH_FLAGS_SHIFT = 24u;
public static const uint PRIME_WAVEFRONT_PATH_FLAGS_MASK =
        PRIME_PATH_PREVIOUS_DISCRETE
        | PRIME_PATH_PREVIOUS_NO_SUN_NEE;
public static const uint PRIME_WAVEFRONT_PRIMARY_BOUNCE_SHIFT = 8u;
public static const uint PRIME_WAVEFRONT_MEDIUM_COUNT_SHIFT = 16u;
// Bits 18..31 are the complete current material-flag domain. Extending material flags beyond
// bit 13 requires a queue ABI migration rather than silent truncation here.
public static const uint PRIME_WAVEFRONT_PRIMARY_FLAGS_SHIFT = 18u;
public static const uint PRIME_WAVEFRONT_BYTE_MASK = 0xffu;
public static const uint PRIME_WAVEFRONT_MEDIUM_COUNT_MASK = 0x3u;
public static const uint PRIME_WAVEFRONT_PRIMARY_FLAGS_MASK = 0x3fffu;
public static const uint PRIME_WAVEFRONT_PSR_CONTROL_MASK = 0x3u;
// The low guide lanes are disjoint from exact medium IOR-source encodings in this word. During
// delta walk fallback means detached; later phases reuse it after abandoning an invalid PSR guide.
public static const uint PRIME_WAVEFRONT_GUIDE_FALLBACK = 0x4u;
public static const uint PRIME_WAVEFRONT_GUIDE_DETACHED =
        PRIME_WAVEFRONT_GUIDE_FALLBACK;
public static const uint PRIME_WAVEFRONT_PRIMARY_MOTION = 1u;

public uint primeWavefrontIndex(uint2 pixel) {
    return pixel.y * primePush.outputExtent.x + pixel.x;
}

public uint primeWavefrontPixelCount() {
    return primePush.outputExtent.x * primePush.outputExtent.y;
}

public uint primeWavefrontQueueCapacity(uint queue) {
    // Either transparent branch can independently defer area work from the same pixel.
    bool wideQueue = queue == PRIME_WAVEFRONT_TRANSPARENT_TRACE_QUEUE_0
            || queue == PRIME_WAVEFRONT_TRANSPARENT_TRACE_QUEUE_1
            || queue == PRIME_WAVEFRONT_AREA_QUEUE;
    // AREA events alias queue zero and may contain both transparent branches.
    wideQueue = wideQueue || queue == PRIME_WAVEFRONT_TRACE_QUEUE_0;
    uint entriesPerPixel = wideQueue
            ? PRIME_WAVEFRONT_QUEUE_ENTRIES_PER_PIXEL
            : 1u;
    return primeWavefrontPixelCount() * entriesPerPixel;
}

public uint primeWavefrontIndex(uint2 pixel, uint branch) {
    return primeWavefrontIndex(pixel) + branch * primeWavefrontPixelCount();
}

public uint2 primeWavefrontPixel(uint pathIndex) {
    uint pixelIndex = pathIndex % primeWavefrontPixelCount();
    return uint2(
            pixelIndex % primePush.outputExtent.x,
            pixelIndex / primePush.outputExtent.x);
}

public uint primeWavefrontQueueCommandWord(uint queue) {
    uint areaWords = primeWavefrontPixelCount()
            * (PRIME_WAVEFRONT_AREA_RECORD_SIZE / 4u);
    return areaWords + queue * (PRIME_WAVEFRONT_QUEUE_COMMAND_STRIDE / 4u);
}

public uint primeWavefrontQueueWord(uint queue, uint entry) {
    uint indexWords = primeWavefrontQueueCommandWord(PRIME_WAVEFRONT_QUEUE_COUNT);
    uint pixelCount = primeWavefrontPixelCount();
    if (queue == PRIME_WAVEFRONT_TRACE_QUEUE_0) {
        return indexWords + entry;
    }
    if (queue == PRIME_WAVEFRONT_TRACE_QUEUE_1) {
        return indexWords + pixelCount + entry;
    }
    if (queue == PRIME_WAVEFRONT_TRANSPARENT_TRACE_QUEUE_1) {
        // Primary is consumed before this transparent ping-pong target becomes reachable.
        return indexWords + 2u * pixelCount + entry;
    }
    if (queue == PRIME_WAVEFRONT_TRANSPARENT_TRACE_QUEUE_0) {
        return indexWords + 4u * pixelCount + entry;
    }
    if (queue == PRIME_WAVEFRONT_AREA_QUEUE) {
        return indexWords + 6u * pixelCount + entry;
    }
    // The area queue occupies both slots 6 and 7. Resolve and the serialized detached-guide
    // queue own the two remaining one-entry-per-pixel regions.
    if (queue == PRIME_WAVEFRONT_TRANSPARENT_RESOLVE_QUEUE) {
        return indexWords + 8u * pixelCount + entry;
    }
    return indexWords + 9u * pixelCount + entry;
}

public uint primeAdditionalSpecularBounces() {
    return primeWavefrontQueue[
            primeWavefrontQueueCommandWord(PRIME_WAVEFRONT_TRACE_QUEUE_0) + 3u]
            & 0xffu;
}

public static const uint PRIME_WAVEFRONT_AREA_GUIDE_WORDS = 8u;

public uint primeWavefrontAreaWord(uint2 pixel, uint component) {
    return primeWavefrontIndex(pixel) * PRIME_WAVEFRONT_AREA_GUIDE_WORDS + component;
}

public uint3 primePackWavefrontPair(float3 first, float3 second) {
    return uint3(
            packHalf2x16(first.xy),
            packHalf2x16(float2(first.z, second.x)),
            packHalf2x16(second.yz));
}

public void primeUnpackWavefrontPair(uint3 packedPair, out float3 first, out float3 second) {
    float2 first01 = unpackHalf2x16ToFloat(packedPair.x);
    float2 first2Second0 = unpackHalf2x16ToFloat(packedPair.y);
    float2 second12 = unpackHalf2x16ToFloat(packedPair.z);
    first = float3(first01, first2Second0.x);
    second = float3(first2Second0.y, second12);
}

public void primeStoreWavefrontArea(uint2 pixel, PrimeDenoiserGuides guides) {
    uint3 radiance = primePackWavefrontPair(
            primeNrdSanitizeRadiance(guides.primaryAreaDiffuse),
            primeNrdSanitizeRadiance(guides.primaryAreaSpecular));
    float3 direction = guides.primaryAreaDirection;
    if (!(dot(direction, direction) > 0.0)) {
        direction = float3(0.0, 1.0, 0.0);
    }
    primeWavefrontQueue[primeWavefrontAreaWord(pixel, 0u)] = radiance.x;
    primeWavefrontQueue[primeWavefrontAreaWord(pixel, 1u)] = radiance.y;
    primeWavefrontQueue[primeWavefrontAreaWord(pixel, 2u)] = radiance.z;
    primeWavefrontQueue[primeWavefrontAreaWord(pixel, 3u)] =
            primePackOctahedralUnitVector(direction);
}

public void primeLoadWavefrontArea(
        uint2 pixel,
        out float3 diffuse,
        out float3 specular,
        out float3 direction) {
    uint3 radiance = uint3(
            primeWavefrontQueue[primeWavefrontAreaWord(pixel, 0u)],
            primeWavefrontQueue[primeWavefrontAreaWord(pixel, 1u)],
            primeWavefrontQueue[primeWavefrontAreaWord(pixel, 2u)]);
    primeUnpackWavefrontPair(radiance, diffuse, specular);
    direction = primeUnpackOctahedralUnitVector(
            primeWavefrontQueue[primeWavefrontAreaWord(pixel, 3u)]);
}

public bool primeWavefrontTransparentBranch(uint control) {
    return (control & PRIME_WAVEFRONT_TRANSPARENT_BRANCH) != 0u;
}

public bool primeWavefrontTransmissionBranch(uint control) {
    return (control & PRIME_WAVEFRONT_TRANSMISSION_BRANCH) != 0u;
}

public bool primeWavefrontGuideEnabled(uint control) {
    return (control & PRIME_WAVEFRONT_GUIDE_ENABLED) != 0u;
}

public bool primeWavefrontDirectionalGuide(uint control) {
    return (control & PRIME_WAVEFRONT_DIRECTIONAL_GUIDE) != 0u;
}

public bool primeWavefrontGuideFallback(uint guideControl) {
    return (guideControl & PRIME_WAVEFRONT_GUIDE_FALLBACK) != 0u;
}

public bool primeWavefrontGuideDetached(uint guideControl) {
    return (guideControl & PRIME_WAVEFRONT_GUIDE_DETACHED) != 0u;
}

public bool primeWavefrontPixelTransparent(uint2 pixel) {
    uint control = asuint(
            primeWavefrontPaths[primeWavefrontIndex(pixel, 0u)]
                    .transport.rayDirectionAndDenoiserControl.w);
    return primeWavefrontTransparentBranch(control);
}

public PrimeOrdinarySample primeLoadWavefrontIntermediate(
        uint2 pixel,
        uint pathIndex,
        PrimeWavefrontTransportRecord record) {
    int2 coordinate = int2(pixel);
    float4 diffuse = primeImageLoad(primeNrdNoisyDiffuse, coordinate);
    float4 specular = primeImageLoad(primeNrdNoisySpecular, coordinate);
    float4 stable = primeImageLoad(primeStableRadiance, coordinate);
    float4 sun = primeImageLoad(primeNrdSunLighting, coordinate);
    float4 position = primeImageLoad(primeNrdPrimaryPosition, coordinate);
    float4 material = primeImageLoad(primeNrdMaterial, coordinate);
    float4 specularMaterial = primeImageLoad(primeNrdSpecularMaterial, coordinate);
    uint denoiserControl =
            asuint(record.rayDirectionAndDenoiserControl.w);

    PrimeOrdinarySample result;
    result.radiance.diffuse = diffuse.rgb;
    result.radiance.specular = specular.rgb;
    result.radiance.stable = stable.rgb;
    result.radiance.unshadowedSun = sun.rgb;
    result.radiance.sunVisibility = sun.a;
    result.guides = primeEmptyDenoiserGuides();
    result.guides.primaryDistance = position.w;
    result.guides.specularHitDistance = specular.a;
    result.guides.diffuseHitDistance = diffuse.a;
    result.guides.sunPenumbra = primeImageLoad(primeNrdSunPenumbra, coordinate).r;
    result.guides.primaryAlbedo = material.rgb;
    result.guides.primaryHitKind = position.w >= 0.0
            ? PRIME_HIT_SURFACE
            : PRIME_HIT_NONE;
    float4 guideNormalRoughness =
            primeWavefrontPaths[pathIndex].guideNormalRoughness;
    result.guides.primaryNormal = guideNormalRoughness.xyz;
    result.guides.primaryMaterialFlags =
            (denoiserControl >> PRIME_WAVEFRONT_PRIMARY_FLAGS_SHIFT)
                    & PRIME_WAVEFRONT_PRIMARY_FLAGS_MASK;
    result.guides.primarySpecularAlbedo = specularMaterial.rgb;
    result.guides.primaryLinearRoughness = guideNormalRoughness.w;
    result.guides.primaryPosition = position.xyz;
    uint4 previous = primeWavefrontPaths[pathIndex].psrPacked;
    result.guides.primaryHasMotion =
            (previous.w & PRIME_WAVEFRONT_PRIMARY_MOTION) != 0u;
    result.guides.primaryPreviousPosition = result.guides.primaryHasMotion
            ? asfloat(previous.xyz)
            : position.xyz;
    if (primeWritesNrdShInputs()) {
        result.guides.diffuseDirection =
                primeRestoreFp16Direction(
                        primeImageLoad(primeNrdDiffuseDirection, coordinate).xyz);
        result.guides.specularDirection =
                primeRestoreFp16Direction(
                        primeImageLoad(primeNrdSpecularDirection, coordinate).xyz);
    }
    primeLoadWavefrontArea(
            pixel,
            result.guides.primaryAreaDiffuse,
            result.guides.primaryAreaSpecular,
            result.guides.primaryAreaDirection);
    return result;
}
