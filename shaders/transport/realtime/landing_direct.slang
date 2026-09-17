// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

#language slang 2026
module "transport/realtime/landing_direct.slang";

import "prime_abi.slang";
import "prime_abi_types.slang";
import "prime_realtime_abi.slang";
import "contract/light.slang";
import "contract/light_sample.slang";
import "math/direct_sampling.slang";
import "math/direct_discrete_sampling.slang";
import "math/packed_geometry.slang";
import "model/atmosphere/sky.slang";
import "model/material/defaults.slang";
import "policy/runtime/config.slang";
import "service/light/area_sample.slang";
import "service/light/emission.slang";
import "service/trace/context.slang";
import "state/realtime/contract.slang";
import "state/realtime/direct.slang";
import "state/realtime/direct_accumulation.slang";
import "state/realtime/staged_light.slang";
import "state/realtime/queue.slang";
import "state/realtime/surface.slang";
import "transport/direct/primary.slang";
import "transport/surface/medium.slang";
import "transport/realtime/staged_hit_emission_direct.slang";
import "contract/medium.slang";

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
            path.throughput, surface, path.medium)) {
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
        IntegratorRecord integrator;
        integrator.sunDirectionIntensity = float4(
                primePush.sunDirection,
                ATM_SPACE_SUN_INTENSITY * primeSunRadianceMultiplier());
        PrimeDirectLightingSplit sun = primeEstimateDirectSunSplit(
                context, integrator, surface, path.viewDirection,
                primeDirectSample2D(directSample, PRIME_DIRECT_SAMPLE_SUN), path.medium);
        AreaLightSample area = primeSampleAreaLightSelection(
                surface.position,
                selection.area,
                primeDirectDiscreteSample1D(directSample, primeDirectDiscreteSeed(
                        pixel, primeSampleIndex(), primeSampleEpoch(), path.bounce + 1u,
                        uint(recordIndex >= primeWavefrontPixelCount())), 1u),
                primeDirectSample2D(directSample, PRIME_DIRECT_SAMPLE_AREA_SECONDARY));
        PrimeDirectLightingSplit nonsun = primeEstimateDirectAreaSplit(
                context, surface, path.viewDirection, area, path.medium, false);
        // Guide routing only partitions the common estimator's output; it cannot select MIS.
        if (guideDual) {
            primeAccumulateTransparentPrimaryDirect(
                    pixel, primeWavefrontTransmissionBranch(control),
                    path.throughput * (sun.diffuse + nonsun.diffuse),
                    path.throughput * (sun.specular + nonsun.specular));
        } else {
            float3 direct = sun.diffuse + sun.specular;
            direct += nonsun.diffuse + nonsun.specular;
            float3 contribution = path.throughput * direct;
            if (any(contribution > float3(0.0))) {
                primeAccumulateDeferredDirectLight(pixel, control, contribution);
            }
        }
    }

    // Visible emission also covers dynamic geometry whose emitter lane is a motion identity.
    if ((surface.materialControl & PRIME_MATERIAL_VISIBLE_EMISSION) == 0u
            && primeSurfaceEmitterIndex(surface) == PRIME_NO_LIGHT_INDEX) return;
    PrimeWavefrontHitEmissionState hitPath =
            primeLoadWavefrontHitEmissionState(recordIndex);
    float3 emitted = primeEvaluateStagedHitEmission(
            surface, hitPath, path.throughput, true);
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
