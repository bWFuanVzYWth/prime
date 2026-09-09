// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

#language slang 2026
module "service/trace/candidate_record.slang";

import glsl;
import "prime_abi.slang";
import "prime_abi_types.slang";
import "prime_surface_abi.slang";
import "math/packed_geometry.slang";
import "service/trace/surface_records.slang";

public SectionRecord* primeCandidateSection() {
    return (SectionRecord*)primePush.sectionTableAddress + InstanceID();
}

// Bound only to geometry class 0; no cutout/transmissive layout or UV/material record is needed.
public bool primeOpaqueCandidateIsSource(uint2 source) {
    if (InstanceID() != source.x) return false;
    SectionRecord* section = primeCandidateSection();
    return primeOpaqueSourceTriangle(PrimitiveIndex(), source.y,
            section->cutoutBase, section->opaqueMacroTriangleBase);
}

public uint primeOpaqueCandidateEmitter() {
    SectionRecord* section = primeCandidateSection();
    uint triangle = PrimitiveIndex();
    uint macroBase = section->opaqueMacroTriangleBase;
    uint index = triangle < macroBase ? triangle : macroBase + ((triangle - macroBase) >> 1u);
    uint flags;
    if ((section->instanceTint & PRIME_SURFACE_KEYS_FLAG) != 0u) {
        uint key = ((uint*)section->primitiveAddress)[index];
        flags = primeSurfaceRecords[key].flagsEmitter;
    } else {
        flags = ((PrimitiveRecord*)section->primitiveAddress)[index].flagsEmitter;
    }
    uint encoded = (flags & 4u) != 0u ? (flags >> 3u & 0x00ffffffu) : 0u;
    return encoded == 0u ? 0xffffffffu : encoded - 1u;
}

public uint primeCandidatePrimitiveIndex() {
    SectionRecord* section = primeCandidateSection();
    uint geometry = GeometryIndex();
    uint macroBase = geometry == 0u ? section->opaqueMacroTriangleBase
            : (geometry == 1u ? section->cutoutMacroTriangleBase : section->transmissiveMacroTriangleBase);
    uint base = geometry == 0u ? 0u : (geometry == 1u ? section->cutoutBase : section->transmissiveBase);
    uint triangle = PrimitiveIndex();
    return base + (triangle < macroBase ? triangle : macroBase + ((triangle - macroBase) >> 1u));
}

public bool primeCandidateIsSource(uint2 source) {
    if (InstanceID() != source.x) return false;
    SectionRecord* section = primeCandidateSection();
    uint opaqueCount = section->cutoutBase;
    uint cutoutCount = section->transmissiveBase - opaqueCount;
    uint opaqueMacro = section->opaqueMacroTriangleBase;
    uint cutoutMacro = section->cutoutMacroTriangleBase;
    uint triangle = primeGlobalTriangleIndexValue(GeometryIndex(), PrimitiveIndex(),
            opaqueCount, cutoutCount, opaqueMacro, cutoutMacro);
    if (triangle == source.y) return true;
    return primeCandidatePrimitiveIndex() == primeGlobalPrimitiveIndexFromTriangleValue(
            source.y, opaqueCount, cutoutCount, opaqueMacro, cutoutMacro,
            section->transmissiveMacroTriangleBase);
}

public struct PrimeCandidateRecord {
    public uint64_t address;
    public uint index;
    // 0: direct primitive, 1: shared SurfaceKey, 2: selected relation material.
    public uint kind;
};

public PrimeCandidateRecord primeCandidateRecord() {
    SectionRecord* section = primeCandidateSection();
    PrimeCandidateRecord result;
    result.index = primeCandidatePrimitiveIndex();
    result.address = section->primitiveAddress;
    result.kind = 0u;
    if ((section->instanceTint & PRIME_SURFACE_KEYS_FLAG) != 0u) {
        result.index = ((uint*)result.address)[result.index];
        result.kind = 1u;
    }
    return result;
}

public uint primeCandidateIdentity(PrimeCandidateRecord record) {
    if (record.kind == 2u) return ((uint*)record.address)[4];
    if (record.kind == 1u) return primeSurfaceRecords[record.index].identity;
    return ((PrimitiveRecord*)record.address)[record.index].identity;
}

public uint primeCandidateFlagsEmitter(PrimeCandidateRecord record) {
    if (record.kind == 2u) return 0u;
    if (record.kind == 1u) return primeSurfaceRecords[record.index].flagsEmitter;
    return ((PrimitiveRecord*)record.address)[record.index].flagsEmitter;
}

public uint primeCandidateEmitter(PrimeCandidateRecord record) {
    uint flags = primeCandidateFlagsEmitter(record);
    uint encoded = (flags & 4u) != 0u ? (flags >> 3u & 0x00ffffffu) : 0u;
    return encoded == 0u ? 0xffffffffu : encoded - 1u;
}

// Group fields by the consumer, so selecting the physical layout does not become a separate
// dynamic branch for every scalar load. Neither group contains tangent or emitter shading data.
public uint4 primeCandidateUvWords(PrimeCandidateRecord record) {
    if (record.kind == 2u) {
        uint* words = (uint*)record.address;
        return uint4(words[1], words[2], words[3], words[5]);
    }
    if (record.kind == 1u) {
        return uint4(primeSurfaceRecords[record.index].uv0, primeSurfaceRecords[record.index].uv1,
                primeSurfaceRecords[record.index].uv2, primeSurfaceRecords[record.index].uvDensity);
    }
    PrimitiveRecord* value = (PrimitiveRecord*)record.address + record.index;
    return uint4(value->uv0, value->uv1, value->uv2, value->uvDensity);
}

public uint3 primeCandidateHeader(PrimeCandidateRecord record) {
    if (record.kind == 2u) {
        uint* words = (uint*)record.address;
        return uint3(words[0] & 0x80000000u, words[4], 0u);
    }
    if (record.kind == 1u) {
        return uint3(primeSurfaceRecords[record.index].tint,
                primeSurfaceRecords[record.index].identity, primeSurfaceRecords[record.index].flagsEmitter);
    }
    PrimitiveRecord* value = (PrimitiveRecord*)record.address + record.index;
    return uint3(value->tint, value->identity, value->flagsEmitter);
}
