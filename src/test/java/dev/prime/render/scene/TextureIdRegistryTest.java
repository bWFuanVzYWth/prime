package dev.prime.render.scene;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class TextureIdRegistryTest {
    @Test
    void appendsNewTexturesWithoutRenumberingExistingTextures() {
        TextureIdRegistry registry = new TextureIdRegistry();
        SpriteId stone = new SpriteId("minecraft", "block/stone");
        SpriteId glass = new SpriteId("minecraft", "block/glass");
        SpriteId modded = new SpriteId("example", "block/modded");

        int stoneId = registry.resolve(stone);
        int glassId = registry.resolve(glass);
        int moddedId = registry.resolve(modded);

        assertEquals(stoneId, registry.resolve(stone));
        assertEquals(glassId, registry.resolve(glass));
        assertTrue(moddedId > Math.max(stoneId, glassId));
        assertEquals(moddedId, registry.resolve(modded));
    }

    @Test
    void temporaryRemovalAndLaterReappearanceKeepTheRetiredIdentityReserved() {
        TextureIdRegistry registry = new TextureIdRegistry();
        SpriteId stone = new SpriteId("minecraft", "block/stone");
        SpriteId glass = new SpriteId("minecraft", "block/glass");
        int stoneId = registry.resolve(stone);
        int glassId = registry.resolve(glass);

        // A later catalog omits glass and introduces copper. The registry deliberately persists
        // across resource generations, so the omitted identity remains retired rather than free.
        assertEquals(stoneId, registry.resolve(stone));
        int copperId = registry.resolve(new SpriteId("minecraft", "block/copper_block"));
        assertTrue(copperId > glassId);

        assertEquals(glassId, registry.resolve(glass));
        assertTrue(registry.resolve(new SpriteId("example", "block/later")) > copperId);
    }
}
