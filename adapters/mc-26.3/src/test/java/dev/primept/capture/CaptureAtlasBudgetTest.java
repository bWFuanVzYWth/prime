package dev.primept.capture;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.renderer.texture.SpriteLoader;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CaptureAtlasBudgetTest {
    @Test
    void oversizedAtlasFailsBeforeAllocatingPixelsAndKeepsThePreviousSnapshot() {
        var inbox = new CaptureInbox();
        inbox.enable();
        inbox.captureAtlas(new SpriteLoader.Preparations(1, 1, 0, null, Map.of(),
                                                         CompletableFuture.completedFuture(null)));
        var previous = inbox.atlas();
        assertNotNull(previous);
        long epoch = inbox.epoch();
        inbox.captureAtlas(new SpriteLoader.Preparations(8192, 8192, 0, null, Map.of(),
                                                         CompletableFuture.completedFuture(null)));
        assertInstanceOf(IllegalArgumentException.class, inbox.failure());
        assertTrue(inbox.failure().getMessage().contains("packet capacity"));
        assertSame(previous, inbox.atlas());
        assertTrue(inbox.epoch() > epoch, "Rejected source capture must invalidate the producer");
    }
}
