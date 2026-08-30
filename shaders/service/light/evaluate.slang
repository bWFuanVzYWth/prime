#language slang 2026
module "service/light/evaluate.slang";

import "prime_abi.slang";
import "prime_abi_types.slang";
import "contract/light_sample.slang";
import "math/packed_geometry.slang";
import "math/projected_solid_angle_domain.slang";
import "service/material/surface.slang";
import "service/light/area_common.slang";
import "service/light/emission.slang";
import "service/light/tree_common.slang";
import "service/light/tree_pdf.slang";

public LightEvaluation primeEvaluateAreaLight(
        SurfaceInteraction surface,
        float3 rayOrigin,
        float3 rayDirection,
        float3 receiverNormal,
        bool evaluatePdf) {
    LightEvaluation result;
    result.radiance = float3(0.0);
    result.pdf = 0.0;
    if (!evaluatePdf) {
        result.radiance = primeEvaluateHitEmitterRadiance(
                surface, rayDirection);
        return result;
    }
    uint emitterIndex = primeSurfaceEmitterIndex(surface);
    if (emitterIndex == PRIME_NO_LIGHT_INDEX) {
        return result;
    }
    SectionRecord* sections = (SectionRecord*)primePush.sectionTableAddress;
    uint64_t sectionLightAddress = sections[surface.sectionIndex].lightAddress;
    if (sectionLightAddress == uint64_t(0)) {
        return result;
    }
    SectionLightHeader* sectionBuffer = (SectionLightHeader*)sectionLightAddress;
    if (emitterIndex >= sectionBuffer[0].emitterCount) {
        return result;
    }
    LightEmitter* emitters = (LightEmitter*)sectionBuffer[0].emitterAddress;
    float4 emitterCornerArea = emitters[emitterIndex].cornerArea;
    float lightCosine = primeEmitterCosine(
            emitters[emitterIndex].normalPadding.xyz,
            emitters[emitterIndex].metadata.z,
            rayDirection);
    if (!(lightCosine > 0.0) || !(emitterCornerArea.w > 0.0)) {
        return result;
    }

    float3 sectionTranslation = sections[surface.sectionIndex].translation;
    float3 localPosition = surface.position - sectionTranslation;
    float3 relative = localPosition - emitterCornerArea.xyz;
    float3 firstEdge = emitters[emitterIndex].edgeOneScale.xyz;
    float3 secondEdge = emitters[emitterIndex].edgeTwoPower.xyz;
    float3 edgeCross = cross(firstEdge, secondEdge);
    float denominator = dot(edgeCross, edgeCross);
    float2 parentBarycentric = float2(
            dot(cross(relative, secondEdge), edgeCross) / denominator,
            dot(cross(firstEdge, relative), edgeCross) / denominator);
    result.radiance = primeEvaluateEmitterRadiance(
            emitters[emitterIndex].metadata.w,
            emitters[emitterIndex].uvsTint.w,
            emitters[emitterIndex].metadata.z,
            emitters[emitterIndex].edgeOneScale.w,
            primeEmitterUv(
                    emitters[emitterIndex].uvsTint.xyz,
                    parentBarycentric),
            0.0);
    uint64_t worldNodeAddress = sections[surface.sectionIndex].worldLightAddress;
    uint64_t worldLeafAddress =
            sections[surface.sectionIndex].worldLightLeafAddress;
    uint worldLeafCount = sections[surface.sectionIndex].worldLightLeafCount;
    uint worldLightPath = sections[surface.sectionIndex].worldLightPath;
    if (worldNodeAddress == uint64_t(0)
            || worldLeafAddress == uint64_t(0)
            || worldLeafCount == 0u
            || worldLightPath == PRIME_NO_LIGHT_INDEX) {
        return result;
    }
    LightCell* cells = (LightCell*)sectionBuffer[0].cellAddress;
    uint cellIndex = primeLightCellIndex(parentBarycentric);
    uint emitterCellBase = emitters[emitterIndex].metadata.x;
    float cellProbabilityMass = cells[emitterCellBase + cellIndex].probabilityMass;
    float cellArea = emitterCornerArea.w / float(PRIME_LIGHT_CELL_COUNT);
    if (!(cellArea > 0.0)) {
        return result;
    }
    LightNode* worldNodes = primeWorldLightNodes(worldNodeAddress);
    LightLeaf* worldLeaves = (LightLeaf*)worldLeafAddress;
    LightLeafEntry* worldEntries = (LightLeafEntry*)(
            worldLeafAddress
                    + uint64_t(worldLeafCount) * uint64_t(PRIME_LIGHT_LEAF_SIZE));
    float worldPdf = primeLightTreeSelectionPdf(
            worldNodes,
            worldLeaves,
            worldEntries,
            sections,
            uint64_t(0),
            PRIME_NO_LIGHT_INDEX,
            PRIME_LIGHT_LEAF_WORLD,
            0u,
            worldLightPath,
            surface.sectionIndex,
            rayOrigin,
            receiverNormal);
    float sectionPdf = primeLightTreeSelectionPdf(
            (LightNode*)sectionBuffer[0].nodeAddress,
            (LightLeaf*)sectionBuffer[0].leafAddress,
            (LightLeafEntry*)sectionBuffer[0].entryAddress,
            sections,
            sectionBuffer[0].emitterAddress,
            sectionBuffer[0].emitterCount,
            PRIME_LIGHT_LEAF_EMITTER,
            sectionBuffer[0].root,
            emitters[emitterIndex].metadata.y,
            emitterIndex,
            rayOrigin - sectionTranslation,
            receiverNormal);
    if (!(worldPdf > 0.0) || !(sectionPdf > 0.0)) {
        return result;
    }
    float selectionPdf = worldPdf * sectionPdf;
    float textureAreaPdf = selectionPdf * cellProbabilityMass / cellArea;
    float texturePdf = primeAreaSolidAnglePdf(
            rayOrigin,
            surface.position,
            lightCosine,
            textureAreaPdf);
    float3 lightCorner = emitterCornerArea.xyz + sectionTranslation;
    float projectedArea = primePrepareProjectedSolidAngleTriangle(
            lightCorner,
            firstEdge,
            secondEdge,
            rayOrigin,
            receiverNormal).area;
    float projectedPdf = selectionPdf * primeProjectedSolidAnglePdf(
            projectedArea, receiverNormal, rayDirection);
    result.pdf = primeAreaMixturePdf(
            texturePdf,
            projectedPdf,
            primeProjectedSolidAngleReliable(projectedArea));
    return result;
}
