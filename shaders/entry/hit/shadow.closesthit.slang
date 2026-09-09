// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

#language slang 2026
module "entry/hit/shadow.closesthit.slang";

import glsl;

import "contract/trace/shadow_payload.slang";

layout(location = 1) rayPayloadInEXT PrimeShadowPayload payload;

[shader("closesthit")]
void main(in BuiltInTriangleIntersectionAttributes attributes)
{
    payload.opticalDepthMomentHitDistance.w = min(RayTCurrent(), 65503.0);
}
