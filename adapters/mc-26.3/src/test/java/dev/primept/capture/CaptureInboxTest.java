package dev.primept.capture;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.renderer.texture.SpriteLoader;
import net.minecraft.core.SectionPos;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CaptureInboxTest {
    private static final SectionPos SECTION = SectionPos.of(-7, 3, 9);
    private static SourceQuads empty() { return new SourceQuads(); }

    @Test void worldResetAndAtlasReloadRejectInFlightOldGeometry() {
        var inbox = new CaptureInbox(true);
        var beforeReset = inbox.begin(SECTION);
        inbox.reset();
        inbox.capture(beforeReset, empty());
        assertNull(inbox.poll());
        var beforeReload = inbox.begin(SECTION);
        inbox.captureAtlas(new SpriteLoader.Preparations(1, 1, 0, null, Map.of(), CompletableFuture.completedFuture(null)));
        inbox.capture(beforeReload, empty());
        assertNull(inbox.poll());
        assertNotNull(inbox.atlas());
        assertNull(inbox.failure());
    }

    @Test void unloadRejectsLateWorkerAndRemovesTheWholeSection() {
        var inbox = new CaptureInbox(true);
        inbox.capture(inbox.begin(SECTION), empty());
        var pendingWorker = inbox.begin(SECTION);
        inbox.dropChunk(-7, 9);
        inbox.capture(pendingWorker, empty());
        var removal = inbox.poll();
        assertNotNull(removal);
        assertTrue(removal.removal());
        assertEquals(1, removal.packets().size());
        assertNull(inbox.poll());
        assertTrue(inbox.sections().isEmpty());
    }

    @Test void mostRecentCompilationWinsRegardlessOfCompletionOrder() {
        var inbox = new CaptureInbox(true);
        var old = inbox.begin(SECTION);
        var fresh = inbox.begin(SECTION);
        inbox.capture(fresh, empty());
        inbox.capture(old, empty());
        assertEquals(fresh.revision(), inbox.poll().revision());
        assertNull(inbox.poll());

        inbox.capture(inbox.begin(SECTION), empty());
        var newest = inbox.begin(SECTION);
        inbox.capture(newest, empty());
        assertEquals(newest.revision(), inbox.poll().revision());
        assertNull(inbox.poll());
    }

    @Test void queueOverflowDisablesCaptureInsteadOfDroppingVisibleGeometry() {
        var inbox = new CaptureInbox(true);
        for (int x = 0; x < 4097; x++) {
            var section = SectionPos.of(x, 0, 0);
            inbox.capture(inbox.begin(section), empty());
        }
        assertNotNull(inbox.failure());
        assertNull(inbox.begin(SECTION));
        assertNull(inbox.poll());
    }
}
