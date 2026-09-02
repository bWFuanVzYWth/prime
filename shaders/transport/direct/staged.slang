#language slang 2026
module "transport/direct/staged.slang";

import "prime_abi_types.slang";
import "contract/light.slang";
import "math/sampling.slang";
import "service/trace/context.slang";
import "transport/direct/contract.slang";
import "bsdf/compact/contract/volume.slang";

// Fixed-prefix Scatter has already consumed Direct for this vertex. The interface still reports
// an enabled competing technique so emitter MIS remains identical.
public struct PrimeFixedSecondaryAreaNee : IPrimeSecondaryAreaNee {
    bool enabled() { return true; }
    PrimeDirectLightingSplit estimatePrimary(
            inout PrimeIntegratorContext context,
            SurfaceInteraction surface,
            float3 viewDirection,
            PrimePreparedSampleBase preparedSample,
            PrimeOpenPbrVolumeStack volumeStack,
            bool conditionalTransparentBranch) {
        PrimeDirectLightingSplit result;
        result.diffuse = float3(0.0);
        result.specular = float3(0.0);
        result.direction = float3(0.0);
        return result;
    }
    float3 estimate(
            inout PrimeIntegratorContext context,
            SurfaceInteraction surface,
            float3 viewDirection,
            PrimePreparedSampleBase preparedSample,
            PrimeOpenPbrVolumeStack volumeStack) {
        return float3(0.0);
    }
}
