import "math/transport.slang";
import "math/lambert_roulette.slang";
import "model/lambert/path.slang";
import "contract/lambert.slang";

[[vk::binding(0, 0)]] StructuredBuffer<uint> inputData;
[[vk::binding(1, 0)]] RWStructuredBuffer<float4> outputData;

[numthreads(64, 1, 1)]
void main(uint3 tid : SV_DispatchThreadID) {
    uint index = tid.x;
    if (index >= inputData[0]) return;
    if (inputData[1] == 0u) {
        uint o = 4u + index * 4u;
        float3 beta = asfloat(uint3(inputData[o], inputData[o + 1u], inputData[o + 2u]));
        float eta = asfloat(inputData[o + 3u]);
        float p = primeRussianRouletteSurvival(beta, eta);
        outputData[index] = float4(p, p > 0.0 ? beta / p : float3(0.0));
    } else {
        PrimeLambertRecord path = primeLambertCameraPath(float3(2.0, 3.0, 4.0), float3(0.0, 0.0, 1.0));
        primeLambertRecordHitDistance(path.radiance.w, 0u, 5.0);
        float initial = path.radiance.w;
        float3 beta = index == 0u ? float3(0.0) : float3(0.01);
        bool alive = primeLambertRoulette(beta, 1u, index <= 1u ? 0.9 : 0.0);
        if (alive) primeLambertRecordHitDistance(path.radiance.w, 1u, index == 2u ? 7.0 : -1.0);
        float first = path.radiance.w;
        primeLambertRecordHitDistance(path.radiance.w, 2u, 99.0);
        primeLambertRecordHitDistance(path.radiance.w, 12u, -1.0);
        outputData[index] = float4(initial, first, path.radiance.w, float(sizeof(PrimeLambertRecord)));
    }
}
