package dev.prime.render.terrain;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.prime.render.scene.CapturedSectionGeometry;
import dev.prime.render.scene.CapturedSprite;
import dev.prime.render.scene.SpriteId;
import java.util.List;
import org.junit.jupiter.api.Test;

final class ClusterTranslationScaleFixtureTest {
    private static final CapturedSprite BASE = sprite("scale_base", 1);
    private static final CapturedSprite OVERLAY = sprite("scale_overlay", 2);
    private static final CapturedSprite MEDIUM_A = sprite("scale_medium_a", 3);
    private static final CapturedSprite MEDIUM_B = sprite("scale_medium_b", 4);

    @Test
    void singleSectionMixedOverlayFixtureCollapsesDuplicateRasterSubmissions() {
        CapturedSectionGeometry.Builder section = new CapturedSectionGeometry.Builder();
        CapturedSectionGeometry.Surface base = surface(
                BASE, CapturedSectionGeometry.Layer.OPAQUE, false, false, null);
        CapturedSectionGeometry.Surface overlay = surface(
                OVERLAY, CapturedSectionGeometry.Layer.CUTOUT, true, false, null);
        for (int index = 0; index < 384; index++) {
            section.add(unitXFace(1.0F, 1.0F), base);
        }
        for (int index = 0; index < 384; index++) {
            section.add(unitXFace(1.0F, 1.0F), overlay);
        }
        CapturedCluster cluster = clusterWithSection(0, 0, 0, section.build());

        TransparentBoundaryResolver.Result result =
                TransparentBoundaryResolver.resolve(cluster, true);

        assertEquals(1, result.section(0).size());
        assertTrue(result.section(0).stream().allMatch(quad ->
                quad.definition().interfaceMode() == SurfaceDefinition.InterfaceMode.OVERLAY));
    }

    @Test
    void fragmentedBoundaryFixtureProducesAtLeast1024AtomicCells() {
        CapturedSectionGeometry.Builder section = new CapturedSectionGeometry.Builder();
        CapturedSectionGeometry.Surface positive = surface(
                BASE,
                CapturedSectionGeometry.Layer.OPAQUE,
                false,
                false,
                new CapturedSectionGeometry.BlockFacts(-1, 0, 0));
        CapturedSectionGeometry.Surface negative = surface(
                OVERLAY,
                CapturedSectionGeometry.Layer.OPAQUE,
                false,
                false,
                new CapturedSectionGeometry.BlockFacts(0, 0, 0));
        for (int strip = 0; strip < 32; strip++) {
            float minimum = strip / 32.0F;
            float maximum = (strip + 1) / 32.0F;
            section.add(rectangleXFace(0.0F, 1.0F, minimum, maximum, 0.0F, 1.0F), positive);
            section.add(rectangleXFace(0.0F, -1.0F, 0.0F, 1.0F, minimum, maximum), negative);
        }
        CapturedCluster cluster = clusterWithSection(0, 0, 0, section.build());

        TransparentBoundaryResolver.Result result =
                TransparentBoundaryResolver.resolve(cluster, true);

        assertEquals(1024, result.section(0).size());
    }

    @Test
    void fourByFourByFourMixedClusterIsByteDeterministic() {
        CapturedCluster input = mixedCluster();

        CpuClusterMesh first = ClusterSceneTranslator.translate(
                input, LabPbrMaterialSet.EMPTY, settings());
        CpuClusterMesh second = ClusterSceneTranslator.translate(
                input, LabPbrMaterialSet.EMPTY, settings());

        assertTrue(first.triangleCount() >= SectionCluster.SECTION_COUNT * 2L);
        assertTrue(first.transmissiveTriangleCount() > 0L);
        assertTrue(first.surfaceRelationBytes() > 0L);
        assertEquals(first.segments().size(), second.segments().size());
        for (int index = 0; index < first.segments().size(); index++) {
            CpuClusterMesh.Segment expected = first.segments().get(index);
            CpuClusterMesh.Segment actual = second.segments().get(index);
            assertArrayEquals(expected.positions(), actual.positions());
            assertArrayEquals(expected.primitiveRecords(), actual.primitiveRecords());
            assertArrayEquals(expected.surfaceRelationRecords(), actual.surfaceRelationRecords());
        }
    }

