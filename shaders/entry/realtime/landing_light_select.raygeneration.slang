#language slang 2026
module "entry/realtime/landing_light_select.raygeneration.slang";

import "transport/realtime/landing_light_select.slang";

[shader("raygeneration")]
void main() {
    primeSelectLandingLight(DispatchRaysIndex().x);
}
