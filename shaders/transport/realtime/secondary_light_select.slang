// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

#language slang 2026
module "transport/realtime/secondary_light_select.slang";

import "prime_realtime_abi.slang";
import "state/realtime/queue.slang";
import "transport/realtime/light_select.slang";

public void primeSelectSecondaryLight(uint sourceQueue, uint launchIndex) {
    primeSelectWavefrontLight(primeWavefrontQueuedPath(sourceQueue, launchIndex));
}
