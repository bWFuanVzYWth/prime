#language slang 2026
module "display/frame_generation_present.slang";
import "display/hdr.slang";
import "display/image_space.slang";
[[vk::binding(0, 0)]] Texture2D<float4> sdrBaseline;
[[vk::binding(1, 0)]] Texture2D<float4> uiComposite;
[[vk::binding(2, 0)]] [[vk::image_format("rgba8")]] RWTexture2D<float4> hudlessOutput;
[[vk::binding(3, 0)]] [[vk::image_format("r8")]] RWTexture2D<float> uiCoverageOutput;
struct Parameters {
    uint2 extent;
    uint bottomUp;
    uint reserved;
};
[[vk::push_constant]] ConstantBuffer<Parameters> parameters;
[numthreads(8, 8, 1)] void main(uint3 id : SV_DispatchThreadID) {
    if (any(id.xy >= parameters.extent))
        return;
    uint2 ui = primePresentUiPixel(id.xy, parameters.extent, parameters.bottomUp != 0);
    hudlessOutput[id.xy] = float4(sdrBaseline.Load(int3(id.xy, 0)).rgb, 1.0);
    uiCoverageOutput[id.xy] = primeUiCoverage(uiComposite.Load(int3(ui, 0)));
}
