#language slang 2026
module "display/from_linear.slang";
import "display/prime_drt.slang";
import "display/exposure.slang";
import "math/color.slang";
[[vk::binding(0, 0)]] Texture2D<float4> linearInput;
[[vk::binding(1, 0)]] [[vk::image_format("rgba8")]] RWTexture2D<float4> hostOutput;
[[vk::binding(2, 0)]] [[vk::image_format("rgba16f")]] RWTexture2D<float4> hdrOutput;
[[vk::binding(3, 0)]] [[vk::image_format("rgba8")]] RWTexture2D<float4> sdrBaseline;
struct LinearDisplayParameters {
    float4 sdr;
    float4 sdrExtra;
    float4 hdr;
    float4 hdrExtra;
    uint4 control; // extent.xy; input709/host bottom-up/HDR/FG/exposure BDA/linear BDA bits0..5
    PrimeAutoExposureState *exposure;
    float4 *linearAddress;
};
[[vk::push_constant]] ConstantBuffer<LinearDisplayParameters> parameters;
[numthreads(8, 8, 1)] void main(uint3 id : SV_DispatchThreadID) {
    if (any(id.xy >= parameters.control.xy))
        return;
    float3 value = (parameters.control.z & 32u) != 0
                           ? parameters.linearAddress[id.y * parameters.control.x + id.x].rgb
                           : linearInput.Load(int3(id.xy, 0)).rgb;
    value = float3(isfinite(value.x) ? max(value.x, 0.0) : 0.0,
                   isfinite(value.y) ? max(value.y, 0.0) : 0.0,
                   isfinite(value.z) ? max(value.z, 0.0) : 0.0);
    if ((parameters.control.z & 1u) != 0)
        value = linear709ToWorking(value);
    float autoExposure =
            (parameters.control.z & 16u) != 0 ? exp2(parameters.exposure[0].exposureEv) : 1.0;
    PrimeDRTParameters sdr = {parameters.sdr.x * autoExposure, parameters.sdr.y, parameters.sdr.z,
                              parameters.sdr.w, parameters.sdrExtra.x};
    float3 encoded = primeDRT(value, sdr);
    encoded = float3(uint3(saturate(encoded) * 255.0 + 0.5)) / 255.0;
    bool hdrActive = (parameters.control.z & 4u) != 0;
    bool fgActive = (parameters.control.z & 8u) != 0;
    uint2 target =
            uint2(id.x, (parameters.control.z & 2u) != 0 ? parameters.control.y - 1u - id.y : id.y);
    hostOutput[target] = float4(encoded, hdrActive || fgActive ? 0.0 : 1.0);
    if (hdrActive || fgActive)
        sdrBaseline[id.xy] = float4(encoded, 0.0);
    if (hdrActive) {
        // Both snapshots remain canonical top-left and untouched by later hand/HUD drawing.
        PrimeDRTParameters hdr = {parameters.hdr.x * autoExposure, parameters.hdr.y,
                                  parameters.hdr.z, parameters.hdr.w, parameters.hdrExtra.x};
        hdrOutput[id.xy] = float4(primeDRT(value, hdr), 1.0);
    }
}
