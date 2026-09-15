// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

#language slang 2026
module "transport/realtime/secondary_direct.slang";

import "prime_abi.slang";
import "prime_abi_types.slang";
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
import "bsdf/compact/contract/volume.slang";

public void primeEvaluateSecondaryDirect(
        inout PrimeIntegratorContext context,
        uint sourceQueue,
        uint launchIndex) {
    uint recordIndex = primeWavefrontQueuedPath(sourceQueue, launchIndex);
    PrimeStagedLightSelection selection =
            primeLoadStagedLightSelection(recordIndex);
    if (!primeStagedHitEnabled(selection)) {
        return;
    }
    uint2 pixel = primeWavefrontPixel(recordIndex);
    PrimeWavefrontDirectState path =
            primeLoadWavefrontDirectState(recordIndex);
    SurfaceInteraction surface = primeLoadWavefrontSurface(recordIndex);
    if (!primeApplySegmentMedium(
            path.throughput, surface, path.medium)) {
        return;
    }

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
        float3 direct = primeEstimateDirectSun(
                context,
                integrator,
                surface,
                path.viewDirection,
                primeDirectSample2D(
                        directSample, PRIME_DIRECT_SAMPLE_SUN),
                path.medium);
        AreaLightSample area = primeSampleAreaLightSelection(
                surface.position,
                selection.area,
                primeDirectDiscreteSample1D(directSample, primeDirectDiscreteSeed(pixel, primeSampleIndex(), primeSampleEpoch(),
                        path.bounce + 1u, uint(recordIndex >= primeWavefrontPixelCount())), 1u),
                primeDirectSample2D(directSample, PRIME_DIRECT_SAMPLE_AREA_SECONDARY));
        direct += primeEstimateDirectPreparedAreaLight(
                context,
                surface,
                path.viewDirection,
                area,
                path.medium);
        float3 contribution = path.throughput * direct;
        if (any(contribution > float3(0.0))) {
            primeAccumulateDeferredDirectLight(
                    pixel,
                    path.control,
                    contribution);
        }
    }
    // Most receivers cannot emit. Do not load the previous origin, PDF, normal or guide state.
    if ((surface.materialControl & PRIME_MATERIAL_VISIBLE_EMISSION) == 0u
            && primeSurfaceEmitterIndex(surface) == PRIME_NO_LIGHT_INDEX) return;
    PrimeWavefrontHitEmissionState hitPath =
            primeLoadWavefrontHitEmissionState(recordIndex);
    float3 emitted = primeEvaluateStagedHitEmission(
            surface, hitPath, path.throughput, true);
    if (any(emitted > float3(0.0))) {
        primeAccumulateDeferredLight(
                pixel,
                path.control,
                primeStagedHitEmissionDiffuse(
                        recordIndex, surface, hitPath, true),
                emitted);
    }
}
