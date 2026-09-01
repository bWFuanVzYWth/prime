package dev.prime.render.terrain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.prime.render.shader.ShaderAbi;
import dev.prime.render.vulkan.terrain.TerrainScene;
import java.util.List;
import org.junit.jupiter.api.Test;

final class SceneStatisticsTest {
    @Test
    void residentViewPublishesItsCapturedStatistics() {
        TerrainScene.SceneStatistics statistics =
                new TerrainScene.SceneStatistics(17, 101L, 509L, 23, 31);
        TerrainScene.ResidentSceneView view =
                new TerrainScene.ResidentSceneView(
                        1L,
                        2L,
                        new TerrainScene.TintSampleBinding(3L, 8L),
                        3,
                        4,
                        5,
                        6L,
                        7L,
                        8L,
                        List.of(),
                        statistics);

        assertEquals(statistics, view.statistics());
    }

    @Test
    void negativeCountsAreRejectedAtThePublicationBoundary() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new TerrainScene.SceneStatistics(-1, 0L, 0L, 0, 0));
        assertThrows(
                IllegalArgumentException.class,
                () -> new TerrainScene.SceneStatistics(0, -1L, 0L, 0, 0));
        assertThrows(
                IllegalArgumentException.class,
                () -> new TerrainScene.SceneStatistics(0, 0L, -1L, 0, 0));
        assertThrows(
                IllegalArgumentException.class,
                () -> new TerrainScene.SceneStatistics(0, 0L, 0L, -1, 0));
        assertThrows(
                IllegalArgumentException.class,
                () -> new TerrainScene.SceneStatistics(0, 0L, 0L, 0, -1));
        assertThrows(
                IllegalArgumentException.class,
                () -> new TerrainScene.SceneStatistics(
                        0,
                        0L,
                        0L,
                        0,
                        0,
                        TextureTintUsage.EMPTY,
                        MaterialTableCandidate.EMPTY,
                        3L,
                        4L));
    }

    @Test
    void tintIdStatisticsRejectImpossibleRegistrySnapshots() {
        assertEquals(new TerrainScene.TintIdStatistics(1, 0),
                new TerrainScene.TintIdStatistics(1, 0));
        assertThrows(
                IllegalArgumentException.class,
                () -> new TerrainScene.TintIdStatistics(0, 0));
        assertThrows(
                IllegalArgumentException.class,
                () -> new TerrainScene.TintIdStatistics(3, 1));
        assertThrows(
                IllegalArgumentException.class,
                () -> new TerrainScene.TintIdStatistics(1, 0x1_0000));
    }

    @Test
    void materialCoreBindingRequiresTheCompleteFixedU16Table() {
        long bytes = Math.multiplyExact(
                (long) MaterialIdResolver.MAX_ID + 1L,
                ShaderAbi.MATERIAL_CORE_RECORD_SIZE);

        assertEquals(
                new TerrainScene.MaterialCoreBinding(3L, bytes),
                new TerrainScene.MaterialCoreBinding(3L, bytes));
        assertThrows(
                IllegalArgumentException.class,
                () -> new TerrainScene.MaterialCoreBinding(3L, bytes - 1L));
        assertThrows(
                IllegalArgumentException.class,
                () -> new TerrainScene.MaterialCoreBinding(0L, bytes));
    }
}
