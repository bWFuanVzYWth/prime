#language slang 2026
module "entry/realtime/secondary_scatter.raygeneration.slang";

import "contract/realtime/shader_record.slang";
import "policy/realtime/secondary_queue.slang";
import "service/trace/context.slang";
import "state/realtime/queue.slang";
import "transport/realtime/secondary_scatter.slang";

[shader("raygeneration")]
void main() {
    uint launchIndex = DispatchRaysIndex().x;
    uint control = primeWavefrontShaderRecord.control;
    uint sourceQueue = primeSecondarySourceQueue(control);
    if (launchIndex == 0u) {
        // Last consumer: leave the consumed slot empty even if no successor launches next round.
        primeResetWavefrontQueue(sourceQueue);
    }
    PrimeIntegratorContext context = primeIntegratorContextCreate();
    primeScatterSecondaryPath(
            context,
            sourceQueue,
            primeSecondaryTargetQueue(control),
            launchIndex);
}