    private static CapturedCluster mixedCluster() {
        CapturedCluster.Builder cluster = new CapturedCluster.Builder(0, 0, 0);
        CapturedSectionGeometry.Surface ordinary = surface(
                BASE, CapturedSectionGeometry.Layer.OPAQUE, false, false, null);
        for (int z = 0; z < SectionCluster.SECTION_SIZE; z++) {
            for (int y = 0; y < SectionCluster.SECTION_SIZE; y++) {
                for (int x = 0; x < SectionCluster.SECTION_SIZE; x++) {
                    CapturedSectionGeometry.Builder section =
                            new CapturedSectionGeometry.Builder();
                    section.add(rectangleXFace(2.0F, 1.0F, 2.0F, 3.0F, 2.0F, 3.0F), ordinary);
                    if (x == 0 && y == 0 && z == 0) {
                        section.add(
                                unitXFace(16.0F, 1.0F),
                                surface(
                                        MEDIUM_A,
                                        CapturedSectionGeometry.Layer.TRANSLUCENT,
                                        false,
                                        false,
                                        new CapturedSectionGeometry.BlockFacts(15, 0, 0, 1)));
                        section.addPeer(
                                unitXFace(0.0F, -1.0F),
                                surface(
                                        MEDIUM_A,
                                        CapturedSectionGeometry.Layer.TRANSLUCENT,
                                        false,
                                        false,
                                        new CapturedSectionGeometry.BlockFacts(0, 0, 0, 1)));
                    }
                    if (x == 1 && y == 0 && z == 0) {
                        section.add(
                                unitXFace(0.0F, -1.0F),
                                surface(
                                        MEDIUM_B,
                                        CapturedSectionGeometry.Layer.TRANSLUCENT,
                                        false,
                                        false,
                                        new CapturedSectionGeometry.BlockFacts(16, 0, 0, 2)));
                    }
                    if (x == 3 && y == 3 && z == 3) {
                        section.add(fluidTop(), fluidSurface());
                    }
                    cluster.add(x, y, z, section.build());
                }
            }
        }
        return cluster.build();
    }

    private static CapturedSectionGeometry.MutableQuad fluidTop() {
        CapturedSectionGeometry.MutableQuad quad = new CapturedSectionGeometry.MutableQuad();
        float[] x = {0.0F, 0.0F, 1.0F, 1.0F};
        float[] z = {0.0F, 1.0F, 1.0F, 0.0F};
        for (int vertex = 0; vertex < 4; vertex++) {
            quad.x[vertex] = x[vertex];
            quad.y[vertex] = 1.0F;
            quad.z[vertex] = z[vertex];
            quad.u[vertex] = x[vertex];
            quad.v[vertex] = z[vertex];
        }
        quad.normalY = 1.0F;
        return quad;
    }

    private static CapturedSectionGeometry.Surface fluidSurface() {
        return new CapturedSectionGeometry.Surface(
                0xff40_80c0,
                0xff40_80c0,
                0xff40_80c0,
                0xff40_80c0,
                CapturedSectionGeometry.Layer.TRANSLUCENT,
                false,
                false,
                false,
                true,
                false,
                true,
                false,
                0,
                MEDIUM_A,
                new CapturedSectionGeometry.FluidFacts(0, 0, 0, false, 0),
                null);
    }

    private static CapturedCluster clusterWithSection(
            int x, int y, int z, CapturedSectionGeometry section) {
        CapturedCluster.Builder cluster = new CapturedCluster.Builder(0, 0, 0);
        cluster.add(x, y, z, section);
        return cluster.build();
    }

    private static CapturedSectionGeometry.Surface surface(
            CapturedSprite sprite,
            CapturedSectionGeometry.Layer layer,
            boolean rasterOverlay,
            boolean animated,
            CapturedSectionGeometry.BlockFacts block) {
        return CapturedSectionGeometry.Surface.uniform(
                0xff80_a0c0,
                layer,
                false,
                false,
                animated,
                false,
                false,
                true,
                rasterOverlay,
                0,
                sprite,
                block);
    }

    private static CapturedSectionGeometry.MutableQuad unitXFace(float plane, float normal) {
        return rectangleXFace(plane, normal, 0.0F, 1.0F, 0.0F, 1.0F);
    }

    private static CapturedSectionGeometry.MutableQuad rectangleXFace(
            float plane,
            float normal,
            float minimumY,
            float maximumY,
            float minimumZ,
            float maximumZ) {
        float[] y = normal > 0.0F
                ? new float[] {minimumY, maximumY, maximumY, minimumY}
                : new float[] {minimumY, minimumY, maximumY, maximumY};
        float[] z = normal > 0.0F
                ? new float[] {minimumZ, minimumZ, maximumZ, maximumZ}
                : new float[] {minimumZ, maximumZ, maximumZ, minimumZ};
        CapturedSectionGeometry.MutableQuad quad = new CapturedSectionGeometry.MutableQuad();
        for (int vertex = 0; vertex < 4; vertex++) {
            quad.x[vertex] = plane;
            quad.y[vertex] = y[vertex];
            quad.z[vertex] = z[vertex];
            quad.u[vertex] = (y[vertex] - minimumY) / (maximumY - minimumY);
            quad.v[vertex] = (z[vertex] - minimumZ) / (maximumZ - minimumZ);
        }
        quad.normalX = normal;
        return quad;
    }

    private static ClusterTranslationSettings settings() {
        return new ClusterTranslationSettings(
                false, 512, 2, 2, false, 0.0F, false, false);
    }

    private static CapturedSprite sprite(String path, int textureId) {
        return new CapturedSprite(
                new SpriteId("prime", path),
                textureId,
                16,
                16,
                false,
                new int[] {0},
                null);
    }
}
