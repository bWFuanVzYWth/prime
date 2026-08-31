package dev.prime.render.terrain;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

final class TintIdResolverTest {
    @Test
    void remapsOnlyStaticPrimitiveTintAndPreservesControl() {
        int[] staticPrimitive = primitive(
                3,
                0x0012_3456,
                PrimitivePacking.CONTROL_DIELECTRIC_SOLID
                        | PrimitivePacking.CONTROL_WATER_MEDIUM);
        int[] dynamicPrimitive = primitive(1, 0x00ab_cdef, 0);
        dynamicPrimitive[5] = PrimitivePacking.packDynamicControl(0, 1, false);
        int[] source = concatenate(staticPrimitive, dynamicPrimitive);
        AtomicInteger calls = new AtomicInteger();

        int[] result = TintIdResolver.primitiveRecords(source, packedRgb -> {
            calls.incrementAndGet();
            assertEquals(0x0012_3456, packedRgb);
            return 73;
        });

        assertNotSame(source, result);
        assertEquals(0x1400_0049, result[3]);
        assertEquals(dynamicPrimitive[3], result[CpuSectionMesh.PRIMITIVE_WORDS + 3]);
        assertEquals(1, calls.get());
        assertEquals(0x1412_3456, source[3]);
    }

    @Test
    void allDynamicPrimitiveStreamRemainsBorrowedAndUnresolved() {
        int[] source = primitive(1, 0x0012_3456, 0);
        source[5] = PrimitivePacking.packDynamicControl(0, 1, false);

        assertSame(source, TintIdResolver.primitiveRecords(
                source,
                ignored -> {
                    throw new AssertionError("dynamic source tint must remain packed RGB8");
                }));
    }

    @Test
    void remapsBoundaryAndEmbeddedRelationMaterial() {
        int[] boundary = {
            CpuSectionMesh.SURFACE_RELATION_BOUNDARY,
            PrimitivePacking.packUv(0.5F, 0.5F),
            0x0011_2233,
            7,
            3
        };
        int[] bilateral = new int[9];
        bilateral[0] = CpuSectionMesh.SURFACE_RELATION_BILATERAL;
        System.arraycopy(primitive(9, 0x0044_5566, 0), 0, bilateral, 1, 8);
        int[] source = SurfaceRelationTable.encode(List.of(boundary, bilateral));

        int[] result = TintIdResolver.surfaceRelations(
                source,
                2,
                packedRgb -> packedRgb == 0x0011_2233 ? 101 : 202);

        assertNotSame(source, result);
        assertEquals(101, SurfaceRelationTable.record(result, 2, 0)[2]);
        assertEquals(202, SurfaceRelationTable.record(result, 2, 1)[4]);
        assertArrayEquals(boundary, SurfaceRelationTable.record(source, 2, 0));
        assertEquals(0x0044_5566, SurfaceRelationTable.record(source, 2, 1)[4]);
    }

    @Test
    void rejectsIncompleteRecordsAndOutOfRangeResolvedIdentity() {
        assertThrows(
                IllegalArgumentException.class,
                () -> TintIdResolver.primitiveRecords(new int[1], ignored -> 0));
        assertThrows(
                IllegalArgumentException.class,
                () -> TintIdResolver.resolvePackedRgb(0x0100_0000, ignored -> 0));
        assertThrows(
                IllegalArgumentException.class,
                () -> TintIdResolver.resolvePackedRgb(
                        0, ignored -> TintIdResolver.MAX_TINT_ID + 1));
    }

    private static int[] primitive(int textureId, int tint, int control) {
        int[] result = new int[CpuSectionMesh.PRIMITIVE_WORDS];
        result[3] = PrimitivePacking.packTintControl(tint, control);
        result[5] = PrimitivePacking.packControlTexture(0, textureId);
        result[6] = Float.floatToRawIntBits(1.0F);
        return result;
    }

    private static int[] concatenate(int[] first, int[] second) {
        int[] result = new int[first.length + second.length];
        System.arraycopy(first, 0, result, 0, first.length);
        System.arraycopy(second, 0, result, first.length, second.length);
        return result;
    }
}
