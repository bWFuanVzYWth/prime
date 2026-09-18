// Adapted from Sky Tracer b66b163 (GPL-3.0-only); see THIRD_PARTY_LICENSES/SKY-TRACER-NOTICE.md.

#language slang 2026
module "entry/atmosphere/atmosphere_transmittance.compute.slang";

import "model/atmosphere/parameters.slang";
import "model/atmosphere/optical_depth.slang";

[[vk::binding(32, 0)]] [[vk::image_format("rgba16f")]] RWTexture2D<float4> opticalDepthOutput;

[numthreads(8, 8, 1)] void main(uint3 id : SV_DispatchThreadID) {
    if (id.x >= ATM_DIMS.w || id.y >= ATM_DIMS.z) {
        return;
    }
    opticalDepthOutput[int2(id.xy)] = atmOpticalDepth(id.xy);
}
