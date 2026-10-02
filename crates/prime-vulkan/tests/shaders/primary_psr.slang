#language slang 2026
module primary_psr_test;
import "reconstruct/primary_psr.slang";
import "reconstruct/rr_guides.slang";

StructuredBuffer<float4> inputs;
RWStructuredBuffer<float4> outputs;

public float4 psrCpuProbe(float3 camera, float3 first, float3 second, float3 target,
                          float3 firstNormal, float3 secondNormal, float3 targetNormal, uint count,
                          uint flags, uint channel) {
    PrimePrimaryPsr psr = primePrimaryPsrInitial();
    float3 previous = camera;
    if (count > 0) {
        psr = primePrimaryPsrAppend(psr, camera, previous, first, firstNormal, (flags & 1u) != 0,
                                    (flags & 32u) != 0, (flags & 4u) != 0);
        previous = first;
    }
    if (count > 1) {
        psr = primePrimaryPsrAppend(psr, camera, previous, second, secondNormal, (flags & 2u) != 0,
                                    (flags & 64u) != 0, (flags & 8u) != 0);
        previous = second;
    }
    float3 position, normal;
    bool valid =
            primePrimaryPsrSurface(psr, camera, previous, target, targetNormal, position, normal);
    if (channel == 0)
        return float4(position, valid ? 1.0 : 0.0);
    if (channel == 1)
        return float4(normal, primePrimaryPsrMotionKnown(psr, (flags & 16u) != 0) ? 1.0 : 0.0);
    return float4(primePrimaryPsrDirection(psr, targetNormal),
                  float(psr.flags & PRIME_PRIMARY_PSR_COUNT));
}

public float2 psrCpuStaticMotion(float3 point, float2 sampleUv, float3 camera) {
    RrCamera previous = {float4(camera, 1), float4(0, 0, 1, 1), float4(1, 0, 0, 0),
                         float4(0, 1, 0, 0), float4(0.25, -0.25, 1, 0)};
    return primeRrMotion(previous, sampleUv, point, false, true);
}

[shader("compute")][numthreads(1, 1, 1)] void main(uint3 id : SV_DispatchThreadID) {
    float4 a = inputs[id.x];
    outputs[id.x] =
            psrCpuProbe(a.xyz, float3(0, 0, 1), float3(1, 0, 1), float3(1, 0, 0), float3(0, 0, 1),
                        float3(1, 0, 0), float3(0, 1, 0), uint(a.w), 31u, 0u);
    outputs[id.x + 1] = float4(psrCpuStaticMotion(a.xyz, float2(0.5), float3(0)), 0, 0);
}
