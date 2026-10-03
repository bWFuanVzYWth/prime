#language slang 2026
module "display/hdr_present.slang";
import "display/hdr.slang";
[[vk::binding(0, 0)]] Texture2D<float4> hdrImage;
[[vk::binding(1, 0)]] Texture2D<float4> sdrBaseline;
[[vk::binding(2, 0)]] Texture2D<float4> uiComposite;
[[vk::binding(3, 0)]] [[vk::image_format("rgba16f")]] RWTexture2D<float4> outputImage;
[[vk::binding(4, 0)]] [[vk::image_format("rgba16f")]] RWTexture2D<float4> fgHudless;
[[vk::binding(5, 0)]] [[vk::image_format("r8")]] RWTexture2D<float> fgUiCoverage;
struct HdrParameters {
    uint2 extent;
    uint composite;
    uint bottomUp;
    float scale;
    uint fgEnabled;
    uint2 reserved;
};
[[vk::push_constant]] ConstantBuffer<HdrParameters> parameters;
[numthreads(8, 8, 1)] void main(uint3 id : SV_DispatchThreadID) {
    if (any(id.xy >= parameters.extent))
        return;
    float4 ui = uiComposite.Load(int3(id.xy, 0));
    float3 result;
    if (parameters.composite != 0) {
        uint2 world =
                uint2(id.x, parameters.bottomUp != 0 ? parameters.extent.y - 1u - id.y : id.y);
        float3 linearWorld = primeExtendedSrgbEotf(hdrImage.Load(int3(world, 0)).rgb);
        result = primeHdrCompositeLinear(linearWorld, sdrBaseline.Load(int3(world, 0)).rgb, ui,
                                         parameters.scale);
        if (parameters.fgEnabled != 0) {
            fgHudless[id.xy] = float4(linearWorld * parameters.scale, 1.0);
            fgUiCoverage[id.xy] = primeUiCoverage(ui);
        }
    } else
        result = primeExtendedSrgbEotf(ui.rgb) * parameters.scale;
    outputImage[id.xy] = float4(result, 1.0);
}
