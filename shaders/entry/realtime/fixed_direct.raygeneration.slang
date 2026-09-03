#language slang 2026
module "entry/realtime/fixed_direct.raygeneration.slang";

import "contract/realtime/shader_record.slang";
import "policy/realtime/fixed_queue.slang";
import "service/trace/context.slang";
import "transport/realtime/fixed_direct.slang";

[shader("raygeneration")]
void main() {
    PrimeIntegratorContext context = primeIntegratorContextCreate();
    primeEvaluateFixedDirect(
            context,
            primeFixedSourceQueue(primeWavefrontShaderRecord.control),
            DispatchRaysIndex().x);
}
