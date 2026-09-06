#language slang 2026
module "entry/lambert/trace.raygeneration.slang";
import "model/lambert/medium.slang";
import "state/lambert.slang";
import "contract/lambert.slang";
import glsl;
import "state/lambert_transport.slang";
import "state/lambert_hit.slang";
import "service/trace/lambert_walk.slang";
struct PrimeLambertShaderRecord { uint queue; };
[[vk::shader_record]] ConstantBuffer<PrimeLambertShaderRecord> primeLambertShaderRecord;
[shader("raygeneration")]
void main() {
    uint index = primeLambertQueuedPath(primeLambertShaderRecord.queue, DispatchRaysIndex().x);
    float3 origin = primeLambertPaths[index].origin.xyz;
    float3 direction = primeLambertPaths[index].direction.xyz;
    float3 throughput = primeLambertPaths[index].throughput.xyz;
    uint2 source = primeLambertPaths[index].identity.xy;
    PrimeLambertMedium medium = primeLambertLoadMedium(index);
    bool exhausted;
    PrimeLambertPayload hit = primeWalkLambert(origin, direction, source, medium, throughput,
            0u, primeLambertDeltaLimit(), 0u, exhausted);
    primeLambertPublishHit(index, hit, throughput, exhausted);
    primeLambertStoreMedium(index, medium);
}
