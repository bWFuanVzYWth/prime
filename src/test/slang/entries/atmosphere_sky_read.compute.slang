[[vk::binding(4, 0)]] [[vk::image_format("rgba32f")]] readonly RWTexture2D<float4> sky;
[[vk::binding(33, 0)]] StructuredBuffer<uint4> input;
[[vk::binding(34, 0)]] RWStructuredBuffer<float4> output;
[numthreads(64, 1, 1)]
void main(uint3 id : SV_DispatchThreadID) {
    uint count, stride;
    input.GetDimensions(count,stride);
    if (id.x >= count) return;
    output[id.x] = float4(exp(sky[int2(input[id.x].xy)].xyz),0.0);
}
