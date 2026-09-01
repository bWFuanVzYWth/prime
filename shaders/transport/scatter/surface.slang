#language slang 2026
module "transport/scatter/surface.slang";

import "service/bsdf/support/sanitization.slang";

import "prime_abi.slang";
import "prime_abi_types.slang";
import "state/path.slang";
import "service/bsdf/contract/sample.slang";
import "service/reconstruct/nrd/common.slang";
import "math/packed_geometry.slang";
import "policy/runtime/config.slang";
import "math/sampling.slang";
import "model/material/defaults.slang";
import "service/bsdf/contract/albedo.slang";
import "service/bsdf/dispatch/sample.slang";
import "service/bsdf/dispatch/traits.slang";
import "service/trace/context.slang";
import "transport/direct/contract.slang";
import "model/realtime/transport.slang";
import "transport/scatter/ordinary.slang";
import "transport/scatter/contract.slang";
import "transport/scatter/path_update.slang";
import "transport/scatter/phase_contract.slang";
import "transport/surface/access.slang";
import "transport/surface/accumulate.slang";
import "transport/surface/environment.slang";
import "bsdf/compact/contract/volume.slang";
import "contract/path.slang";
import "contract/realtime/ordinary.slang";

// Processes exactly one ordinary realtime path vertex. Primary transparent interfaces remain a
// local island because two reconstruction records share one output pixel. Every other
// continuation can cross this boundary without carrying invocation-local BSDF state.
public bool primeIntegrateWavefrontSurface<
        Phase, SunNee, AreaNee, IndirectReuse>(
        Phase phase,
        SunNee sunNee,
        AreaNee areaNee,
        IndirectReuse indirectReuse,
        inout PrimeIntegratorContext context,
        inout PathState path,
        IntegratorRecord integrator,
        inout PrimeOpenPbrVolumeStack volumeStack,
        inout PrimeDenoiserState denoiserState,
        inout PrimeOrdinarySample result,
        SurfaceInteraction surface,
        bool skipRoulette,
        uint pathIndex)
    where Phase : IPrimeRealtimeTransportPhase
    where SunNee : IPrimeSecondarySunNee
    where AreaNee : IPrimeSecondaryAreaNee
    where IndirectReuse : IPrimeIndirectReuse
{
    if (!primeKnownHitKind(surface)) {
        return false;
    }

    if (denoiserState.hasPrimarySurface
            && primePathBounce(path.control) == phase.ordinaryGuideHitBounce(
                    denoiserState.primaryBounce)) {
        float firstBounceHitDistance = surface.hitKind == PRIME_HIT_NONE
                ? PRIME_NRD_FP16_MAX
                : primeNrdSanitizeHitDistance(surface.t);
        if (denoiserState.diffusePath) {
            result.guides.diffuseHitDistance = firstBounceHitDistance;
        } else {
            result.guides.specularHitDistance = firstBounceHitDistance;
        }
    }
    if (surface.hitKind == PRIME_HIT_NONE) {
        float3 contribution = primeEvaluateEnvironmentContribution(
                context, path, integrator);
        if (!denoiserState.hasPrimarySurface) {
            primeAccumulate(context, result.radiance.stable, contribution);
        } else {
            primeAccumulateAfterPrimary(
                    context, result, denoiserState.diffusePath, contribution);
        }
        return false;
    }
    PrimeIndirectReuseResult reuse;
    reuse.contribution = float3(0.0);
    reuse.hit = false;
    if (denoiserState.hasPrimarySurface) {
        reuse = indirectReuse.query(
                    context,
                    path,
                    surface,
                    denoiserState.hasPrimarySurface);
    }
    if (reuse.hit) {
        primeAccumulateAfterPrimary(
                context,
                result,
                denoiserState.diffusePath,
                reuse.contribution);
        return false;
    }

    float3 viewDirection = -path.rayDirection;
    bool transmissive = primeMaterialIsTransmissive(surface.materialControl);
    float surfaceLinearRoughness =
            (transmissive || !denoiserState.reachedSolidAngle)
            ? primeSurfaceLinearRoughness(surface)
            : PRIME_DEFAULT_REFERENCE_LINEAR_ROUGHNESS;
    PrimeClosureTraits closureTraits = primeSurfaceClosureTraits(
            surface, viewDirection, volumeStack, primeUsesAirGap());
    bool supportsSolidAngle = primeClosureSupports(
            closureTraits, PRIME_MEASURE_SOLID_ANGLE);
    bool discreteOnlyInterface = primeClosureIsDiscreteOnly(closureTraits);
    PrimePreparedSampleBase preparedSample =
            primePreparePathSample(
                    path.sampleSeed,
                    primeSampleIndex(),
                    primePathBounce(path.control) + 1u);
    if (supportsSolidAngle) {
        if (denoiserState.hasPrimarySurface) {
            float3 direct = path.throughput * primeEstimateDirectLighting(
                    sunNee,
                    areaNee,
                    context,
                    integrator,
                    surface,
                    viewDirection,
                    preparedSample,
                    volumeStack,
                    path.throughput,
                    pathIndex);
            primeAccumulateAfterPrimary(
                    context,
                    result,
                    denoiserState.diffusePath,
                    direct);
        }
    }

    float3 scatterSample = primeSobolSample3D(
            preparedSample,
            PRIME_SAMPLE_EFFECT_SCATTER_BSDF,
            PRIME_SAMPLE_DIMENSION_PRIMARY);
    PrimePathScatter scatter;
    PrimeDenoiseAlbedos surfaceAlbedos;
    surfaceAlbedos.diffuse = float3(0.0);
    surfaceAlbedos.specular = float3(0.0);
    if (!denoiserState.reachedSolidAngle) {
        PrimeGuidedPathScatter guided = primeSampleGuidedPathSurface(
                context,
                surface,
                viewDirection,
                scatterSample,
                volumeStack,
                primePathBounce(path.control),
                primeUsesAirGap());
        scatter = guided.scatter;
        surfaceAlbedos = guided.albedos;
    } else {
        scatter = primeSamplePathSurface(
                context,
                surface,
                viewDirection,
                scatterSample,
                volumeStack,
                primeUsesAirGap());
    }
    BsdfSample bsdf = scatter.bsdf;
    volumeStack = scatter.volumeStack;
    bool hasScatter = primeHasScatter(bsdf);
    if (hasScatter) {
    }
    bool sampledSolidAngle = primeIsSolidAngleSample(bsdf);
    if (!denoiserState.reachedSolidAngle) {
        bool firstDenoiseSurface = !denoiserState.hasPrimarySurface;
        if (firstDenoiseSurface) {
            denoiserState.specularAlbedoProduct += surfaceAlbedos.specular;
            denoiserState.diffuseAlbedoProduct *= surfaceAlbedos.diffuse;
            denoiserState.diffusePath = sampledSolidAngle
                    && (bsdf.eventFlags & PRIME_BSDF_EVENT_DIFFUSE) != 0u;
            denoiserState.hasPrimarySurface = true;
            denoiserState.primaryBounce = primePathBounce(path.control);
            result.guides.primaryDistance = length(
                    surface.position - primePush.cameraPosition);
            result.guides.primaryPosition = surface.position - primePush.cameraPosition;
            result.guides.primaryPreviousPosition =
                    primeSurfacePreviousPosition(
                            surface, primePush.sectionTableAddress)
                            - primePush.cameraPosition;
            result.guides.primaryHasMotion = primeSurfaceHasMotion(surface);
            result.guides.primaryAlbedo = primeSanitizeDenoiseAlbedo(
                    denoiserState.diffuseAlbedoProduct);
            result.guides.primaryNormal =
                    primeSurfaceShadingNormal(surface, viewDirection);
            result.guides.primaryHitKind = surface.hitKind;
            result.guides.primaryMaterialFlags = primeNrdGuideControl(
                    surface.materialControl, surface.opticalControl);
            result.guides.primarySpecularAlbedo = primeSanitizeDenoiseAlbedo(
                    denoiserState.specularAlbedoProduct);
            result.guides.primaryLinearRoughness = surfaceLinearRoughness;
            if (hasScatter) {
                if (denoiserState.diffusePath) {
                    result.guides.diffuseDirection = bsdf.direction;
                } else {
                    result.guides.specularDirection = bsdf.direction;
                }
            }
        }

        bool surfaceHasSolidAngleLobe = supportsSolidAngle;
        if (sampledSolidAngle || (!hasScatter && surfaceHasSolidAngleLobe)) {
            if (!firstDenoiseSurface) {
                denoiserState.specularAlbedoProduct *=
                        surfaceAlbedos.specular + surfaceAlbedos.diffuse;
            }
            denoiserState.reachedSolidAngle = true;
            result.guides.primarySpecularAlbedo = primeSanitizeDenoiseAlbedo(
                    denoiserState.specularAlbedoProduct);
        } else if (!firstDenoiseSurface && primeIsDiscreteSample(bsdf)) {
            denoiserState.specularAlbedoProduct *= surfaceAlbedos.specular;
            result.guides.primarySpecularAlbedo = primeSanitizeDenoiseAlbedo(
                    denoiserState.specularAlbedoProduct);
        }
    }
    if (!hasScatter
            || !primeAdvanceRealtimePath(
                    context,
                    path,
                    surface,
                    bsdf,
                    preparedSample,
                    discreteOnlyInterface,
                    skipRoulette)) {
        return false;
    }
    path.control = primeIncrementPathBounce(path.control);
    return true;
}
