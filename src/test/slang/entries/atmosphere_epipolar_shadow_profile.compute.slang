// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.


import "math/sun_shadow_hierarchy.slang";
import "service/atmosphere/epipolar_shadow.slang";

struct PrimeProfileCase {
    float4 cameraDirection;
    float4 rayBlocker;
    float4 intervalDepth;
};

[[vk::binding(0, 0)]] StructuredBuffer<PrimeProfileCase> primeProfileInput;

[[vk::binding(1, 0)]] RWStructuredBuffer<float> primeProfileOutput;

groupshared uint primeProfileCaseIndex;

struct PrimeTestSunShadowProfileSource : IPrimeSunShadowProfileSource {
    bool enabled() { return true; }

    float texelSize(int cascade) {
        return sunShadowTexelSize(cascade);
    }

    float2 hierarchyRange(int cascade, int level, int2 worldNode) {
        float blocker = primeProfileInput[primeProfileCaseIndex].rayBlocker.w;
        return float2(blocker);
    }
};

[numthreads(128, 1, 1)]
void main(uint3 dispatchThreadId : SV_DispatchThreadID, uint3 groupThreadId : SV_GroupThreadID, uint3 groupId : SV_GroupID, uint groupIndex : SV_GroupIndex) {
    uint caseIndex = groupId.x;
    PrimeProfileCase value = primeProfileInput[caseIndex];
    if (groupIndex == 0) {
        primeProfileCaseIndex = caseIndex;
        primeInitializeSunShadowProfile(
                float3(0.0, 0.0, 1.0),
                float3(value.cameraDirection.xy, value.intervalDepth.z),
                float3(normalize(value.cameraDirection.zw), 0.0));
    }
    GroupMemoryBarrierWithGroupSync();
    if (groupIndex < SUN_SHADOW_CASCADE_COUNT) {
        sunShadowBuildProfileCascade(
                PrimeTestSunShadowProfileSource(), int(groupIndex));
    }
    GroupMemoryBarrierWithGroupSync();
    if (groupIndex == 0) {
        int outputBase = int(caseIndex) * 7;
        for (int cascade = 0;
                cascade < SUN_SHADOW_CASCADE_COUNT;
                ++cascade) {
            primeProfileOutput[outputBase + cascade] =
                    float(primeSunShadowProfileCount(cascade));
        }
        int last = primeSunShadowProfileCount(0) - 1;
        primeProfileOutput[outputBase + 5] = last >= 0
                ? primeSunShadowProfileEntry(last).x
                : -1.0;
        primeProfileOutput[outputBase + 6] =
                localSunVisibilityInterval(
                        PrimeTestSunShadowProfileSource(),
                        value.rayBlocker.xyz,
                        value.intervalDepth.x,
                        value.intervalDepth.y);
    }
}
