#language slang 2026
module "phase/hit/shadow_anyhit.slang";


import glsl;
import "prime_abi_types.slang";
import "math/packed_geometry.slang";
import "policy/runtime/config.slang";
import "policy/transport/transparent_nee.slang";
import "model/material/defaults.slang";
import "service/material/access.slang";
import "model/material/transparent.slang";

import "service/trace/hit.slang";
import "contract/trace/shadow_payload.slang";

layout(location = 1) rayPayloadInEXT PrimeShadowPayload primeShadowPayload;

void primeAccumulateSolidMediumBoundary(
        inout PrimeShadowPayload primeShadowPayload,
        float3 extinction,
        bool entering) {
    int startingMediumIndex = primeShadowStartingMediumIndex(
            extinction,
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

float3 primeSurfaceBoundaryExtinction(PrimeSurfaceRelation relation) {
    if (((relation.control >> 8u) & PRIME_RECIPE_WATER) != 0u) {
        return PRIME_PURE_WATER_ABSORPTION_M_INV;
    }
    float opacity;
    float3 baseColor = primeStaticBaseColor(
            relation.textureId,
            relation.tint,
            primeUnpackUv(relation.referenceUv),
            0.0,
            opacity);
    return primeGlassShadowExtinction(baseColor, opacity);
}

public void primePhase(in BuiltInTriangleIntersectionAttributes attributes)
{
    float2 barycentrics = attributes.barycentrics;
    SectionRecord section = primeSection();
    uint triangleIndex = primeGlobalTriangleIndex(section);
    bool sameSourceSection = InstanceID()
            == primeShadowPayload.sourcePrimitive.x;
    if (sameSourceSection
            && (triangleIndex == primeShadowPayload.sourcePrimitive.y
                    || primeGlobalPrimitiveIndex(section)
                            == primeGlobalPrimitiveIndexFromTriangle(
                                    section,
                                    primeShadowPayload.sourcePrimitive.y))) {
        IgnoreHit();
        return;
    }
    PrimitiveRecord primitive = primePrimitive(section);
    bool targetSection = InstanceID() == primeShadowPayload.targetEmitter.x;
    if (targetSection
            && primePrimitiveEmitterIndex(primitive)
                    == primeShadowPayload.targetEmitter.y) {
        IgnoreHit();
        return;
    }
    // Opaque candidates require no material work once endpoint identities have been resolved.
    if (GeometryIndex() == 0u) {
        return;
    }
    PrimeSurfaceRelation relation = primeSurfaceRelation(section);
    PrimeHitTriangle hit = primeHitTriangle(section, barycentrics);
    float3 outwardNormal = hit.geometricNormal;
    primitive = primeResolveSurfacePrimitive(
            section,
            primitive,
            relation,
            barycentrics,
            hit.localPosition,
            outwardNormal);
    if (targetSection
            && primePrimitiveEmitterIndex(primitive)
                    == primeShadowPayload.targetEmitter.y) {
        IgnoreHit();
        return;
    }
    uint recipeControl = primePrimitiveControl(primitive);
    float2 materialUv = primeInterpolateUv(
            section,
            primitive,
            barycentrics,
            hit.localPosition,
            outwardNormal);
    uint textureId = primePrimitiveTextureId(section, primitive);
    float surfaceOpacity = primeEvaluateOpacity(primitive, textureId, materialUv);
    if ((recipeControl & PRIME_RECIPE_ALPHA_CUTOUT) != 0u
            && surfaceOpacity < PRIME_CUTOUT_ALPHA_THRESHOLD) {
        IgnoreHit();
        return;
    }
    if (!primeRecipeIsTransmissive(recipeControl)) {
        return;
    }
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
        float opacity;
        float3 baseColor = primeStaticBaseColor(
                textureId,
                primitive.tint,
                primePrimitiveMaterialReferenceUv(primitive),
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
                primeShadowPayload, extinction, entering);
        if ((relation.control & PRIME_SURFACE_RELATION_KIND_MASK)
                == PRIME_SURFACE_RELATION_BOUNDARY) {
            primeAccumulateSolidMediumBoundary(
                    primeShadowPayload,
                    primeShadowCanonicalExtinction(
                            primeSurfaceBoundaryExtinction(relation)),
                    !entering);
        }
    }
    IgnoreHit();
}
