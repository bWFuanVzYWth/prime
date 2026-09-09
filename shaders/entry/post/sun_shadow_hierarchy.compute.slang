// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

#language slang 2026
module "entry/post/sun_shadow_hierarchy.compute.slang";

import "math/sun_shadow_hierarchy.slang";

struct AtmospherePushConstants
{
    float4x4 inverseViewProjection;
    float eyeRadiusKm;
    float maximumAerialDistanceKm;
    int hierarchyLevel;
    float padding;
    float4 sunDirectionIntensity;
    float4 shadowDirectionBank;
    float4 shadowCameraEnabled;
};

[[vk::binding(8, 0)]] [[vk::image_format("r32f")]]
readonly RWTexture2D<float> sunShadowDepth0;
[[vk::binding(9, 0)]] [[vk::image_format("r32f")]]
readonly RWTexture2D<float> sunShadowDepth1;
[[vk::binding(10, 0)]] [[vk::image_format("r32f")]]
readonly RWTexture2D<float> sunShadowDepth2;
[[vk::binding(11, 0)]] [[vk::image_format("r32f")]]
readonly RWTexture2D<float> sunShadowDepth3;
[[vk::binding(12, 0)]] [[vk::image_format("r32f")]]
readonly RWTexture2D<float> sunShadowDepth4;
[[vk::binding(13, 0)]] [[vk::image_format("r32f")]]
readonly RWTexture2D<float> sunShadowDepth5;
[[vk::binding(14, 0)]] [[vk::image_format("r32f")]]
readonly RWTexture2D<float> sunShadowDepth6;
[[vk::binding(15, 0)]] [[vk::image_format("r32f")]]
readonly RWTexture2D<float> sunShadowDepth7;
[[vk::binding(16, 0)]] [[vk::image_format("r32f")]]
readonly RWTexture2D<float> sunShadowDepth8;
[[vk::binding(17, 0)]] [[vk::image_format("r32f")]]
readonly RWTexture2D<float> sunShadowDepth9;
[[vk::binding(18, 0)]] [[vk::image_format("rg32f")]]
RWTexture2D<float2> sunShadowHierarchy0;
[[vk::binding(19, 0)]] [[vk::image_format("rg32f")]]
RWTexture2D<float2> sunShadowHierarchy1;
[[vk::binding(20, 0)]] [[vk::image_format("rg32f")]]
RWTexture2D<float2> sunShadowHierarchy2;
[[vk::binding(21, 0)]] [[vk::image_format("rg32f")]]
RWTexture2D<float2> sunShadowHierarchy3;
[[vk::binding(22, 0)]] [[vk::image_format("rg32f")]]
RWTexture2D<float2> sunShadowHierarchy4;
[[vk::push_constant]] ConstantBuffer<AtmospherePushConstants> atmospherePush;

float sunShadowDepth(int imageIndex, int2 coordinate)
{
    if (imageIndex == 0) return sunShadowDepth0.Load(coordinate);
    if (imageIndex == 1) return sunShadowDepth1.Load(coordinate);
    if (imageIndex == 2) return sunShadowDepth2.Load(coordinate);
    if (imageIndex == 3) return sunShadowDepth3.Load(coordinate);
    if (imageIndex == 4) return sunShadowDepth4.Load(coordinate);
    if (imageIndex == 5) return sunShadowDepth5.Load(coordinate);
    if (imageIndex == 6) return sunShadowDepth6.Load(coordinate);
    if (imageIndex == 7) return sunShadowDepth7.Load(coordinate);
    if (imageIndex == 8) return sunShadowDepth8.Load(coordinate);
    return sunShadowDepth9.Load(coordinate);
}

void hierarchyStore(int cascade, int2 coordinate, float2 value)
{
    if (cascade == 0) sunShadowHierarchy0.Store(coordinate, value);
    else if (cascade == 1) sunShadowHierarchy1.Store(coordinate, value);
    else if (cascade == 2) sunShadowHierarchy2.Store(coordinate, value);
    else if (cascade == 3) sunShadowHierarchy3.Store(coordinate, value);
    else sunShadowHierarchy4.Store(coordinate, value);
}

[numthreads(8, 8, 1)]
void main(uint3 dispatchThreadId : SV_DispatchThreadID)
{
    int level = atmospherePush.hierarchyLevel;
    int2 coordinate = int2(dispatchThreadId.xy);
    int cascade = int(dispatchThreadId.z);
    if (level != 0
            || cascade >= 5
            || any(coordinate >= int2(PRIME_SUN_SHADOW_RESOLUTION))) return;

    int bank = int(atmospherePush.shadowDirectionBank.w + 0.5);
    int imageIndex = bank * 5 + cascade;
    float blockerDepth = -1.0e20;
    for (int y = -1; y <= 1; ++y) {
        for (int x = -1; x <= 1; ++x) {
            int2 source = (coordinate + int2(x, y))
                    & (PRIME_SUN_SHADOW_RESOLUTION - 1);
            blockerDepth = max(blockerDepth, sunShadowDepth(imageIndex, source));
        }
    }
    hierarchyStore(cascade, coordinate, float2(blockerDepth));
}
