#language slang 2026
module "atmosphere/transmittance_update.slang";
import "atmosphere/view.slang";
import "atmosphere/spectrum.slang";
import "atmosphere/transmittance.slang";
#include "atmosphere/model_bindings.slang"
[[vk::binding(0, 1)]] StructuredBuffer<AtmFrame> frames;
[[vk::binding(1, 2)]] [[vk::image_format("rgba16f")]] RWTexture2D<float4> transmittanceOutput;
[numthreads(64, 1, 1)] void main(uint3 id : SV_DispatchThreadID) {
    if (id.x >= 8193)
        return;
    float u = 2.0 * (float(id.x) / 8192.0) - 1.0;
    float mu = u * abs(u);
    transmittanceOutput[int2(id.x, 0)] =
            float4(atmRec2020Transmittance(
                           atmOpticalTransmittance(atmosphereModel(), frames[0].geometry.x, mu)),
                   1);
}
