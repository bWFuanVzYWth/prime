#language slang 2026
module "entry/realtime/camera_trace.raygeneration.slang";

import "service/trace/context.slang";
import "transport/realtime/camera_trace.slang";

[shader("raygeneration")]
void main()
{
    uint2 launchIndex = DispatchRaysIndex().xy;
    PrimeIntegratorContext context = primeIntegratorContextCreate();
    primeTraceRealtimeCamera(context, launchIndex);
}
