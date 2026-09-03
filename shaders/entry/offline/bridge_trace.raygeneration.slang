#language slang 2026
module "entry/offline/bridge_trace.raygeneration.slang";

import "contract/offline/shader_record.slang";
import "policy/offline/fixed_queue.slang";
import "transport/offline/trace.slang";

[shader("raygeneration")]
void main() {
    primeTraceOfflineBridge(
            primeOfflineSourceQueue(primeOfflineShaderRecord.control),
            DispatchRaysIndex().x);
}
