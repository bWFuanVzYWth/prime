// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

#language slang 2026
module "transport/realtime/landing_light_select.slang";

import "prime_realtime_abi.slang";
import "state/realtime/queue.slang";
import "transport/realtime/light_select.slang";

public void primeSelectLandingLight(uint launchIndex) {
    primeSelectWavefrontLight(primeWavefrontQueuedPath(PRIME_WAVEFRONT_PRIMARY_QUEUE, launchIndex));
}
