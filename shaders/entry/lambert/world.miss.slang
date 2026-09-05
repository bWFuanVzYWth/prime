#language slang 2026
module "entry/lambert/world.miss.slang";
import glsl;
import "contract/lambert.slang";
layout(location = 0) rayPayloadInEXT PrimeLambertPayload primeLambertPayload;
[shader("miss")]
void main() { primeLambertPayload.distance = -1.0; }
