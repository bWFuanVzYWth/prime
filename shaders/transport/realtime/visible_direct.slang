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
import "service/bsdf/support/volume_transition.slang";
import "service/camera/projection.slang";
import "service/trace/context.slang";
import "state/realtime/contract.slang";
import "state/realtime/direct_accumulation.slang";
import "state/realtime/guide.slang";
import "state/realtime/queue.slang";
import "state/realtime/surface.slang";
import "contract/light.slang";
import "contract/path.slang";
import "transport/direct/area.slang";
import "transport/direct/primary.slang";
import "transport/surface/medium.slang";
import "bsdf/compact/contract/volume.slang";
import "model/realtime/guide.slang";

void primeInitializeVisibleDirectSignals(uint2 pixel) {
    int2 coordinate = int2(pixel);
    primeImageStore(primeNrdNoisyDiffuse, coordinate, float4(0.0));
    primeImageStore(primeNrdNoisySpecular, coordinate, float4(0.0));
    primeImageStore(primeNrdSunLighting, coordinate, float4(0.0));
    primeImageStore(primeNrdSunPenumbra, coordinate, float4(0.0));
}

public void primeShadeVisibleDirect(
        inout PrimeIntegratorContext context,
        uint launchIndex) {
    uint recordIndex = primeWavefrontQueuedPath(
            PRIME_WAVEFRONT_AREA_QUEUE, launchIndex);
    SurfaceInteraction surface = primeLoadWavefrontSurface(
            recordIndex, uint2(PRIME_NO_PRIMITIVE_INDEX));
    uint2 pixel = primeWavefrontPixel(recordIndex);
    PrimeDenoiserGuides guides = primeEmptyDenoiserGuides();
    primeInitializeVisibleDirectSignals(pixel);
    primeStoreWavefrontArea(pixel, guides);
    float3 viewDirection = -primeCameraRayDirection(
            pixel, primeRealtimeCameraSample(pixel));
    float3 throughput = float3(1.0);
    PrimeOpenPbrVolumeStack volumeStack =
            (primePush.path.z & PRIME_PATH_CAMERA_IN_WATER_MASK) != 0u
            ? primeCameraWaterVolumeStack()
            : primeEmptyVolumeStack();
    if (!primeApplySegmentMedium(
            throughput, surface, volumeStack)) {
        return;
    }
    PrimeClosureTraits closureTraits = primeSurfaceClosureTraits(
            surface,
            viewDirection,
            volumeStack,
            primeUsesAirGap());
    if (!primeClosureSupports(closureTraits, PRIME_MEASURE_SOLID_ANGLE)) {
        return;
    }

    PrimeDirectSampleBase directSample = primeMakeDirectSampleBase(
            pixel,
            primeSampleIndex(),
            primeSampleEpoch(),
            1u,
            uint(recordIndex >= primeWavefrontPixelCount()));
    bool transparent = primeMaterialIsTransmissive(surface.materialControl);

    PrimeDirectLightingSplit area = primeEstimatePrimaryNonsunDirectSampled(
            context,
            surface,
            viewDirection,
            primeDirectSample2D(
                    directSample, PRIME_DIRECT_SAMPLE_AREA_PRIMARY),
            primeDirectSample2D(
                    directSample, PRIME_DIRECT_SAMPLE_AREA_SECONDARY),
            volumeStack,
            transparent);
    area.diffuse *= throughput;
    area.specular *= throughput;
    primeAccumulatePrimaryDirectSplit(pixel, area);

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
            viewDirection,
            primeDirectSample2D(
                    directSample, PRIME_DIRECT_SAMPLE_SUN),
            volumeStack,
            transparent);
    PrimeDirectLightingSplit direct = sun.lighting;
    direct.diffuse *= throughput;
    direct.specular *= throughput;
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
