// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

#language slang 2026
module "entry/offline/direct.raygeneration.slang";

import "contract/offline/shader_record.slang";
import "policy/offline/fixed_queue.slang";
import "service/trace/context.slang";
import "transport/offline/direct.slang";

[shader("raygeneration")]
void main() {
    PrimeIntegratorContext context = primeIntegratorContextCreate();
    primeEvaluateOfflineDirect(
            context,
            primeOfflineSourceQueue(primeOfflineShaderRecord.control),
            DispatchRaysIndex().x);
}
