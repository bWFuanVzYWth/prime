package dev.prime.render.terrain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.prime.render.material.ScatteringFamily;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

final class MaterialIdResolverTest {
    private static final int GLASS_CONTROL = ScatteringFamily.DIELECTRIC_SOLID.encoded()
            << PrimitivePacking.CONTROL_SCATTERING_SHIFT;
    private static final MediumKey GLASS =
            new MediumKey(MediumKey.Kind.TEXTURE, 7, 0, false);

    @Test
    void packsExactIdsAndMakesTheMaterialTableTheOnlyImmutableRecipeSource() {
        int[] local = primitive(7, 1, GLASS_CONTROL);
        int[] resolvedMedium = MediumIdResolver.primitiveRecords(
                local, new int[] {0, 19});

        int[] packed = MaterialIdResolver.primitiveRecords(
                resolvedMedium,
                local,
                CompiledClusterLights.EMPTY,
                MaterialIdResolver.cache(List.of(GLASS), key -> {
                        assertEquals(7, key.textureId());
                        assertEquals(GLASS, key.medium());
                        assertEquals(GLASS_CONTROL, key.materialControl());
                        return 41;
                    }));

        int identity = packed[PrimitivePacking.MEDIUM_ID_WORD];
        assertEquals(0, MaterialIdResolver.unpackMediumId(identity));
        assertEquals(41, MaterialIdResolver.unpackMaterialId(identity));
        assertEquals(resolvedMedium[3] & 0x00ff_ffff, packed[3]);
        for (int word = 0; word < CpuSectionMesh.PRIMITIVE_WORDS; word++) {
            if (word != 3 && word != PrimitivePacking.MEDIUM_ID_WORD) {
                assertEquals(resolvedMedium[word], packed[word]);
            }
        }
    }

    @Test
    void resolvedRecordsRetainGeometryOrientationAndPhysicalPayloadOnly() {
        int control = PrimitivePacking.CONTROL_ALPHA_CUTOUT
                | PrimitivePacking.CONTROL_NORMAL_TEXTURE
                | PrimitivePacking.CONTROL_TANGENT_NEGATIVE
                | PrimitivePacking.CONTROL_FRONT_FACE_ONLY
                | 9 << PrimitivePacking.CONTROL_BUILTIN_SHIFT;
        int[] local = primitive(7, 0, control);

        int[] packed = MaterialIdResolver.primitiveRecords(
                local,
                local,
                CompiledClusterLights.EMPTY,
                MaterialIdResolver.cache(List.of(), key -> 41));

        assertEquals(
                PrimitivePacking.CONTROL_TANGENT_NEGATIVE,
                packed[3] >>> 24);
        assertEquals(0x00ff_ffff, packed[3] & 0x00ff_ffff);
        assertEquals(PrimitivePacking.CONTROL_FRONT_FACE_ONLY,
                (packed[5] & 3) << 8);
        assertEquals(7, PrimitivePacking.unpackTextureId(packed[5]));
        assertEquals(0, packed[5] >>> 27 & 15);
    }

    @Test
    void leavesDynamicAndBakedMaterialsOnReservedZeroIdentity() {
        int[] dynamic = primitive(1, 0, 0);
        dynamic[5] = PrimitivePacking.packDynamicControl(0, 1, false);
        int[] baked = primitive(2, 0, 0);
        baked[2] = PrimitivePacking.CONSTANT_UV_BAKED_MATERIAL;
        baked[6] = PrimitivePacking.CONSTANT_UV_DENSITY;
        int[] source = concatenate(dynamic, baked);

        int[] packed = MaterialIdResolver.primitiveRecords(
                source,
                source,
                CompiledClusterLights.EMPTY,
                MaterialIdResolver.cache(List.of(), key -> {
                        throw new AssertionError("Excluded material must not allocate an ID");
                    }));

        assertEquals(0, packed[PrimitivePacking.MEDIUM_ID_WORD]);
        assertEquals(
                0,
                packed[CpuSectionMesh.PRIMITIVE_WORDS
                        + PrimitivePacking.MEDIUM_ID_WORD]);
    }

