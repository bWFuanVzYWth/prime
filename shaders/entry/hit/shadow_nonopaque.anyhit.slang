#language slang 2026
module "entry/hit/shadow_nonopaque.anyhit.slang";

import "phase/hit/shadow_anyhit.slang";

[shader("anyhit")]
void main(in BuiltInTriangleIntersectionAttributes attributes) {
    primePhase(attributes);
}
