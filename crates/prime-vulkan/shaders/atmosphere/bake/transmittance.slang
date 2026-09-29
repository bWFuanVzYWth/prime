// Adapted from Sky Tracer b66b163 (GPL-3.0-only); see THIRD_PARTY_NOTICES.md.

#language slang 2026
module "atmosphere/bake/transmittance.slang";
#include "atmosphere/model_bindings.slang"

import "atmosphere/parameters.slang";
import "atmosphere/optical_depth.slang";

[[vk::binding(6, 0)]] [[vk::image_format("rgba16f")]] RWTexture2D<float4> opticalDepthOutput;

[numthreads(8, 8, 1)] void main(uint3 id : SV_DispatchThreadID) {
    if (id.x >= ATM_DIMS.w || id.y >= ATM_DIMS.z) {
        return;
    }
    opticalDepthOutput[int2(id.xy)] = atmOpticalDepth(atmosphereModel(), id.xy);
}
