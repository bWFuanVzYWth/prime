// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

package dev.prime.render.terrain;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.prime.render.material.ScatteringFamily;
import java.util.Arrays;
import java.util.List;
import java.util.function.IntUnaryOperator;
import java.util.function.ToIntFunction;
import org.junit.jupiter.api.Test;

final class GpuSurfaceRelationTableTest {
    private static final int GLASS_CONTROL = ScatteringFamily.DIELECTRIC_SOLID.encoded()
            << PrimitivePacking.CONTROL_SCATTERING_SHIFT;
    private static final MediumKey GLASS =
            new MediumKey(MediumKey.Kind.TEXTURE, 7, 0, false);

    @Test
    void boundaryAndEmbeddedMaterialFactsUseTheirCompactGpuLayouts() {
        int[] boundary = boundary();
        int[] overlay = overlay();
        int[] source = SurfaceRelationTable.encode(Arrays.asList(boundary, null, overlay));

        CpuClusterMesh mesh = mesh(section(source, 3, 0, 0));
        GpuSurfaceRelationTable.Encoding encoded = encode(mesh, 0);

        assertEquals(10, encoded.words().length);
        assertArrayEquals(
                new int[] {
                    CpuSectionMesh.SURFACE_RELATION_BOUNDARY
                            | CpuSectionMesh.SURFACE_RELATION_MICRO_GAP_ELIGIBLE,
                    boundary[1],
                    MaterialIdResolver.pack(boundary[2], 53)
                },
                GpuSurfaceRelationTable.record(encoded, 0));
        assertNull(GpuSurfaceRelationTable.record(encoded, 1));
        assertArrayEquals(compactMaterialRelation(overlay),
                GpuSurfaceRelationTable.record(encoded, 2));

        assertEquals(68L, mesh.surfaceRelationBytes());
        assertEquals(40L, GpuSurfaceRelationTable.byteSize(mesh));
    }

    @Test
    void relationOffsetsReuseStaticPayloadAndEmitterPaddingWithoutChangingControls() {
        int[] sourceRelations = SurfaceRelationTable.encode(Arrays.asList(
                boundary(),
                null,
                overlay()));
        GpuSurfaceRelationTable.Encoding relations =
                encode(mesh(section(sourceRelations, 3, 0, 0)), 1);
        int[] primitives = new int[3 * CpuSectionMesh.PRIMITIVE_WORDS];
        primitives[PrimitivePacking.MEDIUM_ID_WORD] = MaterialIdResolver.pack(11, 41);
        primitives[3] = PrimitivePacking.packTintControl(
                0x0012_3456, PrimitivePacking.CONTROL_NORMAL_TEXTURE);
        primitives[5] = PrimitivePacking.packControlTexture(
                PrimitivePacking.CONTROL_NORMAL_TEXTURE, 91);
        int second = CpuSectionMesh.PRIMITIVE_WORDS;
        primitives[second + PrimitivePacking.MEDIUM_ID_WORD] =
                MaterialIdResolver.pack(12, 42);
        primitives[second + 3] = PrimitivePacking.packTintControl(
                0x0065_4321, PrimitivePacking.CONTROL_ALPHA_CUTOUT);
        primitives[second + 5] = PrimitivePacking.packControlTexture(
                PrimitivePacking.CONTROL_ALPHA_CUTOUT, 92);
        int third = 2 * CpuSectionMesh.PRIMITIVE_WORDS;
        primitives[third + PrimitivePacking.MEDIUM_ID_WORD] =
                MaterialIdResolver.pack(13, 43);
        primitives[third + 5] = PrimitivePacking.packControlEmitter(
                PrimitivePacking.CONTROL_DIELECTRIC_SOLID, 0);
        int[] original = primitives.clone();

        int[] packed = GpuSurfaceRelationTable.primitiveRecords(
                primitives, 3, 0, 0, 0, 3, 3, relations);

        assertEquals(
                PrimitivePacking.CONTROL_NORMAL_TEXTURE,
                PrimitivePacking.unpackControl(packed[3], packed[5]));
        assertEquals(1, packed[5] >>> 3 & PrimitivePacking.MAX_TEXTURE_ID);
        assertEquals(
                PrimitivePacking.CONTROL_ALPHA_CUTOUT,
                PrimitivePacking.unpackControl(
                        packed[second + 3], packed[second + 5]));
        assertEquals(0, packed[second + 5] >>> 3 & PrimitivePacking.MAX_TEXTURE_ID);
        assertEquals(primitives[third + 5], packed[third + 5]);
        assertArrayEquals(new int[] {4}, relations.completedEmitterOffsets());
        assertArrayEquals(original, primitives);
    }

