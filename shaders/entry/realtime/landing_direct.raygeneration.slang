// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

#language slang 2026
module "entry/realtime/landing_direct.raygeneration.slang";

import "service/trace/context.slang";
import "transport/realtime/landing_direct.slang";

[shader("raygeneration")]
void main() {
    PrimeIntegratorContext context = primeIntegratorContextCreate();
    primeEvaluateLandingDirect(context, DispatchRaysIndex().x);
}
