package dev.primept.capture;

import java.util.Map;
import java.util.IdentityHashMap;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.renderer.texture.SpriteLoader;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ResourceGenerationTest {
    @Test
    void releasingSourceOwnershipDropsObservedDirectoriesWithoutReusingGeneration()
            throws ReflectiveOperationException {
        var inbox = new CaptureInbox();
        inbox.enable();
        var upload = new SpriteLoader.Preparations(1, 1, 0, null, Map.of(),
                                                   CompletableFuture.completedFuture(null));
        inbox.captureAtlas(upload);
        var models = new Object();
        var fluids = new Object();
        long previous = inbox.resourceGeneration(models, fluids);
        inbox.releaseSources();
        for (String name : new String[] {"observedModels", "observedFluids"}) {
            var field = CaptureInbox.class.getDeclaredField(name);
            field.setAccessible(true);
            assertNull(field.get(inbox), "Inactive capture must release its resource owner");
        }
        assertNull(inbox.atlas());
        assertTrue(inbox.sprites().isEmpty());
        inbox.enable();
        inbox.captureAtlas(upload);
        assertTrue(inbox.resourceGeneration(models, fluids) > previous);
    }

    @Test
    void worldResetRetainsResourcesWhileActualAtlasAndModelReplacementsAdvanceGeneration() {
        var inbox = new CaptureInbox();
        inbox.enable();
        var upload = new SpriteLoader.Preparations(1, 1, 0, null, Map.of(),
                                                   CompletableFuture.completedFuture(null));
        inbox.captureAtlas(upload);
        var models = new Object();
        var fluids = new Object();
        long first = inbox.resourceGeneration(models, fluids);
        assertTrue(first > 0);
        inbox.reset();
        inbox.disable();
        inbox.enable();
        assertEquals(first, inbox.resourceGeneration(models, fluids));
        long epoch = inbox.epoch();
        var replacementModels = new Object();
        long second = inbox.resourceGeneration(replacementModels, fluids);
        assertTrue(second > first);
        assertEquals(epoch, inbox.epoch(), "Model-only resource replacement is not a world reset");
        assertEquals(second, inbox.resourceGeneration(replacementModels, fluids));
        inbox.captureAtlas(upload);
        assertTrue(inbox.resourceGeneration(replacementModels, fluids) > second);
    }
    @Test
    void actualItemAtlasReplacementRevokesPrivateBindingsAndAdvancesOnlyResourceGeneration()
            throws ReflectiveOperationException {
        var inbox = new CaptureInbox();
        inbox.enable();
        var blocks = new SpriteLoader.Preparations(1, 1, 0, null, Map.of(),
                                                   CompletableFuture.completedFuture(null));
        inbox.captureAtlas(blocks);
        var sprite = blankSprite();
        var upload = new SpriteLoader.Preparations(
                64, 64, 0, null,
                Map.of(Identifier.fromNamespaceAndPath("minecraft", "item/resource_fixture"),
                       sprite),
                CompletableFuture.completedFuture(null));
        var models = new Object();
        var fluids = new Object();
        long first = inbox.resourceGeneration(models, fluids), epoch = inbox.epoch();
        inbox.captureItemSprites(upload, 42);
        long second = inbox.resourceGeneration(models, fluids);
        assertTrue(second > first);
        assertEquals(epoch, inbox.epoch(),
                     "Item atlas replacement is independent of world identity");
        assertEquals(second, inbox.resourceGeneration(models, fluids));
        assertEquals(java.util.List.of(sprite), inbox.itemSprites());
        var sourceIds = new IdentityHashMap<TextureAtlasSprite, Integer>();
        sourceIds.put(sprite, 7);
        assertEquals(0, inbox.itemTexture(sprite, 42),
                     "Preparation alone never publishes a native identity");
        inbox.publishItemSprites(sourceIds);
        assertEquals(0x40000007, inbox.itemTexture(sprite, 42));
        assertEquals(0, inbox.itemTexture(sprite, 43),
                     "Custom draw must retain its actual bound texture");
        assertEquals(0, inbox.itemTexture(null, 42));
        assertEquals(0, inbox.itemTexture(blankSprite(), 42),
                     "Equal-looking foreign sprite identity is unknown");
        inbox.reset();
        inbox.disable();
        inbox.enable();
        assertEquals(second, inbox.resourceGeneration(models, fluids));
        assertEquals(0x40000007, inbox.itemTexture(sprite, 42),
                     "World resets retain current source resources");
        inbox.captureItemSprites(upload, 44);
        assertEquals(0, inbox.itemTexture(sprite, 42));
        assertEquals(0, inbox.itemTexture(sprite, 44));
        long third = inbox.resourceGeneration(models, fluids);
        assertTrue(third > second);
        sourceIds.put(sprite, 9);
        inbox.publishItemSprites(sourceIds);
        assertEquals(0x40000009, inbox.itemTexture(sprite, 44));
        sourceIds.put(sprite, 99);
        assertEquals(0x40000009, inbox.itemTexture(sprite, 44),
                     "Published lookup owns a snapshot of caller IDs");
        inbox.captureAtlas(blocks);
        assertEquals(0, inbox.itemTexture(sprite, 44),
                     "Shared resource replacement revokes published item IDs");
        inbox.publishItemSprites(sourceIds);
        inbox.releaseSources();
        assertTrue(inbox.itemSprites().isEmpty());
        assertEquals(0, inbox.itemTexture(sprite, 44));
        inbox.enable();
        inbox.captureAtlas(blocks);
        assertTrue(inbox.resourceGeneration(models, fluids) > third);
    }
    private static TextureAtlasSprite blankSprite() throws ReflectiveOperationException {
        var unsafe = Class.forName("sun.misc.Unsafe");
        var field = unsafe.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        return (TextureAtlasSprite)unsafe.getMethod("allocateInstance", Class.class)
                .invoke(field.get(null), TextureAtlasSprite.class);
    }
}
