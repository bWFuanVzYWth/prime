import "model/atmosphere/sky_view.slang";
[[vk::binding(4, 0)]] [[vk::image_format("rgba32f")]] readonly RWTexture2D<float4> sky;
[[vk::binding(33, 0)]] StructuredBuffer<float4> input;
[[vk::binding(34, 0)]] RWStructuredBuffer<float4> output;
[numthreads(64, 1, 1)]
void main(uint3 id : SV_DispatchThreadID) {
    uint count, stride;
    input.GetDimensions(count,stride);
    if (id.x*2u >= count) return;
    float4 ray = input[id.x*2u], sun = input[id.x*2u+1u];
    output[id.x] = float4(atmSampleSky(sky,ray.xyz,sun.xyz,ray.w),0.0);
}
