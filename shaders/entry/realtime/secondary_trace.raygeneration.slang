#language slang 2026
module "entry/realtime/secondary_trace.raygeneration.slang";

import "contract/realtime/shader_record.slang";
import "policy/realtime/secondary_queue.slang";
import "service/trace/context.slang";
import "state/realtime/queue.slang";
import "transport/realtime/secondary_trace.slang";

[shader("raygeneration")]
void main() {
    uint launchIndex = DispatchRaysIndex().x;
    uint control = primeWavefrontShaderRecord.control;
    PrimeIntegratorContext context = primeIntegratorContextCreate();
    primeTraceSecondary(
            context, primeSecondarySourceQueue(control), launchIndex);
}
