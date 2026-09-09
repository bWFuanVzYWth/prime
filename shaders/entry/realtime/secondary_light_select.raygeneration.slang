// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

#language slang 2026
module "entry/realtime/secondary_light_select.raygeneration.slang";

import "contract/realtime/shader_record.slang";
import "policy/realtime/secondary_queue.slang";
import "transport/realtime/secondary_light_select.slang";

[shader("raygeneration")]
void main() {
    primeSelectSecondaryLight(
            primeSecondarySourceQueue(primeWavefrontShaderRecord.control),
            DispatchRaysIndex().x);
}
