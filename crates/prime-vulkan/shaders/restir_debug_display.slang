// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.
#language slang 2026
module restir_debug_display;
import "restir/parameters.slang";
import "restir/indexing.slang";
[[vk::binding(23, 0)]] ConstantBuffer<RestirParameters> parameters;
[[vk::binding(4, 0)]] [[vk::image_format("rgba8")]] RWTexture2D<float4> outputImage;

// RA-015: visualization is a display-only consumer after RR. The model still
// receives physical noisy RGB, and neither SDK nor reservoir history is polluted.
[numthreads(8, 8, 1)] void main(uint3 id : SV_DispatchThreadID) {
    uint width, height;
    outputImage.GetDimensions(width, height);
    if (id.x >= width || id.y >= height)
        return;
    uint2 extent = parameters.frame.dimensions.xy;
    uint2 pixel =
            min(uint2((float2(id.xy) + 0.5) * float2(extent) / float2(width, height)), extent - 1u);
    uint offset = restirOffset(pixel, extent);
    float3 color = parameters.config.features.w == 1u
                           ? float3(float(parameters.duplicateCounts[offset]) / 288.0, 0, 0)
                           : float3(0, float(parameters.currentAges[offset]) / 40.0, 0);
    uint2 target = uint2(id.x, parameters.frame.target.x != 0u ? height - 1u - id.y : id.y);
    outputImage[target] = float4(saturate(color), 1);
}
