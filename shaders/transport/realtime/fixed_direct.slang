#language slang 2026
module "transport/realtime/fixed_direct.slang";

import "prime_abi.slang";
import "prime_abi_types.slang";
import "contract/light_sample.slang";
import "contract/path.slang";
import "math/direct_sampling.slang";
import "model/atmosphere/sky.slang";
import "policy/runtime/config.slang";
import "service/light/area_sample.slang";
import "service/trace/context.slang";
import "state/path.slang";
import "state/realtime/contract.slang";
import "state/realtime/staged_light.slang";
import "state/realtime/light.slang";
import "state/realtime/ordinary.slang";
import "state/realtime/queue.slang";
import "state/realtime/surface.slang";
import "transport/direct/primary.slang";
import "transport/direct/selection.slang";
import "transport/surface/medium.slang";
import "bsdf/compact/contract/volume.slang";

public void primeEvaluateFixedDirect(
        inout PrimeIntegratorContext context,
        uint sourceQueue,
        uint launchIndex) {
    uint recordIndex = primeWavefrontQueuedPath(sourceQueue, launchIndex);
    PrimeStagedLightSelection selection =
            primeLoadStagedLightSelection(recordIndex);
    if (!primeStagedDirectEnabled(selection)) {
        return;
    }
    PrimeWavefrontTransportRecord record =
            primeLoadWavefrontTransportRecord(recordIndex);
    uint2 pixel = primeWavefrontPixel(recordIndex);
    PathState path = primeWavefrontPath(pixel, record);
    PrimeOpenPbrVolumeStack volumeStack = primeWavefrontVolumeStack(record);
    SurfaceInteraction surface = primeLoadWavefrontSurface(recordIndex);
    if (!primeApplySegmentMedium(
            context, path, surface, volumeStack)) {
        return;
    }

    PrimeDirectSampleBase directSample = primeMakeDirectSampleBase(
            pixel,
            primeSampleIndex(),
            primeSampleEpoch(),
            primePathBounce(path.control) + 1u,
            uint(recordIndex >= primeWavefrontPixelCount()));
    float2 areaSample = primeDirectSample2D(
            directSample, PRIME_DIRECT_SAMPLE_AREA_PRIMARY);
    float3 viewDirection = -path.rayDirection;
    float3 receiverNormal = primeAreaLightReceiverNormal(
            surface, viewDirection);
    AreaLightSample area = primeSampleAreaLightSelection(
            surface.position,
            receiverNormal,
            selection.area,
            areaSample.y,
            primeDirectSample2D(
                    directSample, PRIME_DIRECT_SAMPLE_AREA_SECONDARY));
    IntegratorRecord integrator;
    integrator.sunDirectionIntensity = float4(
            primePush.sunDirection,
            ATM_SPACE_SUN_INTENSITY * primeSunRadianceMultiplier());
    integrator.environmentRadiance = float4(0.0);
    float3 direct = primeEstimateDirectSun(
            context,
            integrator,
            surface,
            viewDirection,
            primeDirectSample2D(
                    directSample, PRIME_DIRECT_SAMPLE_SUN),
            volumeStack);
    direct += primeEstimateDirectPreparedAreaLight(
            context,
            surface,
            viewDirection,
            area,
            volumeStack);
    float3 contribution = path.throughput * direct;
    if (any(contribution > float3(0.0))) {
        primeAccumulateDeferredDirectLight(
                context,
                pixel,
                asuint(record.rayDirectionAndDenoiserControl.w),
                contribution);
    }
}
