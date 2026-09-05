#language slang 2026
module "entry/hit/shadow_nonopaque.anyhit.slang";


import glsl;

import "service/material/coverage.slang";
import "model/material/recipe.slang";
import "prime_material_core_abi.slang";
import "prime_abi_types.slang";
import "math/packed_geometry.slang";
import "policy/runtime/config.slang";
import "policy/transport/transparent_nee.slang";
import "model/material/defaults.slang";
import "service/material/access.slang";
import "service/material/medium_access.slang";
import "service/material/static_color.slang";
import "model/material/transparent.slang";

import "service/trace/candidate_material.slang";
import "service/trace/candidate.slang";
import "service/trace/candidate_record.slang";
import "contract/trace/shadow_payload.slang";

layout(location = 1) rayPayloadInEXT PrimeShadowPayload primeShadowPayload;

void primeAccumulateSolidMediumBoundary(
        inout PrimeShadowPayload primeShadowPayload,
        uint mediumId,
        float3 extinction,
        bool entering) {
    int startingMediumIndex = primeShadowStartingMediumIndex(
            mediumId,
            primeShadowPayload.startingMediumIds,
            primeShadowPayload.startingExtinction0Winding,
            primeShadowPayload.startingExtinction1Winding,
            primeShadowPayload.startingMediumCount,
            entering);
    float3 boundaryExtinction = startingMediumIndex >= 0
            ? primeShadowStartingMediumCoefficient(
                    primeShadowPayload.startingExtinction0Winding.xyz,
                    primeShadowPayload.startingExtinction1Winding.xyz,
                    uint(startingMediumIndex))
            : extinction;
    primeShadowPayload.opticalDepthMomentHitDistance.xyz =
            primeShadowBoundaryOpticalDepthMoment(
                    primeShadowPayload.opticalDepthMomentHitDistance.xyz,
                    boundaryExtinction,
                    RayTCurrent(),
                    entering);
    if (startingMediumIndex == 0) {
        primeShadowPayload.startingExtinction0Winding.w += entering ? 1.0 : -1.0;
    } else if (startingMediumIndex == 1) {
        primeShadowPayload.startingExtinction1Winding.w += entering ? 1.0 : -1.0;
    } else {
        primeShadowPayload.terminalExtinctionRayDistance.xyz =
                primeShadowBoundaryTerminalExtinction(
                        primeShadowPayload.terminalExtinctionRayDistance.xyz,
                        boundaryExtinction,
                        entering);
    }
}

float3 primeSurfaceBoundaryExtinction(uint materialId, uint tint, uint referenceUv) {
    uint core = primeMaterialCoreWord(materialId);
    uint recipeControl = (core >> PRIME_MATERIAL_CORE_RECIPE_CONTROL_SHIFT)
            & PRIME_MATERIAL_CORE_RECIPE_CONTROL_MASK;
    if ((recipeControl & PRIME_RECIPE_WATER) != 0u) {
        return PRIME_PURE_WATER_ABSORPTION_M_INV;
    }
    float opacity;
    float3 baseColor = primeStaticBaseColor(
            core & PRIME_MATERIAL_CORE_TEXTURE_ID_MASK,
            tint,
            primeUnpackUv(referenceUv),
            0.0,
            opacity);
    return primeGlassShadowExtinction(baseColor, opacity);
}

