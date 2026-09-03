#language slang 2026
module "entry/realtime/fixed_scatter.raygeneration.slang";

import "contract/realtime/shader_record.slang";
import "policy/realtime/fixed_queue.slang";
import "service/trace/context.slang";
import "transport/realtime/fixed_scatter.slang";

[shader("raygeneration")]
void main() {
    uint control = primeWavefrontShaderRecord.control;
    PrimeIntegratorContext context = primeIntegratorContextCreate();
    primeScatterFixedPath(
            context,
            primeFixedSourceQueue(control),
            primeFixedTargetQueue(control),
            DispatchRaysIndex().x);
}
