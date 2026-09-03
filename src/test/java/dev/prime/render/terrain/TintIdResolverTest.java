package dev.prime.render.terrain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

final class TintIdResolverTest {
    @Test
    void movesTableBackedTintIntoTheExactIdentityLaneAndPreservesControl() {
        int[] staticPrimitive = primitive(
                3,
                0x0012_3456,
                PrimitivePacking.CONTROL_DIELECTRIC_SOLID
                        | PrimitivePacking.CONTROL_WATER_MEDIUM);
        staticPrimitive[PrimitivePacking.MEDIUM_ID_WORD] =
                PrimitivePacking.packSourceMaterialIdentity(0, 0xa000_0000);
        int[] resolvedStatic = staticPrimitive.clone();
        resolvedStatic[PrimitivePacking.MEDIUM_ID_WORD] =
                MaterialIdResolver.pack(0, 41);
        int[] dynamicPrimitive = primitive(1, 0x00ab_cdef, 0);
        dynamicPrimitive[5] = PrimitivePacking.packDynamicControl(0, 1, false);
        int[] source = concatenate(staticPrimitive, dynamicPrimitive);
        int[] resolved = concatenate(resolvedStatic, dynamicPrimitive);
        AtomicInteger calls = new AtomicInteger();

        int[] result = TintIdResolver.primitiveRecords(resolved, source, packedRgba -> {
            calls.incrementAndGet();
            assertEquals(0xa012_3456, packedRgba);
            return 73;
        });

        assertNotSame(resolved, result);
        assertEquals(0x1400_0000, result[3]);
        assertEquals(
                MaterialIdResolver.pack(73, 41),
                result[PrimitivePacking.MEDIUM_ID_WORD]);
        assertEquals(dynamicPrimitive[3], result[CpuSectionMesh.PRIMITIVE_WORDS + 3]);
        assertEquals(1, calls.get());
        assertEquals(0x1412_3456, source[3]);
        assertEquals(
                MaterialIdResolver.pack(0, 41),
                resolved[PrimitivePacking.MEDIUM_ID_WORD]);
    }

    @Test
    void allDynamicPrimitiveStreamRemainsBorrowedAndUnresolved() {
        int[] source = primitive(1, 0x0012_3456, 0);
        source[5] = PrimitivePacking.packDynamicControl(0, 1, false);

        assertSame(source, TintIdResolver.primitiveRecords(
                source,
                source,
                ignored -> {
                    throw new AssertionError("dynamic source tint must remain inline");
                }));
    }

    @Test
    void rejectsIncompleteRecordsAndOutOfRangeResolvedIdentity() {
        assertThrows(
                IllegalArgumentException.class,
                () -> TintIdResolver.primitiveRecords(
                        new int[1], new int[1], ignored -> 0));
        assertThrows(
                IllegalArgumentException.class,
                () -> TintIdResolver.resolvePackedRgba(
                        0, ignored -> TintIdResolver.MAX_TINT_ID + 1));
    }

    @Test
    void explicitZeroAlphaDoesNotAliasLegacyOpaqueSourceRecords() {
        int[] explicitZero = primitive(3, 0x0012_3456, 0);
        explicitZero[PrimitivePacking.MEDIUM_ID_WORD] =
                PrimitivePacking.packSourceMaterialIdentity(0, 0x0012_3456);
        int[] legacyOpaque = primitive(3, 0x0012_3456, 0);
        int[] source = concatenate(explicitZero, legacyOpaque);
        int[] resolved = source.clone();
        resolved[PrimitivePacking.MEDIUM_ID_WORD] = 0;

        int[] result = TintIdResolver.primitiveRecords(
                resolved,
                source,
                packedRgba -> packedRgba == 0x0012_3456 ? 7 : 11);

        assertEquals(7, result[3] & 0xffff);
        assertEquals(11, result[CpuSectionMesh.PRIMITIVE_WORDS + 3] & 0xffff);
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
