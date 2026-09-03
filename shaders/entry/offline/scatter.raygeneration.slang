#language slang 2026
module "entry/offline/scatter.raygeneration.slang";

import "contract/offline/shader_record.slang";
import "policy/offline/fixed_queue.slang";
import "policy/offline/queue.slang";
import "service/trace/context.slang";
import "state/offline/queue.slang";
import "transport/offline/scatter.slang";

[shader("raygeneration")]
void main() {
    uint launchIndex = DispatchRaysIndex().x;
    uint control = primeOfflineShaderRecord.control;
    uint sourceQueue = primeOfflineSourceQueue(control);
    if (launchIndex == 0u) {
        // The indirect command is already consumed; clearing its count makes this ping-pong slot
        // safe even when a later round receives no continuations.
        primeResetOfflineQueue(sourceQueue);
    }
    PrimeIntegratorContext context = primeIntegratorContextCreate();
    primeScatterOfflinePath(
            PrimeOfflineContinuationAppender(),
            context,
            sourceQueue,
            primeOfflineTargetQueue(control),
            launchIndex);
}
