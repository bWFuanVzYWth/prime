// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

#language slang 2026
module "transport/scatter/visible.slang";


import "prime_abi_types.slang";
import "state/path.slang";
import "service/bsdf/contract/sample.slang";
import "policy/runtime/config.slang";
import "math/sampling.slang";
import "service/bsdf/dispatch/ordinary_sample.slang";
import "transport/scatter/surface.slang";
import "service/bsdf/dispatch/traits.slang";
import "service/trace/context.slang";
import "model/realtime/transport.slang";
import "transport/scatter/contract.slang";
import "transport/surface/access.slang";
import "transport/surface/accumulate.slang";
import "transport/surface/environment.slang";
import "contract/medium.slang";
import "contract/path.slang";
import "contract/realtime/ordinary.slang";

// surface_split routes the first transparent interface separately. This entry only receives
// an ordinary camera hit or a miss; later vertices use the same completion and family samplers.
public bool primeIntegrateVisibleSurface(
        inout PrimeIntegratorContext context,
        inout PathState path,
        inout PrimeMedium medium,
        inout PrimeDenoiserState denoiserState,
        inout PrimeOrdinarySample result,
        SurfaceInteraction surface) {
    if (!primeKnownHitKind(surface)) return false;
    if (surface.hitKind == PRIME_HIT_NONE) {
        primeAccumulate(context, result.radiance.stable,
                primeEvaluateEnvironmentContribution(context, path));
        return false;
    }
    float3 viewDirection = -path.rayDirection;
    PrimeClosureTraits closureTraits = primeSurfaceClosureTraits(
            surface, viewDirection, medium, primeUsesAirGap());
    PrimePreparedSampleBase preparedSample = primePreparePathSample(
            path.sampleSeed, primeSampleIndex(), primePathBounce(path.control) + 1u);
    float3 scatterSample = primeSobolSample3D(
            preparedSample, PRIME_SAMPLE_EFFECT_SCATTER_BSDF, PRIME_SAMPLE_DIMENSION_PRIMARY);
    PrimeGuidedPathScatter sampled = primeSampleOrdinaryPathSurface(
            surface, viewDirection, scatterSample, medium, true);
    return primeFinishWavefrontSurface(context, path, medium, denoiserState,
            result.guides, surface, sampled.scatter, sampled.albedos, closureTraits);
}