    @Test
    void reservedZeroMaterialIdentityRetainsItsInlineMedium() {
        int[] source = primitive(1, 1, PrimitivePacking.CONTROL_DIELECTRIC_SOLID);
        source[5] = PrimitivePacking.packDynamicControl(
                PrimitivePacking.CONTROL_DIELECTRIC_SOLID, 1, false);
        int[] resolvedMedium = MediumIdResolver.primitiveRecords(
                source, new int[] {0, 19});

        int[] packed = MaterialIdResolver.primitiveRecords(
                resolvedMedium,
                source,
                CompiledClusterLights.EMPTY,
                MaterialIdResolver.cache(List.of(GLASS), key -> {
                    throw new AssertionError("Dynamic material must not allocate an ID");
                }));

        assertEquals(19, MaterialIdResolver.unpackMediumId(
                packed[PrimitivePacking.MEDIUM_ID_WORD]));
        assertEquals(0, MaterialIdResolver.unpackMaterialId(
                packed[PrimitivePacking.MEDIUM_ID_WORD]));
    }

    @Test
    void resolvesEachClusterLocalMaterialCombinationOnlyOnce() {
        int[] first = primitive(7, 1, GLASS_CONTROL);
        int[] second = primitive(
                7,
                1,
                GLASS_CONTROL | PrimitivePacking.CONTROL_FRONT_FACE_ONLY);
        int[] local = concatenate(first, second);
        int[] resolvedMedium = MediumIdResolver.primitiveRecords(
                local, new int[] {0, 19});
        AtomicInteger resolutions = new AtomicInteger();
        MaterialIdResolver.Cache cache = MaterialIdResolver.cache(
                List.of(GLASS),
                key -> {
                    resolutions.incrementAndGet();
                    return 41;
                });

        int[] packed = MaterialIdResolver.primitiveRecords(
                resolvedMedium,
                local,
                CompiledClusterLights.EMPTY,
                cache);

        assertEquals(1, resolutions.get());
        assertEquals(41, MaterialIdResolver.unpackMaterialId(
                packed[PrimitivePacking.MEDIUM_ID_WORD]));
        assertEquals(41, MaterialIdResolver.unpackMaterialId(
                packed[CpuSectionMesh.PRIMITIVE_WORDS
                        + PrimitivePacking.MEDIUM_ID_WORD]));
    }

    @Test
    void packsBoundaryRelationIdentityAndPreservesSparseHeader() {
        int[] boundary = {
            CpuSectionMesh.SURFACE_RELATION_BOUNDARY | GLASS_CONTROL << 8,
            PrimitivePacking.packUv(0.5F, 0.5F),
            0x00ff_ffff,
            7,
            1
        };
        int[] local = SurfaceRelationTable.encode(List.of(boundary));
        int[] resolvedMedium = MediumIdResolver.surfaceRelations(
                local, 1, new int[] {0, 23});

        int[] packed = MaterialIdResolver.surfaceRelations(
                resolvedMedium,
                local,
                1,
                MaterialIdResolver.cache(List.of(GLASS), key -> 53));

        assertEquals(local[0], packed[0]);
        int identity = packed[local[0] + 4];
        assertEquals(23, MaterialIdResolver.unpackMediumId(identity));
        assertEquals(53, MaterialIdResolver.unpackMaterialId(identity));
    }

    @Test
    void rejectsReservedOrOverflowingResolvedMaterialIds() {
        int[] local = primitive(7, 1, GLASS_CONTROL);
        int[] resolvedMedium = MediumIdResolver.primitiveRecords(
                local, new int[] {0, 1});

        assertThrows(
                IllegalArgumentException.class,
                () -> MaterialIdResolver.primitiveRecords(
                        resolvedMedium,
                        local,
                        CompiledClusterLights.EMPTY,
                        MaterialIdResolver.cache(List.of(GLASS), key -> 0)));
        assertThrows(
                IllegalArgumentException.class,
                () -> MaterialIdResolver.primitiveRecords(
                        resolvedMedium,
                        local,
                        CompiledClusterLights.EMPTY,
                        MaterialIdResolver.cache(List.of(GLASS), key -> 0x1_0000)));
    }

    private static int[] primitive(int textureId, int mediumId, int control) {
        int[] result = new int[CpuSectionMesh.PRIMITIVE_WORDS];
        result[3] = PrimitivePacking.packTintControl(0x00ff_ffff, control);
        result[4] = mediumId;
        result[5] = PrimitivePacking.packControlTexture(control, textureId);
        result[6] = Float.floatToRawIntBits(1.0F);
        return result;
    }

    private static int[] concatenate(int[]... values) {
        int[] result = new int[values.length * CpuSectionMesh.PRIMITIVE_WORDS];
        int cursor = 0;
        for (int[] value : values) {
            System.arraycopy(value, 0, result, cursor, value.length);
            cursor += value.length;
        }
        return result;
    }
}
