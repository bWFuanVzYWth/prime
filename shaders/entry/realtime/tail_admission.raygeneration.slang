#language slang 2026
module "entry/realtime/tail_admission.raygeneration.slang";

import "contract/realtime/shader_record.slang";
import "policy/realtime/fixed_queue.slang";
import "prime_realtime_abi.slang";
import "state/realtime/queue.slang";
import "transport/realtime/tail_admission.slang";

[shader("raygeneration")]
void main() {
    uint launchIndex = DispatchRaysIndex().x;
    if (launchIndex == 0u) {
        primeResetWavefrontQueue(PRIME_WAVEFRONT_AREA_QUEUE);
    }
    primeAdmitRealtimeTailPath(
            primeFixedSourceQueue(primeWavefrontShaderRecord.control),
            launchIndex);
}
