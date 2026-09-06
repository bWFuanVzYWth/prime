import "policy/realtime/hit_distance.slang";
import "policy/realtime/landing.slang";
import "policy/realtime/secondary.slang";
[[vk::binding(0, 0)]] StructuredBuffer<uint4> inputData;
[[vk::binding(1, 0)]] RWStructuredBuffer<uint4> outputData;
[numthreads(64, 1, 1)]
void main(uint3 tid : SV_DispatchThreadID) {
    if (tid.x >= inputData[0].x) return;
    uint4 c = inputData[tid.x + 1u];
    outputData[tid.x] = uint4(
            primeScatterHitDistanceUpdates(PrimeLandingTransportPhase(), c.x != 0u, c.y != 0u, c.z, c.w),
            primeScatterHitDistanceUpdates(PrimeSecondaryTransportPhase(), c.x != 0u, c.y != 0u, c.z, c.w), 0u, 0u);
}
