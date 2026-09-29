#language slang 2026
module "atmosphere/shadow_demand.slang";
import "atmosphere/view.slang";
#include "atmosphere/shadow_profile.inc.slang"
[[vk::binding(0, 1)]] StructuredBuffer<AtmFrame> frames;
#define ATM_SHADOW_DEMAND
#include "atmosphere/shadow_source.inc.slang"
[numthreads(8, 1, 1)] void main(uint3 group : SV_GroupID, uint3 lane : SV_GroupThreadID) {
    AtmFrame f = frames[0];
    if (lane.x == 0)
        primeInitializeSunShadowProfile(f.sun.xyz, f.camera.xyz,
                                        atmAerialDirection(f, int2(127, group.y)), f.shadowU.xyz,
                                        f.shadowV.xyz);
    GroupMemoryBarrierWithGroupSync();
    if (lane.x < 5)
        sunShadowBuildProfileCascade(shadowSource(), int(lane.x));
}