    @Test
    void nonTablePrimitiveCannotOwnARelation() {
        GpuSurfaceRelationTable.Encoding relations =
                encode(mesh(section(
                        SurfaceRelationTable.encode(List.of(boundary())), 1, 0, 0)), 0);
        int[] dynamic = new int[CpuSectionMesh.PRIMITIVE_WORDS];
        dynamic[5] = PrimitivePacking.packDynamicControl(0, 1, false);

        assertThrows(
                IllegalArgumentException.class,
                () -> GpuSurfaceRelationTable.primitiveRecords(
                        dynamic, 1, 0, 0, 0, 1, 1, relations));
    }

    @Test
    void emitterWithoutARelationStillPublishesAnExplicitZeroOffset() {
        GpuSurfaceRelationTable.Encoding relations =
                encode(mesh(section(new int[0], 1, 0, 0)), 1);
        int[] emitter = new int[CpuSectionMesh.PRIMITIVE_WORDS];
        emitter[PrimitivePacking.MEDIUM_ID_WORD] = MaterialIdResolver.pack(3, 7);
        emitter[5] = PrimitivePacking.packControlEmitter(0, 0);

        int[] packed = GpuSurfaceRelationTable.primitiveRecords(
                emitter, 1, 0, 0, 0, 1, 1, relations);

        assertArrayEquals(emitter, packed);
        assertArrayEquals(new int[] {0}, relations.completedEmitterOffsets());
    }

    @Test
    void categoryBasesPreserveGlobalOrderAcrossSegments() {
        int[] localRelations = SurfaceRelationTable.encode(List.of(
                boundary(), boundary(), boundary()));
        CpuClusterMesh mesh = mesh(
                section(localRelations, 1, 1, 1),
                section(localRelations, 1, 1, 1));
        GpuSurfaceRelationTable.Encoding relations = encode(mesh, 0);
        int[] first = tableBackedPrimitives(3);
        int[] second = tableBackedPrimitives(3);

        int[] firstPacked = GpuSurfaceRelationTable.primitiveRecords(
                first, 1, 1, 1, 0, 2, 4, relations);
        int[] secondPacked = GpuSurfaceRelationTable.primitiveRecords(
                second, 1, 1, 1, 1, 3, 5, relations);

        assertArrayEquals(new int[] {1, 7, 13}, relationPayloads(firstPacked));
        assertArrayEquals(new int[] {4, 10, 16}, relationPayloads(secondPacked));
    }

    @Test
    void boundaryEncodingRejectsAnUnresolvedMaterialIdentity() {
        CpuClusterMesh mesh = mesh(section(
                SurfaceRelationTable.encode(List.of(boundary())), 1, 0, 0));

        assertThrows(
                IllegalArgumentException.class,
                () -> encode(mesh, 0, ignored -> 0, packedRgba -> packedRgba));
    }

    @Test
    void embeddedMaterialEncodingRejectsAnUnresolvedMaterialIdentity() {
        CpuClusterMesh mesh = mesh(section(
                SurfaceRelationTable.encode(List.of(overlay())), 1, 0, 0));

        assertThrows(
                IllegalArgumentException.class,
                () -> encode(mesh, 0, ignored -> 0, packedRgba -> packedRgba));
    }

    @Test
    void encodingRejectsUnresolvedTintPayloadsInsteadOfTruncatingThem() {
        CpuClusterMesh boundary = mesh(section(
                SurfaceRelationTable.encode(List.of(boundary())), 1, 0, 0));
        CpuClusterMesh material = mesh(section(
                SurfaceRelationTable.encode(List.of(overlay())), 1, 0, 0));

        assertThrows(
                IllegalArgumentException.class,
                () -> encode(
                        boundary,
                        0,
                        key -> 53,
                        ignored -> TintIdResolver.MAX_TINT_ID + 1));
        assertThrows(
                IllegalArgumentException.class,
                () -> encode(
                        material,
                        0,
                        key -> 27,
                        ignored -> TintIdResolver.MAX_TINT_ID + 1));
    }

