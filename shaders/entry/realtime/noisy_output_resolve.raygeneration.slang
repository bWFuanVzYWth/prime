// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

#language slang 2026
module "entry/realtime/noisy_output_resolve.raygeneration.slang";

import "transport/realtime/noisy_output_resolve.slang";

[shader("raygeneration")]
void main()
{
    uint2 launchIndex = DispatchRaysIndex().xy;
    primeResolveNoisyOutput(launchIndex);
}