[shader("anyhit")]
void main(in BuiltInTriangleIntersectionAttributes attributes)
{
    if (primeCandidateIsSource(primeShadowPayload.sourcePrimitive)) {
        IgnoreHit();
        return;
    }
    bool targetSection = InstanceID() == primeShadowPayload.targetEmitter.x;
    if (GeometryIndex() == 0u && !targetSection) return;
    if (targetSection && primeCandidateEmitter(primeCandidateRecord()) == primeShadowPayload.targetEmitter.y) {
        IgnoreHit();
        return;
    }
    if (GeometryIndex() == 0u) return;
    PrimeCandidateSurface surface = primeCandidateSurface(attributes.barycentrics);
    if (targetSection && primeCandidateEmitter(surface.record) == primeShadowPayload.targetEmitter.y) {
        IgnoreHit();
        return;
    }
    uint recipeControl = surface.recipe;
    bool cutout = (recipeControl & PRIME_RECIPE_ALPHA_CUTOUT) != 0u;
    bool glass = primeRecipeIsTransmissive(recipeControl) && (recipeControl & PRIME_RECIPE_WATER) == 0u;
    float surfaceOpacity = surface.opacity;
    if ((cutout || glass) && surfaceOpacity < 0.0) {
        surfaceOpacity = primeCandidateOpacity(surface.record, surface.core, attributes.barycentrics);
    }
    if (cutout && surfaceOpacity < PRIME_CUTOUT_ALPHA_THRESHOLD) {
        IgnoreHit();
        return;
    }
    if (!primeRecipeIsTransmissive(recipeControl)) return;
    // BSDF-only transparent transport is unbiased without MNEE: a shadow ray may retain
    // absorption from its starting medium, but it cannot cross a specular interface.
    PrimeTransparentNeeInterfaceDecision transparentNee =
            primeTransparentNeeInterfaceDecision(
                    primeUsesUnbiasedTransparentNee(),
                    primeShadowPayload.terminalExtinctionRayDistance.w,
                    RayTCurrent());
    primeShadowPayload.terminalExtinctionRayDistance.w =
            transparentNee.terminalDistance;
    if (transparentNee.blocks) {
        return;
    }
    float3 extinction;
    if ((recipeControl & PRIME_RECIPE_WATER) != 0u) {
        extinction = PRIME_PURE_WATER_ABSORPTION_M_INV;
    } else {
        uint textureId = primeCandidateTextureId(surface.record, surface.core);
        float opacity;
        float3 baseColor = primeStaticBaseColor(
                textureId,
                primeCandidateMaterialTint(surface.record),
                primeCandidateReferenceUv(surface.record),
                0.0,
                opacity);
        if (!primeGlassReferenceIsStained(opacity)
                && primeGlassTexelIsRough(
                        surfaceOpacity, primeUsesSeamlessGlass())) {
            // Clear-glass decoration is an opaque diffuse border, not part of the dielectric
            // volume. Accepting the candidate lets the ordinary closest-hit record occlusion.
            return;
        }
        extinction = primeGlassShadowExtinction(baseColor, opacity);
    }
    extinction = primeShadowCanonicalExtinction(extinction);
    float3 outwardNormal = primeCandidateNormal();
    float cosine = dot(outwardNormal, WorldRayDirection());
    if (primeRecipeIsThinWalled(recipeControl)) {
        primeShadowPayload.opticalDepthMomentHitDistance.xyz = primeShadowThinOpticalDepth(
                primeShadowPayload.opticalDepthMomentHitDistance.xyz,
                extinction,
                primeShadowPayload.terminalExtinctionRayDistance.w,
                cosine);
    } else {
        bool entering = cosine < 0.0;
        primeAccumulateSolidMediumBoundary(
                primeShadowPayload, primeCandidateMediumId(surface.record), extinction, entering);
        // Boundary metadata is needed only for a solid interface, after alpha and extinction.
        uint64_t relation = primeCandidateRelation(primeCandidateHeader(primeCandidateRecord()));
        if (relation != uint64_t(0) && (((uint*)relation)[0] & 0xfu) == 1u) {
            uint identity = ((uint*)relation)[2];
            uint materialId = primeMaterialId(identity);
            primeAccumulateSolidMediumBoundary(
                    primeShadowPayload, primeMaterialMediumId(materialId),
                    primeShadowCanonicalExtinction(primeSurfaceBoundaryExtinction(
                            materialId, identity & 0xffffu, ((uint*)relation)[1])), !entering);
        }
    }
    IgnoreHit();
}
