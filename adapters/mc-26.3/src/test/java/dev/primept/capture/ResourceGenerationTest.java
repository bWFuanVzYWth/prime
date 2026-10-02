package dev.primept.capture;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.renderer.texture.SpriteLoader;
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
}
