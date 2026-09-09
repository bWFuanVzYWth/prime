// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

package dev.prime.render.terrain;

import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.prime.render.shader.ShaderAbi;
import dev.prime.render.vulkan.terrain.TerrainScene;
import org.junit.jupiter.api.Test;

final class SceneStatisticsTest {
    @Test
    void materialCoreBindingRequiresTheCompleteFixedU16Table() {
        long bytes = Math.multiplyExact(
                (long) MaterialIdResolver.MAX_ID + 1L,
                ShaderAbi.MATERIAL_CORE_RECORD_SIZE);

        new TerrainScene.MaterialCoreBinding(3L, bytes);
        assertThrows(
                IllegalArgumentException.class,
                () -> new TerrainScene.MaterialCoreBinding(3L, bytes - 1L));
        assertThrows(
                IllegalArgumentException.class,
                () -> new TerrainScene.MaterialCoreBinding(0L, bytes));
    }
}
