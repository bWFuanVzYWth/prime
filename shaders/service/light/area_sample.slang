#language slang 2026
module "service/light/area_sample.slang";

import "prime_abi.slang";
import "prime_abi_types.slang";
import "contract/light_sample.slang";
import "service/light/area_common.slang";
import "service/light/cell_sample.slang";
import "service/light/emission.slang";
import "service/material/emission.slang";

AreaLightSample primeInvalidAreaLightSample() {
    AreaLightSample result;
    result.light.direction = float3(0.0, 1.0, 0.0);
    result.light.distance = 0.0;
    result.light.radiance = float3(0.0);
    result.light.pdf = 0.0;
    result.light.isDelta = 0u;
    result.sectionIndex = PRIME_NO_LIGHT_INDEX;
    result.emitterIndex = PRIME_NO_LIGHT_INDEX;
    result.uv = float2(0.0);
    return result;
}

AreaLightSample primeSampleAreaLightEmitter(
        float3 surfacePosition,
        uint sectionIndex,
        uint emitterIndex,
        float emitterSelectionPdf,
        float cellSample,
        float2 positionSample) {
    SectionRecord* sections = (SectionRecord*)primePush.sectionTableAddress;
    uint64_t sectionLightAddress = sections[sectionIndex].lightAddress;
    if (sectionLightAddress == uint64_t(0)) {
        return primeInvalidAreaLightSample();
    }
    SectionLightHeader* sectionBuffer = (SectionLightHeader*)sectionLightAddress;
    if (emitterIndex >= sectionBuffer[0].emitterCount
            || !(emitterSelectionPdf > 0.0)) {
        return primeInvalidAreaLightSample();
    }

    LightEmitter* emitters = (LightEmitter*)sectionBuffer[0].emitterAddress;
    LightCell* cells = (LightCell*)sectionBuffer[0].cellAddress;
    float4 emitterCornerArea = emitters[emitterIndex].cornerArea;
    if (!(emitterCornerArea.w > 0.0)) {
        return primeInvalidAreaLightSample();
    }

    float3 firstEdge = emitters[emitterIndex].edgeOneScale.xyz;
    float3 secondEdge = emitters[emitterIndex].edgeTwoPower.xyz;
    float3 lightCorner = emitterCornerArea.xyz
            + sections[sectionIndex].translation;
    uint emitterCellBase = emitters[emitterIndex].metadata.x;
    // STBN and Sobol provide [0, 1); the whole domain now selects the texture alias table.
    float aliasValue = cellSample * float(PRIME_LIGHT_CELL_COUNT);
    uint column = uint(aliasValue);
    LightCell aliasCell = cells[emitterCellBase + column];
    uint cellIndex = primeSelectLightCell(aliasValue, aliasCell.aliasProbability,
            aliasCell.aliasGeometry);
    LightCell selectedCell = cells[emitterCellBase + cellIndex];
    if (!(selectedCell.probabilityMass > 0.0)) {
        return primeInvalidAreaLightSample();
    }
    float2 parentBarycentric = primeSampleLightCell(
            selectedCell.aliasGeometry >> 8u, positionSample);
    float3 lightPosition = lightCorner
            + firstEdge * parentBarycentric.x
            + secondEdge * parentBarycentric.y;
    float3 toLight = lightPosition - surfacePosition;
    float distanceSquared = dot(toLight, toLight);
    if (!(distanceSquared > 0.0)) {
        return primeInvalidAreaLightSample();
    }
    float distance = sqrt(distanceSquared);
    float3 direction = toLight / distance;
    float lightCosine = primeEmitterCosine(
            emitters[emitterIndex].normal,
            emitters[emitterIndex].metadata.z,
            direction);
    if (!(lightCosine > 0.0)) {
        return primeInvalidAreaLightSample();
    }

    float cellArea = emitterCornerArea.w / float(PRIME_LIGHT_CELL_COUNT);
    float textureAreaPdf = emitterSelectionPdf
            * selectedCell.probabilityMass / cellArea;
    float pdf = primeAreaSolidAnglePdf(
            surfacePosition,
            lightPosition,
            lightCosine,
            textureAreaPdf);
    if (!(pdf > 0.0) || isnan(pdf) || isinf(pdf)) {
        return primeInvalidAreaLightSample();
    }

    AreaLightSample result;
    result.light.direction = direction;
    result.light.distance = distance;
    result.light.radiance = float3(0.0);
    result.light.pdf = pdf;
    result.light.isDelta = 0u;
    result.sectionIndex = sectionIndex;
    result.emitterIndex = emitterIndex;
    result.uv = primeEmitterUv(
            emitters[emitterIndex].uvsTint.xyz, parentBarycentric);
    return result;
}

public AreaLightSample primeSampleAreaLightSelection(
        float3 surfacePosition,
        AreaLightSelection selection,
        float cellSample,
        float2 positionSample) {
    if (selection.sectionIndex == PRIME_NO_LIGHT_INDEX
            || selection.emitterIndex == PRIME_NO_LIGHT_INDEX
            || !(selection.pdf > 0.0)) {
        return primeInvalidAreaLightSample();
    }
    return primeSampleAreaLightEmitter(
            surfacePosition,
            selection.sectionIndex,
            selection.emitterIndex,
            selection.pdf,
            cellSample,
            positionSample);
}

public float3 primeResolveSampledAreaLightRadiance(uint sectionIndex, uint emitterIndex, float2 uv) {
    if (sectionIndex == PRIME_NO_LIGHT_INDEX
            || emitterIndex == PRIME_NO_LIGHT_INDEX) {
        return float3(0.0);
    }
    SectionRecord* sections = (SectionRecord*)primePush.sectionTableAddress;
    uint64_t sectionLightAddress = sections[sectionIndex].lightAddress;
    if (sectionLightAddress == uint64_t(0)) {
        return float3(0.0);
    }
    SectionLightHeader* sectionBuffer = (SectionLightHeader*)sectionLightAddress;
    if (emitterIndex >= sectionBuffer[0].emitterCount) {
        return float3(0.0);
    }
    LightEmitter* emitters = (LightEmitter*)sectionBuffer[0].emitterAddress;
    return primeEvaluateEmitterRadiance(
            emitters[emitterIndex].metadata.w,
            emitters[emitterIndex].uvsTint.w,
            emitters[emitterIndex].metadata.z,
            emitters[emitterIndex].edgeOneScale.w,
            uv);
}

public float3 primeResolveSampledAreaLightRadiance(AreaLightSample sample) {
    return primeResolveSampledAreaLightRadiance(sample.sectionIndex, sample.emitterIndex, sample.uv);
}
