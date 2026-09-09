// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

#language slang 2026
module "entry/offline/light_select.raygeneration.slang";

import "contract/offline/shader_record.slang";
import "policy/offline/fixed_queue.slang";
import "transport/offline/light_select.slang";

[shader("raygeneration")]
void main() {
    primeSelectOfflineLight(
            primeOfflineSourceQueue(primeOfflineShaderRecord.control),
            DispatchRaysIndex().x);
}
