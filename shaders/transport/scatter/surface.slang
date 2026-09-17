// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

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
import "model/material/defaults.slang";
import "service/bsdf/contract/albedo.slang";
import "service/bsdf/dispatch/traits.slang";
import "service/trace/context.slang";
import "model/realtime/transport.slang";
import "transport/scatter/ordinary.slang";
import "transport/scatter/contract.slang";
import "transport/scatter/path_update.slang";
import "transport/surface/access.slang";
import "contract/medium.slang";
import "contract/path.slang";
import "contract/realtime/ordinary.slang";
import "contract/realtime/guide.slang";

public bool primeFinishWavefrontSurface(
        inout PrimeIntegratorContext context,
        inout PathState path,
        inout PrimeMedium medium,
        inout PrimeDenoiserState denoiserState,
        inout PrimeDenoiserGuides guides,
        SurfaceInteraction surface,
        PrimePathScatter scatter,
        PrimeDenoiseAlbedos surfaceAlbedos,
        PrimeClosureTraits closureTraits) {
    float3 viewDirection = -path.rayDirection;
    bool transmissive = primeMaterialIsTransmissive(surface.materialControl);
    float surfaceLinearRoughness = (transmissive || !denoiserState.reachedSolidAngle)
            ? primeSurfaceLinearRoughness(surface) : PRIME_DEFAULT_REFERENCE_LINEAR_ROUGHNESS;
    bool supportsSolidAngle = primeClosureSupports(closureTraits, PRIME_MEASURE_SOLID_ANGLE);
    bool discreteOnlyInterface = primeClosureIsDiscreteOnly(closureTraits);
    BsdfSample bsdf = scatter.bsdf;
    medium = scatter.medium;
    bool hasScatter = primeHasScatter(bsdf);
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
            guides.primaryDistance = length(
                    surface.position - primePush.cameraPosition);
            guides.primaryPosition = surface.position - primePush.cameraPosition;
            guides.primaryPreviousPosition =
                    primeSurfacePreviousPosition(
                            surface, primePush.sectionTableAddress)
                            - primePush.cameraPosition;
            guides.primaryHasMotion = primeSurfaceHasMotion(surface);
            guides.primaryAlbedo = primeSanitizeDenoiseAlbedo(
                    denoiserState.diffuseAlbedoProduct);
            guides.primaryNormal =
                    primeSurfaceShadingNormal(surface, viewDirection);
            guides.primaryHitKind = surface.hitKind;
            guides.primaryMaterialFlags = primeNrdGuideControl(
                    surface.materialControl, surface.opticalControl);
            guides.primarySpecularAlbedo = primeSanitizeDenoiseAlbedo(
                    denoiserState.specularAlbedoProduct);
            guides.primaryLinearRoughness = surfaceLinearRoughness;
            if (hasScatter) {
                if (denoiserState.diffusePath) {
                    guides.diffuseDirection = bsdf.direction;
                } else {
                    guides.specularDirection = bsdf.direction;
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
            guides.primarySpecularAlbedo = primeSanitizeDenoiseAlbedo(
                    denoiserState.specularAlbedoProduct);
        } else if (!firstDenoiseSurface && primeIsDiscreteSample(bsdf)) {
            denoiserState.specularAlbedoProduct *= surfaceAlbedos.specular;
            guides.primarySpecularAlbedo = primeSanitizeDenoiseAlbedo(
                    denoiserState.specularAlbedoProduct);
        }
    }
    if (!hasScatter
            || !primeAdvanceRealtimePath(
                    context,
                    path,
                    surface,
                    bsdf,
                    discreteOnlyInterface)) {
        return false;
    }
    path.control = primeIncrementPathBounce(path.control);
    return true;
}
