#language slang 2026
module "entry/realtime/guide_delta_walk.raygeneration.slang";

import "phase/realtime/guide_delta_walk.slang";

[shader("raygeneration")]
void main()
{
    primePhase();
}
