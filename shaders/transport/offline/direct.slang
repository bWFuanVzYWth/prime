#language slang 2026
module "transport/offline/direct.slang";

import "prime_abi.slang";
import "prime_abi_types.slang";
import "contract/light_sample.slang";
import "state/path.slang";
import "model/atmosphere/sky.slang";
import "service/reconstruct/nrd/common.slang";
import "math/sampling.slang";
import "policy/runtime/config.slang";
import "service/light/emission.slang";
import "service/light/area_sample.slang";
import "service/trace/context.slang";
import "state/offline/contract.slang";
import "state/offline/index.slang";
import "state/offline/queue.slang";
import "state/offline/staged_light.slang";
import "state/offline/surface.slang";
import "state/offline/transport.slang";
import "transport/direct/evaluate.slang";
import "transport/direct/primary.slang";
import "transport/direct/selection.slang";
import "transport/direct/shadow.slang";
import "transport/offline/context.slang";
import "transport/surface/access.slang";
import "transport/surface/accumulate.slang";
import "transport/surface/emission.slang";
import "transport/surface/environment.slang";
import "transport/surface/medium.slang";
import "bsdf/compact/contract/volume.slang";
import "contract/path.slang";

public void primeAccumulateOfflineAreaDirect(
        inout PrimeIntegratorContext context,
        inout float3 radiance,
        PathState path,
        SurfaceInteraction surface,
        float3 viewDirection,
        AreaLightSample area,
        PrimeOpenPbrVolumeStack volumeStack) {
    float3 shadingNormal = primeSurfaceShadingNormal(surface, viewDirection);
    float3 sourceRadiance = primeResolveSampledAreaLightRadiance(area);
    float3 contribution = path.throughput * primeEvaluateCompleteDirect(
            context,
            surface,
            viewDirection,
            shadingNormal,
            area.light,
            sourceRadiance,
            volumeStack,
            primeUsesAirGap(),
            true);
    bool valid = area.light.pdf > 0.0
            && area.light.distance > 0.0
            && any(greaterThan(contribution, float3(0.0)));
    if (!valid) {
        return;
    }
    PrimeShadowTrace shadow = primeTraceShadow(
            context,
            surface,
            area.light,
            uint2(area.sectionIndex, area.emitterIndex),
            volumeStack);
    if (shadow.hitDistance >= PRIME_NRD_FP16_MAX) {
        primeAccumulate(context, radiance, contribution * shadow.transmittance);
    }
}

public void primeEvaluateOfflineDirect(
        inout PrimeIntegratorContext context,
        uint sourceQueue,
        uint launchIndex) {
    uint pathIndex = primeOfflineQueuedPath(sourceQueue, launchIndex);
    PrimeOfflinePathRecord record = primeOfflinePaths[pathIndex];
    uint2 pixel = primeOfflinePixel(pathIndex);
    PathState path = primeOfflinePath(pixel, record.transport);
    PrimeOpenPbrVolumeStack volumeStack = primeOfflineVolumeStack(record.transport);
    float3 radiance = record.radianceAndPrimaryDistance.xyz;
    float primaryDistance = record.radianceAndPrimaryDistance.w;
    float3 primaryAlbedo = record.primaryAlbedoAndMaterialFlags.xyz;
    uint primaryMaterialFlags = asuint(record.primaryAlbedoAndMaterialFlags.w);
    SurfaceInteraction surface = primeLoadOfflineSurface(pathIndex);
    bool active = false;

    if (primeKnownHitKind(surface)) {
        if (surface.hitKind == PRIME_HIT_NONE) {
            primeAccumulate(
                    context,
                    radiance,
                    primeEvaluateEnvironmentContribution(
                            context, path, primeOfflineIntegrator()));
        } else if (primeApplySegmentMedium(
                path.throughput, surface, volumeStack)) {
            bool previousUsedAreaNee =
                    (primePathFlags(path.control)
                            & PRIME_OFFLINE_PATH_PREVIOUS_AREA_NEE) != 0u;
            primeAccumulate(
                    context,
                    radiance,
                    primeEvaluateHitEmission(
                            context, path, surface, previousUsedAreaNee));
            float3 viewDirection = -path.rayDirection;
            if (!(primaryDistance >= 0.0)) {
                primaryDistance = length(
                        surface.position - primePush.cameraPosition);
                primaryAlbedo = surface.baseColor;
                primaryMaterialFlags = primeNrdGuideControl(
                        surface.materialControl, surface.opticalControl);
            }

            PrimeOfflineStagedLightSelection selection =
                    primeLoadOfflineStagedLightSelection(pathIndex);
            bool usedAreaNee = primeOfflineStagedDirectEnabled(selection);
            float3 receiverNormal = usedAreaNee
                    ? primeAreaLightReceiverNormal(surface, viewDirection)
                    : float3(0.0);
            path.previousLightNormal = receiverNormal;
            if (usedAreaNee) {
                PrimePreparedSampleBase preparedSample = primePreparePathSample(
                        path.sampleSeed,
                        primeSampleIndex(),
                        primePathBounce(path.control) + 1u);
                primeAccumulate(
                        context,
                        radiance,
                        path.throughput * primeEstimateDirectSun(
                                context,
                                primeOfflineIntegrator(),
                                surface,
                                viewDirection,
                                primeSobolSample2D(
                                        preparedSample,
                                        PRIME_SAMPLE_EFFECT_DIRECT_SUN,
                                        PRIME_SAMPLE_DIMENSION_PRIMARY),
                                volumeStack));
                float3 areaSample = primeSobolSample3D(
                        preparedSample,
                        PRIME_SAMPLE_EFFECT_DIRECT_AREA_LIGHT,
                        PRIME_SAMPLE_DIMENSION_PRIMARY);
                AreaLightSample area = primeSampleAreaLightSelection(
                        surface.position,
                        receiverNormal,
                        selection.area,
                        areaSample.y,
                        primeSobolSample2D(
                                preparedSample,
                                PRIME_SAMPLE_EFFECT_DIRECT_AREA_LIGHT,
                                PRIME_SAMPLE_DIMENSION_SECONDARY));
                primeAccumulateOfflineAreaDirect(
                        context,
                        radiance,
                        path,
                        surface,
                        viewDirection,
                        area,
                        volumeStack);
            }
            active = true;
        }
    }

    record.transport = primeMakeOfflineTransport(path, volumeStack, active);
    record.radianceAndPrimaryDistance = float4(radiance, primaryDistance);
    record.primaryAlbedoAndMaterialFlags = float4(
            primaryAlbedo, asfloat(primaryMaterialFlags));
    primeOfflinePaths[pathIndex] = record;
}
