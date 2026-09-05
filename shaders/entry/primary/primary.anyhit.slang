#language slang 2026
module "entry/primary/primary.anyhit.slang";

import glsl;
import "prime_abi_types.slang";
import "contract/primary_color.slang";
import "service/trace/candidate.slang";

layout(location = 0) rayPayloadInEXT PrimePrimaryColorPayload primePrimaryPayload;

[shader("anyhit")]
void main(in BuiltInTriangleIntersectionAttributes attributes)
{
    if (GeometryIndex() == 0u) {
        return;
    }
    if (primeCandidateCutout(primeCandidateSurface(attributes.barycentrics), attributes.barycentrics)) {
        IgnoreHit();
    }
}
