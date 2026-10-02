#language slang 2026
module primary_psr_test;
import "reconstruct/primary_psr.slang";
import "reconstruct/rr_guides.slang";

StructuredBuffer<float4> inputs;
RWStructuredBuffer<float4> outputs;

PrimePrimaryPsr psrCpuUnpack(float4 rotation, float4 translationFlags) {
    return {rotation, translationFlags.xyz, uint(translationFlags.w)};
}

float4 psrCpuPack(PrimePrimaryPsr state, uint channel) {
    return channel == 0 ? state.rotation : float4(state.translation, float(state.flags));
}

public float4 psrCpuInitial(uint channel) {
    return psrCpuPack(primePrimaryPsrInitial(), channel);
}

public float4 psrCpuAppend(float4 rotation, float4 translationFlags, float3 point, float3 normal,
                           uint events, uint channel) {
    PrimePrimaryPsr state =
            primePrimaryPsrAppend(psrCpuUnpack(rotation, translationFlags), point, normal,
                                  (events & 1u) != 0, (events & 2u) != 0, (events & 4u) != 0);
    return psrCpuPack(state, channel);
}

public float3 psrCpuTransform(float4 rotation, float4 translationFlags, float3 value, bool point) {
    PrimePrimaryPsr state = psrCpuUnpack(rotation, translationFlags);
    return point ? primePrimaryPsrPoint(state, value) : primePrimaryPsrDirection(state, value);
}

public float4 psrCpuSurface(float4 rotation, float4 translationFlags, float3 camera,
                            float3 cameraDirection, float3 target, float3 geometricNormal,
                            float3 shadingNormal, bool terminalStatic, uint channel) {
    PrimePrimaryPsr state = psrCpuUnpack(rotation, translationFlags);
    float3 point, normal;
    bool valid = primePrimaryPsrSurface(state, camera, cameraDirection, target, geometricNormal,
                                        shadingNormal, point, normal);
    return channel == 0
                   ? float4(point, valid ? 1.0 : 0.0)
                   : float4(normal, primePrimaryPsrMotionKnown(state, terminalStatic) ? 1.0 : 0.0);
}

public float2 psrCpuMotion(float3 point, float2 sampleUv, float4 position, float4 forward,
                           float4 right, float4 up, float2 jitter) {
    RrCamera previous = (RrCamera)0;
    previous.previousPosition = position;
    previous.previousForward = forward;
    previous.previousRight = right;
    previous.previousUp = up;
    previous.jitter = float4(jitter, 1, 0);
    return primeRrMotion(previous, sampleUv, point, false, true);
}

[shader("compute")][numthreads(1, 1, 1)] void main(uint3 id : SV_DispatchThreadID) {
    float4 a = inputs[id.x];
    float4 rotation = psrCpuInitial(0);
    float4 translation = psrCpuInitial(1);
    float4 appended = psrCpuAppend(rotation, translation, a.xyz, float3(0, 0, 1), 5u, 1u);
    outputs[id.x] = psrCpuSurface(rotation, appended, float3(0), float3(0, 0, 1), a.xyz,
                                  float3(0, 0, 1), float3(0, 0, 1), true, 0u);
    outputs[id.x + 1] = float4(psrCpuTransform(rotation, appended, a.xyz, true), 0);
    outputs[id.x + 2] =
            float4(psrCpuMotion(a.xyz, float2(0.5), float4(0, 0, 0, 1), float4(0, 0, 1, 1),
                                float4(1, 0, 0, 0), float4(0, 1, 0, 0), float2(0.25, -0.25)),
                   0, 0);
}
