#language slang 2026
module "transport/realtime/visible_direct.slang";

import "prime_realtime_abi.slang";
import "prime_abi.slang";
import "prime_abi_types.slang";
import "service/bsdf/contract/sample.slang";
import "contract/realtime/guide.slang";
import "model/atmosphere/sky.slang";
import "policy/runtime/config.slang";
import "math/direct_sampling.slang";
import "model/material/defaults.slang";
import "service/bsdf/dispatch/traits.slang";
import "service/trace/context.slang";
import "state/realtime/contract.slang";
import "state/realtime/direct.slang";
import "state/realtime/direct_accumulation.slang";
import "state/realtime/guide.slang";
import "state/realtime/queue.slang";
import "state/realtime/surface.slang";
import "contract/light.slang";
import "transport/direct/area.slang";
import "transport/direct/primary.slang";
import "transport/surface/medium.slang";
import "bsdf/compact/contract/volume.slang";
import "model/realtime/guide.slang";

public void primeShadeVisibleDirect(
        inout PrimeIntegratorContext context,
        uint launchIndex) {
    uint recordIndex = primeWavefrontQueuedPath(
            PRIME_WAVEFRONT_AREA_QUEUE, launchIndex);
    SurfaceInteraction surface = primeLoadWavefrontSurface(recordIndex);
    uint2 pixel = primeWavefrontPixel(recordIndex);
    PrimeWavefrontDirectState path =
            primeLoadWavefrontDirectState(recordIndex);
    if (!primeApplySegmentMedium(
            path.throughput, surface, path.volumeStack)) {
        return;
    }
    PrimeClosureTraits closureTraits = primeSurfaceClosureTraits(
            surface,
            path.viewDirection,
            path.volumeStack,
            primeUsesAirGap());
    if (!primeClosureSupports(closureTraits, PRIME_MEASURE_SOLID_ANGLE)) {
        return;
    }

    PrimeDirectSampleBase directSample = primeMakeDirectSampleBase(
            pixel,
            primeSampleIndex(),
            primeSampleEpoch(),
            path.bounce + 1u,
            uint(recordIndex >= primeWavefrontPixelCount()));
    bool transparent = primeMaterialIsTransmissive(surface.materialControl);

    PrimeDirectLightingSplit area = primeEstimatePrimaryNonsunDirectSampled(
            context,
            surface,
            path.viewDirection,
            primeDirectSample2D(
                    directSample, PRIME_DIRECT_SAMPLE_AREA_PRIMARY),
            primeDirectSample2D(
                    directSample, PRIME_DIRECT_SAMPLE_AREA_SECONDARY),
            path.volumeStack,
            transparent);
    area.diffuse *= path.throughput;
    area.specular *= path.throughput;
    primeAccumulatePrimaryDirectSplit(pixel, area);

    PrimeDenoiserGuides guides = primeEmptyDenoiserGuides();
    guides.primaryAreaDiffuse = area.diffuse;
    guides.primaryAreaSpecular = area.specular;
    if (any(area.diffuse > float3(0.0))
            || any(area.specular > float3(0.0))) {
        guides.primaryAreaDirection = area.direction;
    }
    primeStoreWavefrontArea(pixel, guides);

    IntegratorRecord integrator;
    integrator.sunDirectionIntensity = float4(
            primePush.sunDirection,
            ATM_SPACE_SUN_INTENSITY * primeSunRadianceMultiplier());
    integrator.environmentRadiance = float4(0.0);
    PrimePrimarySunSample sun = primeEstimatePrimaryDirectSun(
            context,
            integrator,
            surface,
            path.viewDirection,
            primeDirectSample2D(
                    directSample, PRIME_DIRECT_SAMPLE_SUN),
            path.volumeStack,
            transparent);
    PrimeDirectLightingSplit direct = sun.lighting;
    direct.diffuse *= path.throughput;
    direct.specular *= path.throughput;
    if (transparent) {
        direct.diffuse *= sun.visibility;
        direct.specular *= sun.visibility;
        primeAccumulatePrimaryDirectSplit(pixel, direct);
    } else {
        primeStoreWavefrontPrimarySun(
                pixel,
                direct.diffuse + direct.specular,
                sun.visibility,
                sun.penumbra);
    }
}
