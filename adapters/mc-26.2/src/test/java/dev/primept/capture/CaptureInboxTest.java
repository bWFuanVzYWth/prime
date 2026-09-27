package dev.primept.capture;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.renderer.texture.SpriteLoader;
import net.minecraft.core.SectionPos;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CaptureInboxTest {
    private static final SectionPos SECTION = SectionPos.of(-7, 3, 9);
    private static SourceQuads empty() {
        return new SourceQuads();
    }
    private static SourceQuads quad() {
        var source = new SourceQuads();
        source.vertex(SourceQuads.OPAQUE, 0, 0, 0, -1, 0, 0);
        source.vertex(SourceQuads.OPAQUE, 1, 0, 0, -1, 1, 0);
        source.vertex(SourceQuads.OPAQUE, 1, 1, 0, -1, 1, 1);
        source.vertex(SourceQuads.OPAQUE, 0, 1, 0, -1, 0, 1);
        return source;
    }

    @Test
    void residentSectionsCanExceedTheOldCountLimitWhenTheQueueIsDrained() {
        var inbox = new CaptureInbox(true);
        int count = 32769;
        for (int x = 0; x < count; ++x) {
            var section = SectionPos.of(x, 0, 0);
            inbox.capture(inbox.begin(section), quad());
            var publication = inbox.poll();
            assertNotNull(publication, "Every admitted source must publish its actual geometry");
            assertEquals(section.asLong(), publication.section());
            assertFalse(publication.removal());
            assertEquals(208, publication.packets().getFirst().length);
        }
        assertEquals(count, inbox.profileSnapshot().sections());
        assertEquals(count, inbox.sections().size());
        assertEquals(0, inbox.profileSnapshot().batches());
        assertEquals(0, inbox.profileSnapshot().bytes());
        assertNull(inbox.failure());
        assertNotNull(inbox.begin(SECTION));
    }

    @Test
    void longChunkHistoryRetainsTheGenerationThatInvalidatesOldWorkers() {
        var inbox = new CaptureInbox(true);
        var first = SectionPos.of(0, 0, 0);
        var last = SectionPos.of(32768, 0, 0);
        var oldFirst = inbox.begin(first);
        var oldLast = inbox.begin(last);
        long epoch = inbox.epoch();
        for (int x = 0; x <= 32768; ++x) {
            inbox.dropChunk(x, 0);
            assertNull(inbox.poll());
        }
        assertNull(inbox.failure());
        assertEquals(epoch, inbox.epoch(),
                     "History growth must not reset or disable the resource epoch");
        inbox.capture(oldFirst, quad());
        inbox.capture(oldLast, quad());
        assertNull(inbox.poll(), "Both early and recent old-generation workers remain invalid");
        var fresh = inbox.begin(first);
        assertNotNull(fresh);
        inbox.capture(fresh, quad());
        assertEquals(fresh.revision(), inbox.poll().revision());
        assertNull(inbox.poll());
        assertNull(inbox.failure());
    }

    @Test
    void worldResetAndAtlasReloadRejectInFlightOldGeometry() {
        var inbox = new CaptureInbox(true);
        var beforeReset = inbox.begin(SECTION);
        inbox.reset();
        inbox.capture(beforeReset, empty());
        assertNull(inbox.poll());
        var beforeReload = inbox.begin(SECTION);
        inbox.captureAtlas(new SpriteLoader.Preparations(1, 1, 0, null, Map.of(),
                                                         CompletableFuture.completedFuture(null)));
        inbox.capture(beforeReload, empty());
        assertNull(inbox.poll());
        assertNotNull(inbox.atlas());
        assertNull(inbox.failure());
    }

    @Test
    void unloadRejectsLateWorkerAndRemovesTheWholeSection() {
        var inbox = new CaptureInbox(true);
        inbox.capture(inbox.begin(SECTION), empty());
        var pendingWorker = inbox.begin(SECTION);
        inbox.dropChunk(-7, 9);
        inbox.capture(pendingWorker, empty());
        var removal = inbox.poll();
        assertNotNull(removal);
        assertTrue(removal.removal());
        assertEquals(1, removal.packets().size());
        var packet = java.nio.ByteBuffer.wrap(removal.packets().getFirst())
                             .order(java.nio.ByteOrder.LITTLE_ENDIAN);
        assertEquals(3, packet.getInt(8),
                     "Unload must revoke availability, not publish a complete empty section");
        assertNull(inbox.poll());
        assertTrue(inbox.sections().isEmpty());
    }

    @Test
    void chunkIndexDeduplicatesPublicationsAndRetiresOnlyTheTargetColumn() {
        var inbox = new CaptureInbox(true);
        var sameColumn = SectionPos.of(SECTION.x(), SECTION.y() + 1, SECTION.z());
        var otherColumn = SectionPos.of(SECTION.x() + 1, SECTION.y(), SECTION.z());
        inbox.capture(inbox.begin(SECTION), empty());
        inbox.capture(inbox.begin(sameColumn), empty());
        inbox.capture(inbox.begin(otherColumn), empty());
        inbox.capture(inbox.begin(SECTION), empty());
        inbox.capture(inbox.begin(SECTION), empty());
        var staleTarget = inbox.begin(SECTION);
        var survivingWorker = inbox.begin(otherColumn);

        inbox.dropChunk(SECTION.x(), SECTION.z());
        inbox.capture(staleTarget, empty());
        inbox.capture(survivingWorker, empty());
        assertEquals(java.util.Set.of(otherColumn.asLong()),
                     new java.util.HashSet<>(inbox.sections()));
        var batches = new java.util.ArrayList<CaptureInbox.Batch>();
        for (var batch = inbox.poll(); batch != null; batch = inbox.poll())
            batches.add(batch);
        assertEquals(3, batches.size(),
                     "Repeated publications must not duplicate column membership");
        assertEquals(java.util.Set.of(SECTION.asLong(), sameColumn.asLong()),
                     batches.stream()
                             .filter(CaptureInbox.Batch::removal)
                             .map(CaptureInbox.Batch::section)
                             .collect(java.util.stream.Collectors.toSet()));
        var survivor = batches.stream().filter(batch -> !batch.removal()).findFirst().orElseThrow();
        assertEquals(otherColumn.asLong(), survivor.section());
        assertEquals(survivingWorker.revision(), survivor.revision());

        inbox.capture(inbox.begin(SECTION), empty());
        assertEquals(SECTION.asLong(), inbox.poll().section());
        inbox.dropChunk(SECTION.x(), SECTION.z());
        var removal = inbox.poll();
        assertTrue(removal.removal());
        assertEquals(SECTION.asLong(), removal.section());
        assertNull(inbox.poll());
        assertEquals(java.util.Set.of(otherColumn.asLong()),
                     new java.util.HashSet<>(inbox.sections()));
        assertNull(inbox.failure());
    }

    @Test
    void mostRecentCompilationWinsRegardlessOfCompletionOrder() {
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

    @Test
    void queueOverflowDisablesCaptureInsteadOfDroppingVisibleGeometry() {
        var inbox = new CaptureInbox(true);
        for (int x = 0; x < 4097; x++) {
            var section = SectionPos.of(x, 0, 0);
            inbox.capture(inbox.begin(section), empty());
        }
        assertNotNull(inbox.failure());
        assertNull(inbox.begin(SECTION));
        assertNull(inbox.poll());
    }

    @Test
    void titleResourceReloadUpdatesPixelsWhileWorldCaptureIsDisabled() {
        var inbox = new CaptureInbox(true);
        inbox.captureAtlas(new SpriteLoader.Preparations(1, 1, 0, null, Map.of(),
                                                         CompletableFuture.completedFuture(null)));
        var first = inbox.atlas();
        var oldWorld = inbox.begin(SECTION);
        inbox.disable();
        long titleEpoch = inbox.epoch();
        assertNull(inbox.begin(SECTION));
        inbox.captureAtlas(new SpriteLoader.Preparations(2, 3, 0, null, Map.of(),
                                                         CompletableFuture.completedFuture(null)));
        var reloaded = inbox.atlas();
        assertNotSame(first, reloaded);
        assertEquals(2, reloaded.width());
        assertEquals(3, reloaded.height());
        assertEquals(24, reloaded.rgba().length);
        assertTrue(reloaded.version() > first.version());
        assertTrue(inbox.epoch() > titleEpoch);
        assertNull(inbox.begin(SECTION), "A resource upload must not reactivate world compilation");
        inbox.enable();
        assertSame(reloaded, inbox.atlas(), "The next world uses the actual latest source upload");
        inbox.capture(oldWorld, empty());
        assertNull(inbox.poll());
        inbox.capture(inbox.begin(SECTION), empty());
        assertNotNull(inbox.poll());
        assertNull(inbox.failure());
    }

    @Test
    void selectingVanillaReleasesSourcesAndRequiresANewActualUpload() {
        var inbox = new CaptureInbox(true);
        var upload = new SpriteLoader.Preparations(2, 1, 0, null, Map.of(),
                                                   CompletableFuture.completedFuture(null));
        inbox.captureAtlas(upload);
        var old = inbox.begin(SECTION);
        inbox.releaseSources();
        long epoch = inbox.epoch();
        inbox.captureAtlas(upload);
        assertNull(inbox.atlas());
        assertEquals(epoch, inbox.epoch());
        assertNull(inbox.begin(SECTION));
        inbox.enable();
        assertNull(inbox.atlas(), "Enabling capture cannot reconstruct a released source asset");
        inbox.capture(old, empty());
        assertNull(inbox.poll());
        inbox.captureAtlas(upload);
        assertEquals(8, inbox.atlas().rgba().length);
        assertNull(inbox.failure());
    }
}
