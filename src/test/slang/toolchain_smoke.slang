// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

struct PrimeSlangSmokePush
{
    float4x4 transform;
    uint outputIndex;
};

[[vk::push_constant]]
ConstantBuffer<PrimeSlangSmokePush> primePush;

[[vk::binding(0, 0)]]
RWStructuredBuffer<uint> primeOutput;

[numthreads(1, 1, 1)]
void main(uint3 dispatchThreadId : SV_DispatchThreadID)
{
    float4 transformed = mul(
        primePush.transform,
        float4(float(dispatchThreadId.x), 0.0, 0.0, 1.0));
    primeOutput[primePush.outputIndex] = asuint(transformed.x);
}
