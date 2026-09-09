// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

#language slang 2026
module "entry/realtime/secondary_direct.raygeneration.slang";

import "contract/realtime/shader_record.slang";
import "policy/realtime/secondary_queue.slang";
import "service/trace/context.slang";
import "transport/realtime/secondary_direct.slang";

[shader("raygeneration")]
void main() {
    PrimeIntegratorContext context = primeIntegratorContextCreate();
    primeEvaluateSecondaryDirect(
            context,
            primeSecondarySourceQueue(primeWavefrontShaderRecord.control),
            DispatchRaysIndex().x);
}