    private static int[] boundary() {
        return new int[] {
            CpuSectionMesh.SURFACE_RELATION_BOUNDARY
                    | CpuSectionMesh.SURFACE_RELATION_MICRO_GAP_ELIGIBLE
                    | GLASS_CONTROL << 8,
            PrimitivePacking.packUv(0.25F, 0.75F),
            73,
            7,
            1
        };
    }

    private static int[] overlay() {
        int[] result = new int[9];
        result[0] = CpuSectionMesh.SURFACE_RELATION_OVERLAY
                | PrimitivePacking.CONTROL_ALPHA_CUTOUT << 8;
        result[4] = PrimitivePacking.packTintControl(
                202,
                PrimitivePacking.CONTROL_NORMAL_TEXTURE
                        | PrimitivePacking.CONTROL_TANGENT_NEGATIVE);
        result[5] = 0;
        result[6] = PrimitivePacking.packControlTexture(0, 9);
        result[7] = Float.floatToRawIntBits(1.0F);
        result[8] = 0x1234_5678;
        return result;
    }

    private static int[] compactMaterialRelation(int[] source) {
        return new int[] {
            source[0] | 0x8000_0000,
            source[1],
            source[2],
            source[3],
            MaterialIdResolver.pack(
                    source[4] & TintIdResolver.MAX_TINT_ID,
                    27),
            source[7],
            source[8]
        };
    }

    private static GpuSurfaceRelationTable.Encoding encode(
            CpuClusterMesh mesh, int emitterCount) {
        return encode(
                mesh,
                emitterCount,
                key -> key.textureId() == 7 ? 53 : 27,
                packedRgba -> packedRgba & TintIdResolver.MAX_TINT_ID);
    }

    private static GpuSurfaceRelationTable.Encoding encode(
            CpuClusterMesh mesh,
            int emitterCount,
            ToIntFunction<MaterialKey> materialResolver,
            IntUnaryOperator tintResolver) {
        return GpuSurfaceRelationTable.encode(
                mesh,
                emitterCount,
                MaterialIdResolver.cache(List.of(GLASS), materialResolver),
                tintResolver);
    }

    private static CpuClusterMesh mesh(CpuSectionMesh... sections) {
        return CpuClusterMesh.fromSegments(List.of(sections));
    }

    private static CpuSectionMesh section(
            int[] relations, int opaque, int cutout, int transmissive) {
        int primitiveCount = opaque + cutout + transmissive;
        return new CpuSectionMesh(
                new float[primitiveCount * 9],
                new int[primitiveCount * CpuSectionMesh.PRIMITIVE_WORDS],
                relations,
                TriangleLayout.triangles(opaque, cutout, transmissive),
                cutout == 0
                        ? OpacityMicromapData.EMPTY
                        : OpacityMicromapData.fullyUnknown(cutout),
                CpuSectionLights.EMPTY);
    }

    private static int[] tableBackedPrimitives(int count) {
        int[] result = new int[count * CpuSectionMesh.PRIMITIVE_WORDS];
        for (int primitive = 0; primitive < count; primitive++) {
            int base = primitive * CpuSectionMesh.PRIMITIVE_WORDS;
            result[base + PrimitivePacking.MEDIUM_ID_WORD] =
                    MaterialIdResolver.pack(3, 7);
            result[base + 5] = PrimitivePacking.packControlTexture(0, 91);
        }
        return result;
    }

    private static int[] relationPayloads(int[] primitives) {
        int[] result = new int[primitives.length / CpuSectionMesh.PRIMITIVE_WORDS];
        for (int primitive = 0; primitive < result.length; primitive++) {
            result[primitive] = primitives[
                            primitive * CpuSectionMesh.PRIMITIVE_WORDS + 5]
                    >>> 3
                    & PrimitivePacking.MAX_TEXTURE_ID;
        }
        return result;
    }
}
