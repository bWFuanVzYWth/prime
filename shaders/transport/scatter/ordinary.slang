// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

#language slang 2026
module "transport/scatter/ordinary.slang";

import "prime_abi_types.slang";
import "state/path.slang";
import "service/bsdf/contract/sample.slang";
import "service/trace/context.slang";
import "transport/direct/selection.slang";
import "transport/scatter/path_update.slang";

// BSDF advancement does not run RR. The queued continuation publisher owns that policy.
public bool primeAdvanceRealtimePath(
        inout PrimeIntegratorContext context,
        inout PathState path,
        SurfaceInteraction surface,
        BsdfSample bsdf,
        bool discreteOnlyInterface) {
    float3 previousLightNormal = discreteOnlyInterface
            ? float3(0.0)
            : primeAreaLightReceiverNormal(surface, -path.rayDirection);
    return primeAdvancePathWithoutRoulette(context, path, surface, bsdf, previousLightNormal);
}
