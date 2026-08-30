#language slang 2026
module "service/light/area_sample.slang";

import "prime_abi.slang";
import "prime_abi_types.slang";
import "contract/light_sample.slang";
import "math/basis.slang";
import "math/projected_solid_angle_domain.slang";
import "math/projected_solid_angle_sample.slang";
import "service/light/area_common.slang";
import "service/light/emission.slang";
import "service/material/surface.slang";

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

void primeLightCellVertices(
        uint geometry,
        out float2 first,
        out float2 second,
        out float2 third) {
    uint column = geometry & 0xfu;
    uint row = (geometry >> 4u) & 0xfu;
    bool upper = (geometry & 0x100u) != 0u;
    float inverseSubdivision = 1.0 / float(PRIME_LIGHT_CELL_SUBDIVISION);
    float x = float(column) * inverseSubdivision;
    float y = float(row) * inverseSubdivision;
    if (upper) {
        first = float2(x + inverseSubdivision, y);
        second = float2(x + inverseSubdivision, y + inverseSubdivision);
        third = float2(x, y + inverseSubdivision);
    } else {
        first = float2(x, y);
        second = float2(x + inverseSubdivision, y);
        third = float2(x, y + inverseSubdivision);
    }
}

float2 primeSampleLightCell(
        LightCell selectedCell, float2 positionSample) {
    float2 first;
    float2 second;
    float2 third;
    primeLightCellVertices(
            selectedCell.aliasGeometry >> 8u, first, second, third);
    float squareRoot = sqrt(positionSample.x);
    float3 barycentric = float3(
            1.0 - squareRoot,
            squareRoot * (1.0 - positionSample.y),
            squareRoot * positionSample.y);
    return first * barycentric.x
            + second * barycentric.y
            + third * barycentric.z;
}

bool primeIntersectAreaLight(
        float3 origin,
        float3 direction,
        float3 corner,
        float3 firstEdge,
        float3 secondEdge,
        out float2 parentBarycentric) {
    float3 edgeCross = cross(firstEdge, secondEdge);
    float denominator = dot(edgeCross, direction);
    float distance = dot(edgeCross, corner - origin) / denominator;
    float3 relative = origin + distance * direction - corner;
    float inverseAreaSquared = 1.0 / dot(edgeCross, edgeCross);
    parentBarycentric = float2(
            dot(cross(relative, secondEdge), edgeCross) * inverseAreaSquared,
            dot(cross(firstEdge, relative), edgeCross) * inverseAreaSquared);
    // The projected-domain sampler guarantees triangle containment. Retesting strict barycentric
    // bounds here would reject valid boundary samples after the ray-plane round trip.
    return distance > 0.0;
}

AreaLightSample primeSampleAreaLightEmitter(
        float3 surfacePosition,
        float3 receiverNormal,
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
    float projectedArea = 0.0;
    if (dot(receiverNormal, receiverNormal) > 0.0) {
        projectedArea = primePrepareProjectedSolidAngleTriangle(
                lightCorner,
                firstEdge,
                secondEdge,
                surfacePosition,
                receiverNormal).area;
    }
    bool projectedActive = primeProjectedSolidAngleReliable(projectedArea);
    bool sampleTexture = !projectedActive
            || cellSample < PRIME_AREA_PROJECTED_MIXTURE;

    uint emitterCellBase = emitters[emitterIndex].metadata.x;
    float2 parentBarycentric;
    uint cellIndex;
    if (sampleTexture) {
        float textureSample = projectedActive
                ? cellSample / PRIME_AREA_PROJECTED_MIXTURE
                : cellSample;
        float aliasValue = textureSample * float(PRIME_LIGHT_CELL_COUNT);
        // Sobol and mixture remap stay below 1.
        uint column = uint(aliasValue);
        LightCell aliasCell = cells[emitterCellBase + column];
        cellIndex = aliasValue - float(column) < aliasCell.aliasProbability
                ? column
                : aliasCell.aliasGeometry & 0xffu;
        LightCell selectedCell = cells[emitterCellBase + cellIndex];
        if (!(selectedCell.probabilityMass > 0.0)) {
            return primeInvalidAreaLightSample();
        }
        parentBarycentric = primeSampleLightCell(selectedCell, positionSample);
    } else {
        float3 tangent;
        float3 bitangent;
        primeBasis(receiverNormal, tangent, bitangent);
        PrimeProjectedSolidAngleDomain sampleDomain =
                primePrepareProjectedSolidAngleTriangleBasis(
                        lightCorner,
                        firstEdge,
                        secondEdge,
                        surfacePosition,
                        receiverNormal,
                        tangent,
                        bitangent);
        float3 localDirection = primeSampleProjectedSolidAngle(
                sampleDomain, positionSample);
        // Plane intersection ignores scale; the common path rebuilds the exact direction.
        float3 direction = tangent * localDirection.x
                + bitangent * localDirection.y
                + receiverNormal * localDirection.z;
        if (!primeIntersectAreaLight(
                surfacePosition, direction, lightCorner, firstEdge, secondEdge,
                parentBarycentric)) {
            return primeInvalidAreaLightSample();
        }
        cellIndex = primeLightCellIndex(parentBarycentric);
    }

    LightCell selectedCell = cells[emitterCellBase + cellIndex];
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
            emitters[emitterIndex].normalPadding.xyz,
            emitters[emitterIndex].metadata.z,
            direction);
    if (!(lightCosine > 0.0)) {
        return primeInvalidAreaLightSample();
    }

    float cellArea = emitterCornerArea.w / float(PRIME_LIGHT_CELL_COUNT);
    float textureAreaPdf = emitterSelectionPdf
            * selectedCell.probabilityMass / cellArea;
    float texturePdf = primeAreaSolidAnglePdf(
            surfacePosition,
            lightPosition,
            lightCosine,
            textureAreaPdf);
    float projectedPdf = emitterSelectionPdf * primeProjectedSolidAnglePdf(
            projectedArea, receiverNormal, direction);
    float pdf = primeAreaMixturePdf(
            texturePdf, projectedPdf, projectedActive);
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
        float3 receiverNormal,
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
            receiverNormal,
            selection.sectionIndex,
            selection.emitterIndex,
            selection.pdf,
            cellSample,
            positionSample);
}

public float3 primeResolveSampledAreaLightRadiance(AreaLightSample areaSample) {
    if (areaSample.sectionIndex == PRIME_NO_LIGHT_INDEX
            || areaSample.emitterIndex == PRIME_NO_LIGHT_INDEX) {
        return float3(0.0);
    }
    SectionRecord* sections = (SectionRecord*)primePush.sectionTableAddress;
    uint64_t sectionLightAddress = sections[areaSample.sectionIndex].lightAddress;
    if (sectionLightAddress == uint64_t(0)) {
        return float3(0.0);
    }
    SectionLightHeader* sectionBuffer = (SectionLightHeader*)sectionLightAddress;
    if (areaSample.emitterIndex >= sectionBuffer[0].emitterCount) {
        return float3(0.0);
    }
    LightEmitter* emitters = (LightEmitter*)sectionBuffer[0].emitterAddress;
    return primeEvaluateEmitterRadiance(
            emitters[areaSample.emitterIndex].metadata.w,
            emitters[areaSample.emitterIndex].uvsTint.w,
            emitters[areaSample.emitterIndex].metadata.z,
            emitters[areaSample.emitterIndex].edgeOneScale.w,
            areaSample.uv);
}
