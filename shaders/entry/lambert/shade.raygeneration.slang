// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

#language slang 2026
module "entry/lambert/shade.raygeneration.slang";
import "state/lambert.slang";
import glsl;
import "state/lambert_queue.slang";
import "transport/lambert/continuation.slang";
import "transport/lambert/environment.slang";
import "transport/lambert/sun.slang";
struct PrimeLambertShaderRecord { uint queue; };
[[vk::shader_record]] ConstantBuffer<PrimeLambertShaderRecord> primeLambertShaderRecord;
[shader("raygeneration")]
void main() {
    uint launch = DispatchRaysIndex().x;
    uint queue = primeLambertShaderRecord.queue;
    uint index = primeLambertQueuedPath(queue, launch);
    if (launch == 0u) primeLambertQueue[primeLambertCommandWord(queue)] = 0u;
    bool continuation = !primeFinishLambert(index);
    if (continuation) {
        PrimeLambertSunSample sun = primeLambertSun(index);
        if (sun.blockerDistance < 0.0) primeLambertPaths[index].radiance.rgb += sun.radiance;
        continuation = primeContinueLambert(index);
    }
    primeLambertAppend(queue ^ 1u, index, continuation);
}
