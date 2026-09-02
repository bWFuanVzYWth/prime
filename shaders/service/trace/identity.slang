#language slang 2026
module "service/trace/identity.slang";

import glsl;
import "prime_abi.slang";
import "prime_abi_types.slang";
import "contract/path.slang";
import "math/packed_geometry.slang";

public SectionRecord primeSection() {
    return ((SectionRecord*)primePush.sectionTableAddress)[InstanceID()];
}

uint primeLocalPrimitiveIndex(SectionRecord section) {
    // BLAS geometries and primitive storage share opaque, cutout, transmissive order. Macro
    // rectangle triangle pairs share one primitive record at each geometry tail.
    uint macroBase = GeometryIndex() == 0
            ? section.opaqueMacroTriangleBase
            : (GeometryIndex() == 1
                    ? section.cutoutMacroTriangleBase
                    : section.transmissiveMacroTriangleBase);
    return PrimitiveIndex() < macroBase
            ? PrimitiveIndex()
            : macroBase + ((PrimitiveIndex() - macroBase) >> 1u);
}

public PrimitiveRecord primeRawPrimitive(SectionRecord section) {
    uint base = GeometryIndex() == 0
            ? 0u
            : (GeometryIndex() == 1 ? section.cutoutBase : section.transmissiveBase);
    return ((PrimitiveRecord*)section.primitiveAddress)[base + primeLocalPrimitiveIndex(section)];
}

public uint primeGlobalPrimitiveIndex(SectionRecord section) {
    uint base = GeometryIndex() == 0
            ? 0u
            : (GeometryIndex() == 1 ? section.cutoutBase : section.transmissiveBase);
    return base + primeLocalPrimitiveIndex(section);
}

public uint primeGlobalPrimitiveIndexFromTriangle(
        SectionRecord section, uint triangleIndex) {
    return primeGlobalPrimitiveIndexFromTriangleValue(
            triangleIndex,
            section.cutoutBase,
            section.transmissiveBase - section.cutoutBase,
            section.opaqueMacroTriangleBase,
            section.cutoutMacroTriangleBase,
            section.transmissiveMacroTriangleBase);
}

public uint primeGlobalTriangleIndex(SectionRecord section) {
    return primeGlobalTriangleIndexValue(
            GeometryIndex(),
            PrimitiveIndex(),
            section.cutoutBase,
            section.transmissiveBase - section.cutoutBase,
            section.opaqueMacroTriangleBase,
            section.cutoutMacroTriangleBase);
}

public uint primePrimitiveEmitterIndex(PrimitiveRecord primitive) {
    uint encodedEmitter = (primitive.flagsEmitter & 4u) != 0u
            ? primitive.flagsEmitter >> 3u & 0x00ffffffu
            : 0u;
    return encodedEmitter == 0u
            ? PRIME_NO_PRIMITIVE_INDEX
            : encodedEmitter - 1u;
}
