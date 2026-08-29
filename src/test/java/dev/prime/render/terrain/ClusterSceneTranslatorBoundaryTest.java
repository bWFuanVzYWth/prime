package dev.prime.render.terrain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.prime.render.scene.CapturedSectionGeometry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

final class ClusterSceneTranslatorBoundaryTest {
    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2})
    void rejectsNullInputsAtThePublicTranslationBoundary(int missingArgument) {
        CapturedCluster captured = new CapturedCluster.Builder(0, 0, 0).build();
        ClusterTranslationSettings settings = new ClusterTranslationSettings(
                false,
                TerrainMemoryBudget.TARGET_SEGMENT_TRIANGLES,
                OpacityMicromapData.SUBDIVISION_LEVEL + 2,
                true,
                VoxelSurfaceSettings.BASE_HEIGHT,
                false,
                false);

        CapturedCluster capturedArgument = missingArgument == 0 ? null : captured;
        LabPbrMaterialSet materialsArgument =
                missingArgument == 1 ? null : LabPbrMaterialSet.EMPTY;
        ClusterTranslationSettings settingsArgument = missingArgument == 2 ? null : settings;
        assertThrows(NullPointerException.class, () -> ClusterSceneTranslator.translate(
                capturedArgument, materialsArgument, settingsArgument));
    }

    @Test
    void rejectsInvalidAttributesEvenWhenTheQuadHasZeroArea() {
        try (SectionMeshAccumulatorTest.TestSprite sprite =
                new SectionMeshAccumulatorTest.TestSprite("invalid_zero_area")) {
            CapturedSectionGeometry.MutableQuad invalidUv = zeroAreaQuad();
            invalidUv.u[2] = 1.5F;
            assertThrows(
                    IllegalArgumentException.class,
                    () -> translate(invalidUv, surface(sprite, 0)));

            CapturedSectionGeometry.MutableQuad invalidPosition = zeroAreaQuad();
            invalidPosition.x[1] = Float.NaN;
            assertThrows(
                    IllegalArgumentException.class,
                    () -> translate(invalidPosition, surface(sprite, 0)));

            CapturedSectionGeometry.MutableQuad invalidNormal = zeroAreaQuad();
            invalidNormal.normalZ = Float.POSITIVE_INFINITY;
            assertThrows(
                    IllegalArgumentException.class,
                    () -> translate(invalidNormal, surface(sprite, 0)));
        }
    }

    @Test
    void zeroAreaNonFluidQuadProducesNoPrimitiveRelationOrEmitter() {
        try (SectionMeshAccumulatorTest.TestSprite sprite =
                new SectionMeshAccumulatorTest.TestSprite("zero_area")) {
            CpuClusterMesh result = translate(zeroAreaQuad(), surface(sprite, 15));

            assertEquals(0L, result.triangleCount());
            assertEquals(0L, result.surfaceRelationBytes());
            assertEquals(0, result.lights().emitterCount());
        }
    }

    private static CpuClusterMesh translate(
            CapturedSectionGeometry.MutableQuad quad,
            CapturedSectionGeometry.Surface surface) {
        CapturedSectionGeometry.Builder section = new CapturedSectionGeometry.Builder();
        section.add(quad, surface);
        CapturedCluster.Builder cluster = new CapturedCluster.Builder(0, 0, 0);
        cluster.add(0, 0, 0, section.build());
        return ClusterSceneTranslator.translate(
                cluster.build(), LabPbrMaterialSet.EMPTY, settings());
    }

    private static CapturedSectionGeometry.MutableQuad zeroAreaQuad() {
        CapturedSectionGeometry.MutableQuad quad = new CapturedSectionGeometry.MutableQuad();
        for (int vertex = 0; vertex < 4; vertex++) {
            quad.x[vertex] = 1.0F;
            quad.y[vertex] = 2.0F;
            quad.z[vertex] = 3.0F;
            quad.u[vertex] = vertex == 1 || vertex == 2 ? 1.0F : 0.0F;
            quad.v[vertex] = vertex >= 2 ? 1.0F : 0.0F;
        }
        quad.normalZ = 1.0F;
        return quad;
    }

    private static CapturedSectionGeometry.Surface surface(
            SectionMeshAccumulatorTest.TestSprite sprite, int emission) {
        return CapturedSectionGeometry.Surface.uniform(
                0xffff_ffff,
                CapturedSectionGeometry.Layer.OPAQUE,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                emission,
                sprite.sprite());
    }

    private static ClusterTranslationSettings settings() {
        return new ClusterTranslationSettings(
                false, 64, 2, 2, false, 0.0F, false, false);
    }
}
