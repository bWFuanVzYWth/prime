#language slang 2026
module "transport/realtime/landing_direct.slang";

import "prime_abi.slang";
import "prime_abi_types.slang";
import "prime_realtime_abi.slang";
import "contract/light.slang";
import "contract/light_sample.slang";
import "math/direct_sampling.slang";
import "model/atmosphere/sky.slang";
import "policy/runtime/config.slang";
import "service/light/area_sample.slang";
import "service/trace/context.slang";
import "state/realtime/contract.slang";
import "state/realtime/direct.slang";
import "state/realtime/direct_accumulation.slang";
import "state/realtime/staged_light.slang";
import "state/realtime/queue.slang";
import "state/realtime/surface.slang";
import "transport/direct/primary.slang";
import "transport/direct/selection.slang";
import "transport/surface/medium.slang";
import "transport/realtime/staged_hit_emission_direct.slang";
import "bsdf/compact/contract/volume.slang";

public void primeEvaluateLandingDirect(
        inout PrimeIntegratorContext context, uint launchIndex) {
    uint recordIndex = primeWavefrontQueuedPath(
            PRIME_WAVEFRONT_PRIMARY_QUEUE, launchIndex);
    PrimeStagedLightSelection selection =
            primeLoadStagedLightSelection(recordIndex);
    if (!primeStagedHitEnabled(selection)) {
        return;
    }
    PrimeWavefrontDirectState path =
            primeLoadWavefrontDirectState(recordIndex);
    uint control = path.control;
    uint2 pixel = primeWavefrontPixel(recordIndex);
    SurfaceInteraction surface = primeLoadWavefrontSurface(recordIndex);
    if (!primeApplySegmentMedium(
            path.throughput, surface, path.volumeStack)) {
        return;
    }

    bool guideDual = primeWavefrontGuideEnabled(control)
            && (control & PRIME_WAVEFRONT_REACHED_SOLID_ANGLE) == 0u;
    if (primeStagedDirectEnabled(selection)) {
        PrimeDirectSampleBase directSample = primeMakeDirectSampleBase(
                pixel,
                primeSampleIndex(),
                primeSampleEpoch(),
                path.bounce + 1u,
                uint(recordIndex >= primeWavefrontPixelCount()));
        float cellSample = primeDirectSample2D(
                directSample, PRIME_DIRECT_SAMPLE_AREA_PRIMARY).y;
        float3 receiverNormal = primeAreaLightReceiverNormal(
                surface, path.viewDirection);
        AreaLightSample area = primeSampleAreaLightSelection(
                surface.position,
                receiverNormal,
                selection.area,
                cellSample,
                primeDirectSample2D(
                        directSample, PRIME_DIRECT_SAMPLE_AREA_SECONDARY));
        IntegratorRecord integrator;
        integrator.sunDirectionIntensity = float4(
                primePush.sunDirection,
                ATM_SPACE_SUN_INTENSITY * primeSunRadianceMultiplier());
        integrator.environmentRadiance = float4(0.0);
        if (guideDual) {
            PrimePrimarySunSample sun = primeEstimatePrimaryDirectSun(
                    context,
                    integrator,
                    surface,
                    path.viewDirection,
                    primeDirectSample2D(
                            directSample, PRIME_DIRECT_SAMPLE_SUN),
                    path.volumeStack,
                    false);
            PrimeDirectLightingSplit nonsun =
                    primeEstimatePrimaryDirectPreparedAreaLight(
                            context,
                            surface,
                            path.viewDirection,
                            area,
                            path.volumeStack,
                            false);
            primeAccumulateTransparentPrimaryDirect(
                    pixel,
                    primeWavefrontTransmissionBranch(control),
                    path.throughput
                            * (sun.lighting.diffuse * sun.visibility
                                    + nonsun.diffuse),
                    path.throughput
                            * (sun.lighting.specular * sun.visibility
                                    + nonsun.specular));
        } else {
            float3 direct = primeEstimateDirectSun(
                    context,
                    integrator,
                    surface,
                    path.viewDirection,
                    primeDirectSample2D(
                            directSample, PRIME_DIRECT_SAMPLE_SUN),
                    path.volumeStack);
            direct += primeEstimateDirectPreparedAreaLight(
                    context,
                    surface,
                    path.viewDirection,
                    area,
                    path.volumeStack);
            float3 contribution = path.throughput * direct;
            if (any(contribution > float3(0.0))) {
                primeAccumulateDeferredDirectLight(
                        pixel, control, contribution);
            }
        }
    }

    bool previousUsedAreaNee = !primeWavefrontTransparentBranch(control)
            || path.bounce > 0u;
    PrimeWavefrontHitEmissionState hitPath =
            primeLoadWavefrontHitEmissionState(recordIndex);
    float3 emitted = primeEvaluateStagedHitEmission(
            surface, hitPath, path.throughput, previousUsedAreaNee);
    if (any(emitted > float3(0.0))) {
        bool diffuse = primeStagedHitEmissionDiffuse(
                recordIndex, surface, hitPath, true);
        if (guideDual) {
            primeAccumulateTransparentPrimaryDirect(
                    pixel,
                    primeWavefrontTransmissionBranch(control),
                    diffuse ? emitted : float3(0.0),
                    diffuse ? float3(0.0) : emitted);
        } else {
            primeAccumulateDeferredLight(
                    pixel, control, diffuse, emitted);
        }
    }
}
