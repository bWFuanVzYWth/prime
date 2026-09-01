package dev.prime.render.terrain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.prime.render.material.ScatteringFamily;
import dev.prime.render.scene.CapturedSprite;
import dev.prime.render.scene.SpriteId;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

final class MaterialTableCandidateTest {
    private static final CapturedSprite EMITTER_SPRITE = new CapturedSprite(
            new SpriteId("prime", "material_table_emitter"),
            13,
            1,
            1,
            false,
            new int[] {0},
            null);

    @Test
    void globallyDeduplicatesTintAndGeometryOrientationButKeepsMaterialSemantics() {
        int normalControl = PrimitivePacking.CONTROL_NORMAL_TEXTURE;
        int[] first = primitive(
                7,
                0,
                0x0011_2233,
                normalControl | PrimitivePacking.CONTROL_TANGENT_NEGATIVE);
        int[] second = primitive(
                7,
                0,
                0x0044_5566,
                normalControl | PrimitivePacking.CONTROL_FRONT_FACE_ONLY);
        int[] relationPrimitive = primitive(7, 0, 0x0077_8899, normalControl);
        int[] relation = new int[1 + CpuSectionMesh.PRIMITIVE_WORDS];
        relation[0] = CpuSectionMesh.SURFACE_RELATION_BILATERAL;
        System.arraycopy(relationPrimitive, 0, relation, 1, relationPrimitive.length);
        int[] relations = SurfaceRelationTable.encode(Arrays.asList(relation, null));
        CpuClusterMesh mesh = CpuClusterMesh.fromEncoded(
                List.of(new CpuClusterMesh.Segment(
                        new float[18],
                        concatenate(first, second),
                        relations,
                        2,
                        0,
                        0,
                        0,
                        0,
                        0)),
                2,
                0,
                0,
                OpacityMicromapData.EMPTY,
                CompiledClusterLights.EMPTY);

        MaterialTableCandidate measured = MaterialTableCandidate.measure(mesh);

        assertEquals(1, measured.uniqueMaterialCount());
        assertEquals(3L, measured.candidateReferenceCount());
        assertEquals(2L, measured.staticSurfaceReferences());
        assertEquals(1L, measured.relationMaterialReferences());
        MaterialTableCandidate.Key key = measured.references().keySet().iterator().next();
        assertEquals(7, key.textureId());
        assertEquals(null, key.medium());
        assertEquals(normalControl, key.materialControl());
    }

    @Test
    void mediumIdentityAndScatteringRemainPartOfTheExactKey() {
        int glass = ScatteringFamily.DIELECTRIC_SOLID.encoded()
                << PrimitivePacking.CONTROL_SCATTERING_SHIFT;
        CpuClusterMesh mesh = CpuClusterMesh.fromEncoded(
                List.of(new CpuClusterMesh.Segment(
                        new float[18],
                        concatenate(
                                primitive(11, 1, 0x00ff_ffff, glass),
                                primitive(11, 2, 0x00ff_ffff, glass)),
                        0,
                        0,
                        2)),
                0,
                0,
                2,
                OpacityMicromapData.EMPTY,
                CompiledClusterLights.EMPTY)
                .withMediumCatalog(List.of(
                        new MediumKey(MediumKey.Kind.TEXTURE, 11, 0, false),
                        new MediumKey(MediumKey.Kind.TEXTURE, 12, 0, false)));

        MaterialTableCandidate measured = MaterialTableCandidate.measure(mesh);

        assertEquals(2, measured.uniqueMaterialCount());
        assertEquals(2L, measured.candidateReferenceCount());
    }

    @Test
    void reportsDynamicAndBakedExceptionsWithoutInventingMaterialKeys() {
        int[] dynamic = primitive(1, 0, 0, 0);
        dynamic[5] = PrimitivePacking.packDynamicControl(0, 1, false);
        int[] baked = primitive(2, 0, 0, 0);
        baked[2] = PrimitivePacking.CONSTANT_UV_BAKED_MATERIAL;
        baked[6] = PrimitivePacking.CONSTANT_UV_DENSITY;
        CpuClusterMesh mesh = CpuClusterMesh.fromEncoded(
                List.of(new CpuClusterMesh.Segment(
                        new float[18], concatenate(dynamic, baked), 2, 0, 0)),
                2,
                0,
                0,
                OpacityMicromapData.EMPTY,
                CompiledClusterLights.EMPTY);

        MaterialTableCandidate measured = MaterialTableCandidate.measure(mesh);

        assertEquals(0, measured.uniqueMaterialCount());
        assertEquals(0L, measured.candidateReferenceCount());
        assertEquals(1L, measured.dynamicReferences());
        assertEquals(1L, measured.bakedReferences());
    }

    @Test
    void emitterPayloadReusesThePrimitiveMaterialKey() {
        CpuSectionLights.Builder lightBuilder = new CpuSectionLights.Builder();
        int encodedEmitter = lightBuilder.addTriangle(
                0.0F,
                0.0F,
                0.0F,
                1.0F,
                0.0F,
                0.0F,
                0.0F,
                1.0F,
                0.0F,
                PrimitivePacking.packUv(0.0F, 0.0F),
                PrimitivePacking.packUv(1.0F, 0.0F),
                PrimitivePacking.packUv(0.0F, 1.0F),
                0xffff_ffff,
                false,
                15,
                EMITTER_SPRITE,
                null);
        assertEquals(1, encodedEmitter);
        int[] primitive = primitive(1, 0, 0x00ff_ffff, 0);
        primitive[5] = PrimitivePacking.packControlEmitter(0, 0);
        CpuClusterMesh mesh = CpuClusterMesh.fromEncoded(
                List.of(new CpuClusterMesh.Segment(
                        new float[9], primitive, 1, 0, 0)),
                1,
                0,
                0,
                OpacityMicromapData.EMPTY,
                CompiledClusterLights.compile(lightBuilder.build()));

        MaterialTableCandidate measured = MaterialTableCandidate.measure(mesh);

        assertEquals(1, measured.uniqueMaterialCount());
        assertEquals(2L, measured.candidateReferenceCount());
        assertEquals(1L, measured.staticSurfaceReferences());
        assertEquals(1L, measured.lightEmitterReferences());
        assertEquals(
                13,
                measured.references().keySet().iterator().next().textureId());
    }

    @Test
    void rejectsTextureIdentityOutsideTheApprovedU16Domain() {
        CpuClusterMesh mesh = CpuClusterMesh.fromEncoded(
                List.of(new CpuClusterMesh.Segment(
                        new float[9], primitive(0x1_0000, 0, 0, 0), 1, 0, 0)),
                1,
                0,
                0,
                OpacityMicromapData.EMPTY,
                CompiledClusterLights.EMPTY);

        assertThrows(
                IllegalArgumentException.class,
                () -> MaterialTableCandidate.measure(mesh));
    }

    private static int[] primitive(int textureId, int mediumId, int tint, int control) {
        int[] result = new int[CpuSectionMesh.PRIMITIVE_WORDS];
        result[3] = PrimitivePacking.packTintControl(tint, control);
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
