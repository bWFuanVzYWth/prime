#language slang 2026
module "entry/realtime/fixed_light_select.raygeneration.slang";

import "contract/realtime/shader_record.slang";
import "policy/realtime/fixed_queue.slang";
import "transport/realtime/fixed_light_select.slang";

[shader("raygeneration")]
void main() {
    primeSelectFixedLight(
            primeFixedSourceQueue(primeWavefrontShaderRecord.control),
            DispatchRaysIndex().x);
}
