// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

#language slang 2026
module "entry/lambert/terminal.raygeneration.slang";
import "state/lambert.slang";
import glsl;
import "transport/lambert/environment.slang";
struct PrimeLambertShaderRecord { uint queue; };
[[vk::shader_record]] ConstantBuffer<PrimeLambertShaderRecord> primeLambertShaderRecord;
[shader("raygeneration")]
void main() {
    uint launch = DispatchRaysIndex().x;
    uint queue = primeLambertShaderRecord.queue;
    uint index = primeLambertQueuedPath(queue, launch);
    if (launch == 0u) primeLambertQueue[primeLambertCommandWord(queue)] = 0u;
    primeFinishLambert(index);
}
