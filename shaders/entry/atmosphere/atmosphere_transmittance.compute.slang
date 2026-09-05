#language slang 2026
module "entry/atmosphere/atmosphere_transmittance.compute.slang";

import "model/atmosphere/transmittance.slang";
import "service/image/access.slang";

[[vk::binding(0, 0)]] [[vk::image_format("rgba16f")]] writeonly RWTexture2D<float4> transmittanceLow;
[[vk::binding(1, 0)]] [[vk::image_format("rgba16f")]] writeonly RWTexture2D<float4> transmittanceHigh;


[numthreads(8, 8, 1)]
void main(uint3 dispatchThreadId : SV_DispatchThreadID)
{
    int2 pixel = int2(dispatchThreadId.xy);
    int2 dimensions = primeWriteImageSize(transmittanceLow);
    if (any(pixel >= dimensions)) {
        return;
    }
    float4 low;
    float4 high;
    atmTransmittanceTexel(pixel, dimensions, low, high);
    primeWriteImageStore(transmittanceLow, pixel, low);
    primeWriteImageStore(transmittanceHigh, pixel, high);
}
