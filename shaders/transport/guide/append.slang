// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

#language slang 2026
module "transport/guide/append.slang";

import "prime_abi.slang";
import "prime_abi_types.slang";
import "math/queued_psr.slang";
import "service/bsdf/contract/sample.slang";

public PrimeQueuedPsrState primeAppendQueuedPsrDiscrete(
        PrimeQueuedPsrState state,
        float3 previousPosition,
        SurfaceInteraction surface,
        BsdfSample bsdf) {
    return primeAppendQueuedPsrState(
            state,
            primePush.cameraPosition,
            previousPosition,
            surface.position,
            surface.geometricNormal,
            (bsdf.eventFlags & PRIME_BSDF_EVENT_REFLECTION) != 0u);
}
