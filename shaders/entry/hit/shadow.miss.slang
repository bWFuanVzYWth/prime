#language slang 2026
module "entry/hit/shadow.miss.slang";

import glsl;

import "contract/trace/shadow_payload.slang";

layout(location = 1) rayPayloadInEXT PrimeShadowPayload payload;

[shader("miss")]
void main()
{
    payload.opticalDepthMomentHitDistance.w = 65504.0;
}
