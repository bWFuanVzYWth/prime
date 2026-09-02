#language slang 2026
module "entry/hit/shadow_opaque.anyhit.slang";

import "phase/hit/shadow_opaque_anyhit.slang";

[shader("anyhit")]
void main(in BuiltInTriangleIntersectionAttributes attributes) {
    primePhase(attributes);
}
