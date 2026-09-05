#language slang 2026
module "transport/direct/immediate.slang";

import "prime_abi_types.slang";
import "contract/light.slang";
import "math/direct_sampling.slang";
import "math/direct_discrete_sampling.slang";
import "math/sampling.slang";
import "service/trace/context.slang";
import "transport/direct/contract.slang";
import "transport/direct/area.slang";
import "transport/direct/primary.slang";
import "bsdf/compact/contract/volume.slang";

public struct PrimeImmediateSecondarySunNee : IPrimeSecondarySunNee {
    public PrimeDirectSampleBase directSample;

    float3 estimate(
            inout PrimeIntegratorContext context,
            IntegratorRecord integrator,
            SurfaceInteraction surface,
            float3 viewDirection,
            PrimePreparedSampleBase preparedSample,
            PrimeOpenPbrVolumeStack volumeStack,
            float3 pathThroughput,
            uint pathIndex) {
        return primeEstimateDirectSun(
                context,
                integrator,
                surface,
                viewDirection,
                primeDirectSample2D(
                        this.directSample, PRIME_DIRECT_SAMPLE_SUN),
                volumeStack);
    }
}

public struct PrimeImmediateSecondaryAreaNee : IPrimeSecondaryAreaNee {
    public PrimeDirectSampleBase directSample;

    bool enabled() { return true; }
    PrimeDirectLightingSplit estimatePrimary(
            inout PrimeIntegratorContext context,
            SurfaceInteraction surface,
            float3 viewDirection,
            PrimePreparedSampleBase preparedSample,
            PrimeOpenPbrVolumeStack volumeStack,
            bool conditionalTransparentBranch) {
        return primeEstimatePrimaryNonsunDirectSampled(
                context,
                surface,
                viewDirection,
                primeDirectDiscreteSample2D(this.directSample,
                        primeDirectDiscreteSeed(preparedSample.seed, preparedSample.sampleIndex)),
                primeDirectSample2D(
                        this.directSample, PRIME_DIRECT_SAMPLE_AREA_SECONDARY),
                volumeStack,
                conditionalTransparentBranch);
    }
    float3 estimate(
            inout PrimeIntegratorContext context,
            SurfaceInteraction surface,
            float3 viewDirection,
            PrimePreparedSampleBase preparedSample,
            PrimeOpenPbrVolumeStack volumeStack) {
        return primeEstimateDirectAreaLight(
                context,
                surface,
                viewDirection,
                primeDirectDiscreteSample2D(this.directSample,
                        primeDirectDiscreteSeed(preparedSample.seed, preparedSample.sampleIndex)),
                primeDirectSample2D(
                        this.directSample, PRIME_DIRECT_SAMPLE_AREA_SECONDARY),
                volumeStack);
    }
}
