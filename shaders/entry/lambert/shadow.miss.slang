#language slang 2026
module "entry/lambert/shadow.miss.slang";
import glsl;
import "contract/lambert.slang";
layout(location = 1) rayPayloadInEXT PrimeLambertShadowPayload primeLambertShadow;
[shader("miss")]
void main() { primeLambertShadow.distance = -1.0; }
