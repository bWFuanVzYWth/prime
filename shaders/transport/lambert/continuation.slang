#language slang 2026
module "transport/lambert/continuation.slang";
import "contract/lambert.slang";
import "prime_abi.slang";
import "state/lambert.slang";
import "math/lambert.slang";
import "math/lambert_roulette.slang";
import "math/lambert_transport.slang";
import "math/direct_sampling.slang";
import "policy/runtime/config.slang";

public bool primeContinueLambert(uint index) {
    uint completed = primeLambertPaths[index].identity.z + 1u;
    float3 throughput = primeLambertPaths[index].throughput.rgb * primeLambertPaths[index].albedo.rgb;
    if (!any(throughput > 0.0)) return false;
    uint2 pixel = primeLambertPixel(index);
    if (primeLambertNeedsRoulette(completed, primeLambertMinimumBounces(), primeMaximumBounces())
            && !primeLambertRoulette(throughput, primeLambertRouletteSample(
                    pixel, primeSampleIndex(), primeSampleEpoch(), completed))) return false;
    uint branch = uint((primeLambertPaths[index].identity.w & PRIME_LAMBERT_REFLECTION) != 0u);
    // Even vertices own lighting, odd vertices own continuation. Vertex zero is first-interface STBN.
    PrimeDirectSampleBase samples = primeMakeDirectSampleBase(
            pixel, primeSampleIndex(), primeSampleEpoch(), 2u * completed + 1u, branch);
    float3 direction = primeLambertDirection(primeLambertPaths[index].normalDistance.xyz,
            primeDirectSample2D(samples, 0u));
    primeLambertPaths[index].direction.xyz = direction;
    primeLambertPaths[index].throughput.rgb = throughput;
    primeLambertPaths[index].identity.z = completed;
    return true;
}
