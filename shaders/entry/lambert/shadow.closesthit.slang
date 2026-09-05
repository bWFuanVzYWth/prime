#language slang 2026
module "entry/lambert/shadow.closesthit.slang";
import glsl;
import "contract/lambert.slang";
layout(location = 1) rayPayloadInEXT PrimeLambertShadowPayload primeLambertShadow;
[shader("closesthit")]
void main() { primeLambertShadow.distance = RayTCurrent(); }
