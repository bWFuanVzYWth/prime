package dev.prime.render.terrain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.prime.render.scene.CapturedSectionGeometry;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

final class SectionMeshAccumulatorTest {
    @Test
    void buildTransfersOwnershipExactlyOnce() {
        SectionMeshAccumulator accumulator = new SectionMeshAccumulator(
                LabPbrMaterialSet.EMPTY, false);
        CpuSectionGeometry geometry = accumulator.build();
        assertTrue(geometry.meshes().isEmpty());
        assertTrue(geometry.mergeFaces().isEmpty());
        assertThrows(IllegalStateException.class, accumulator::build);
    }

    @Test
    void retainsOnlyCompleteAxisAlignedUnitFacesForClusterMerging() {
        TestSprite sprite = new TestSprite();
        SectionMeshAccumulator accumulator = new SectionMeshAccumulator(
                LabPbrMaterialSet.EMPTY, false);
        accumulator.addQuad(
                horizontalQuad(3.0F, 5.0F, 7.0F, 1.0F),
                opaqueSurface(sprite));
        accumulator.addQuad(
                horizontalQuad(4.0F, 5.0F, 7.0F, 0.5F),
                opaqueSurface(sprite));
        accumulator.addQuad(
                horizontalQuad(5.0F, 5.0F, 7.0F, 1.0F),
                surface(
                        -1,
                        CapturedSectionGeometry.Layer.OPAQUE,
                        0,
                        sprite,
                        TransmissiveTopology.NONE,
                        0));

        CpuSectionGeometry geometry = accumulator.build();

        assertEquals(1, geometry.mergeFaces().size());
        assertEquals(0, PrimitivePacking.unpackSourceMediumId(
                geometry.mergeFaces().getFirst().primitive()[4]));
        assertEquals(1, geometry.meshes().size());
        assertEquals(4, geometry.meshes().getFirst().opaqueTriangleCount());
        assertTrue(Float.intBitsToFloat(
                geometry.mergeFaces().getFirst().primitive()[6]) < 0.0F);
    }

    @Test
    void admitsNonFluidTransmissionButKeepsWaterOutOfMerging() {
        TestSprite sprite = new TestSprite();
        SectionMeshAccumulator accumulator = new SectionMeshAccumulator(
                LabPbrMaterialSet.EMPTY, false);
        accumulator.addQuad(
                horizontalQuad(0.0F, 0.0F, 2.0F, 1.0F),
                transmissiveSurface(sprite, false, 73));
        accumulator.addQuad(
                horizontalQuad(1.0F, 0.0F, 2.0F, 1.0F),
                transmissiveSurface(sprite, true));

        CpuSectionGeometry geometry = accumulator.build();

        assertEquals(1, geometry.mergeFaces().size());
        assertTrue(geometry.mergeFaces().getFirst().transmissive());
        assertEquals(73, PrimitivePacking.unpackSourceMediumId(
                geometry.mergeFaces().getFirst().primitive()[4]));
        assertEquals(1, geometry.meshes().size());
        assertEquals(2, geometry.meshes().getFirst().transmissiveTriangleCount());
    }

    @Test
    void labPbrEmissionBypassesMergingAndBuildsTriangleLights() {
        TestSprite sprite = new TestSprite();
        sprite.fill(0xffffffff);
        int[] specular = new int[16 * 16];
        java.util.Arrays.fill(specular, 0xe7000000);
        LabPbrEmissionMap emission = LabPbrEmissionMap.fromSpecular(
                specular, 16, 16, 16, 16, 1, 1);
        LabPbrMaterialSet materials = new LabPbrMaterialSet(
                Set.of(), Set.of(sprite.id()), Map.of(sprite.id(), emission));
        SectionMeshAccumulator accumulator = new SectionMeshAccumulator(
                materials, false);

        accumulator.addQuad(
                horizontalQuad(0.0F, 0.0F, 0.0F, 1.0F),
                opaqueSurface(sprite));
        CpuSectionGeometry geometry = accumulator.build();

        assertTrue(geometry.mergeFaces().isEmpty());
        assertEquals(1, geometry.meshes().size());
        CpuSectionMesh mesh = geometry.meshes().getFirst();
        assertEquals(2, mesh.opaqueTriangleCount());
        assertEquals(2, mesh.lights().emitterCount());
    }

    @Test
    void authoredZeroEmissionRemainsMergeableForNonVanillaSurfaces() {
        TestSprite sprite = new TestSprite();
        LabPbrEmissionMap emission = LabPbrEmissionMap.fromSpecular(
                new int[16 * 16], 16, 16, 16, 16, 1, 1);
        LabPbrMaterialSet materials = new LabPbrMaterialSet(
                Set.of(), Set.of(sprite.id()), Map.of(sprite.id(), emission));
        SectionMeshAccumulator accumulator = new SectionMeshAccumulator(
                materials, false);

        accumulator.addQuad(
                horizontalQuad(0.0F, 0.0F, 0.0F, 1.0F),
                opaqueSurface(sprite));
        CpuSectionGeometry geometry = accumulator.build();

        assertEquals(1, geometry.mergeFaces().size());
        assertTrue(geometry.meshes().isEmpty());
    }

