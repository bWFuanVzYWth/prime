#language slang 2026
module "state/lambert_hit.slang";
import "prime_abi.slang";
import "contract/lambert.slang";
import "state/lambert.slang";
import "math/lambert_transport.slang";
import "model/lambert/path.slang";

public void primeLambertPublishHit(uint index, PrimeLambertPayload hit, float3 throughput, bool exhausted) {
    primeLambertPaths[index].throughput.xyz = throughput;
    if (exhausted) {
        primeLambertPaths[index].identity.w |= PRIME_LAMBERT_LIMIT;
        primeLambertPaths[index].normalDistance.w = -2.0;
        return;
    }
    uint completed = primeLambertPaths[index].identity.z;
    primeLambertRecordHitDistance(primeLambertPaths[index].radiance.w, completed, hit.distance);
    primeLambertPaths[index].normalDistance = float4(hit.normal, hit.distance);
    if (hit.distance < 0.0) return;
    primeLambertPaths[index].origin.xyz = hit.position;
    primeLambertPaths[index].albedo.xyz = hit.albedo;
    primeLambertPaths[index].identity.xy = uint2(hit.section, hit.triangle);
    if (primeLambertIncludesEmission(completed, (hit.flags & PRIME_LAMBERT_EMITTER) != 0u)) {
        float3 emission = throughput * hit.auxiliary;
        primeLambertPaths[index].radiance.rgb += emission;
    }
}
