import "model/atmosphere/integrate.slang";
[[vk::binding(33, 0)]] StructuredBuffer<float4> input;
[[vk::binding(34, 0)]] RWStructuredBuffer<float4> output;
[numthreads(64, 1, 1)]
void main(uint3 id : SV_DispatchThreadID) {
    uint count, stride;
    input.GetDimensions(count,stride);
    if (id.x * 2u >= count) return;
    float4 ray = input[id.x*2u], sun = input[id.x*2u+1u];
    AtmTransport value = atmIntegrateAt(ray.xyz,sun.xyz,ray.w,sun.w,96u,true);
    output[id.x*2u] = value.light;
    output[id.x*2u+1u] = value.transmittance;
}