    @Test
    void rejectsNonFiniteCapturedAttributesBeforePublishingGeometry() {
        TestSprite sprite = new TestSprite();
        SectionMeshAccumulator accumulator = new SectionMeshAccumulator(
                LabPbrMaterialSet.EMPTY, false);
        SectionMeshAccumulator.Surface surface = opaqueSurface(sprite);
        for (float invalid : new float[] {
            Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY
        }) {
            SectionMeshAccumulator.Quad position =
                    horizontalQuad(0.0F, 0.0F, 0.0F, 1.0F);
            position.x[0] = invalid;
            assertThrows(
                    IllegalArgumentException.class,
                    () -> accumulator.addQuad(position, surface));
        }
        SectionMeshAccumulator.Quad uv =
                horizontalQuad(0.0F, 0.0F, 0.0F, 1.0F);
        uv.v[2] = Float.NaN;
        assertThrows(
                IllegalArgumentException.class,
                () -> accumulator.addQuad(uv, surface));
        SectionMeshAccumulator.Quad normal =
                horizontalQuad(0.0F, 0.0F, 0.0F, 1.0F);
        normal.normalZ = Float.POSITIVE_INFINITY;
        assertThrows(
                IllegalArgumentException.class,
                () -> accumulator.addQuad(normal, surface));

        accumulator.addQuad(
                horizontalQuad(0.0F, 0.0F, 0.0F, 1.0F), surface);
        assertEquals(1, accumulator.build().mergeFaces().size());
    }

    @Test
    void loweringRepairsSourceWindingAndDoesNotPublishASecondNormal() {
        TestSprite sprite = new TestSprite("reversed_source_winding");
        SectionMeshAccumulator accumulator = new SectionMeshAccumulator(
                LabPbrMaterialSet.EMPTY, false);
        SectionMeshAccumulator.Quad quad = horizontalQuad(
                0.0F, 0.0F, 0.0F, 0.5F);
        quad.normalZ = -1.0F;
        SectionMeshAccumulator.Surface surface = surface(
                -1,
                CapturedSectionGeometry.Layer.OPAQUE,
                0,
                sprite,
                TransmissiveTopology.NONE,
                0);

        accumulator.addQuad(quad, surface);
        CpuSectionMesh mesh = accumulator.build().meshes().getFirst();

        assertEquals(2, mesh.opaqueTriangleCount());
        for (int triangle = 0; triangle < mesh.triangleCount(); triangle++) {
            int position = triangle * 9;
            float[] positions = mesh.positions();
            float edgeOneX = positions[position + 3] - positions[position];
            float edgeOneY = positions[position + 4] - positions[position + 1];
            float edgeTwoX = positions[position + 6] - positions[position];
            float edgeTwoY = positions[position + 7] - positions[position + 1];
            assertTrue(edgeOneX * edgeTwoY - edgeOneY * edgeTwoX < 0.0F);
            assertEquals(0, PrimitivePacking.unpackSourceMediumId(
                    mesh.primitiveRecords()[
                            triangle * CpuSectionMesh.PRIMITIVE_WORDS + 4]));
        }
    }

    static SectionMeshAccumulator.Quad horizontalQuad(
            float x, float y, float z, float width) {
        SectionMeshAccumulator.Quad quad = new SectionMeshAccumulator.Quad();
        quad.x[0] = x;
        quad.y[0] = y;
        quad.z[0] = z;
        quad.x[1] = x + width;
        quad.y[1] = y;
        quad.z[1] = z;
        quad.x[2] = x + width;
        quad.y[2] = y + 1.0F;
        quad.z[2] = z;
        quad.x[3] = x;
        quad.y[3] = y + 1.0F;
        quad.z[3] = z;
        quad.u[0] = 0.0F;
        quad.v[0] = 0.0F;
        quad.u[1] = 1.0F;
        quad.v[1] = 0.0F;
        quad.u[2] = 1.0F;
        quad.v[2] = 1.0F;
        quad.u[3] = 0.0F;
        quad.v[3] = 1.0F;
        quad.normalZ = 1.0F;
        return quad;
    }

    static SectionMeshAccumulator.Surface opaqueSurface(TestSprite sprite) {
        return surface(
                -1,
                CapturedSectionGeometry.Layer.OPAQUE,
                CapturedSectionGeometry.Surface.MERGEABLE,
                sprite,
                TransmissiveTopology.NONE,
                0);
    }

    static SectionMeshAccumulator.Surface cutoutSurface(TestSprite sprite) {
        return surface(
                -1,
                CapturedSectionGeometry.Layer.CUTOUT,
                CapturedSectionGeometry.Surface.MERGEABLE,
                sprite,
                TransmissiveTopology.NONE,
                0);
    }

    static SectionMeshAccumulator.Surface transmissiveSurface(
            TestSprite sprite, boolean water) {
        return transmissiveSurface(sprite, water, 0);
    }

    private static SectionMeshAccumulator.Surface transmissiveSurface(
            TestSprite sprite, boolean water, int mediumId) {
        return surface(
                -1,
                CapturedSectionGeometry.Layer.TRANSLUCENT,
                CapturedSectionGeometry.Surface.MERGEABLE
                        | (water ? CapturedSectionGeometry.Surface.WATER : 0),
                sprite,
                TransmissiveTopology.SOLID,
                mediumId);
    }

    private static SectionMeshAccumulator.Surface surface(
            int color,
            CapturedSectionGeometry.Layer layer,
            int flags,
            TestSprite sprite,
            TransmissiveTopology topology,
            int mediumId) {
        return new SectionMeshAccumulator.Surface().set(
                CapturedSectionGeometry.Surface.uniform(
                        color,
                        layer,
                        flags,
                        0,
                        sprite.sprite()),
                topology,
                mediumId);
    }

}
