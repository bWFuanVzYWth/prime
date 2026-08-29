import "rt_execution_contract.slang";

[[vk::binding(0, 0)]]
RaytracingAccelerationStructure primeRtScene;

[[vk::binding(1, 0)]]
RWStructuredBuffer<uint> primeRtOutput;

[shader("raygeneration")]
void main()
{
    uint rayIndex = DispatchRaysIndex().x;
    RayDesc ray;
    ray.Origin = rayIndex == 0u
            ? float3(0.0, 0.0, -1.0)
            : float3(2.0, 0.0, -1.0);
    ray.Direction = float3(0.0, 0.0, 1.0);
    ray.TMin = 0.0;
    ray.TMax = 4.0;

    [[vk::location(0)]] PrimeRtExecutionPayload payload;
    payload.flags = 0u;
    payload.primitiveIndex = 0xffffffffu;
    payload.instanceIndex = 0xffffffffu;
    payload.barycentrics = float2(-1.0);
    payload.distance = -1.0;
    TraceRay(primeRtScene, RAY_FLAG_NONE, 0xff, 0, 0, 0, ray, payload);

    uint output = rayIndex * 6u;
    primeRtOutput[output + 0u] = payload.flags;
    primeRtOutput[output + 1u] = payload.primitiveIndex;
    primeRtOutput[output + 2u] = payload.instanceIndex;
    primeRtOutput[output + 3u] = asuint(payload.barycentrics.x);
    primeRtOutput[output + 4u] = asuint(payload.barycentrics.y);
    primeRtOutput[output + 5u] = asuint(payload.distance);
}
