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

    static CaptureInbox.Batch take(CaptureInbox inbox) {
        var batches = inbox.seal().batches();
        assertTrue(batches.size() <= 1, "Fixture expected a single sealed batch");
        return batches.isEmpty() ? null : batches.getFirst();
    }

    @Test
    void residentSectionsCanExceedTheOldCountLimitWhenTheQueueIsDrained() {
        var inbox = new CaptureInbox(true);
        int count = 32769;
        for (int x = 0; x < count; ++x) {
            var section = SectionPos.of(x, 0, 0);
            inbox.capture(inbox.begin(section), quad());
            var publication = CaptureInboxTest.take(inbox);
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
            assertNull(CaptureInboxTest.take(inbox));
        }
        assertNull(inbox.failure());
        assertEquals(epoch, inbox.epoch(),
                     "History growth must not reset or disable the resource epoch");
        inbox.capture(oldFirst, quad());
        inbox.capture(oldLast, quad());
        assertNull(CaptureInboxTest.take(inbox),
                   "Both early and recent old-generation workers remain invalid");
        var fresh = inbox.begin(first);
        assertNotNull(fresh);
        inbox.capture(fresh, quad());
        assertEquals(fresh.revision(), CaptureInboxTest.take(inbox).revision());
        assertNull(CaptureInboxTest.take(inbox));
        assertNull(inbox.failure());
    }

    @Test
    void worldResetAndAtlasReloadRejectInFlightOldGeometry() {
        var inbox = new CaptureInbox(true);
        var beforeReset = inbox.begin(SECTION);
        inbox.reset();
        inbox.capture(beforeReset, empty());
        assertNull(CaptureInboxTest.take(inbox));
        var beforeReload = inbox.begin(SECTION);
        inbox.captureAtlas(new SpriteLoader.Preparations(1, 1, 0, null, Map.of(),
                                                         CompletableFuture.completedFuture(null)));
        inbox.capture(beforeReload, empty());
        assertNull(CaptureInboxTest.take(inbox));
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
        var removal = CaptureInboxTest.take(inbox);
        assertNotNull(removal);
        assertTrue(removal.removal());
        assertEquals(1, removal.packets().size());
        var packet = java.nio.ByteBuffer.wrap(removal.packets().getFirst())
                             .order(java.nio.ByteOrder.LITTLE_ENDIAN);
        assertEquals(11, packet.getInt(8),
                     "Unload must revoke availability, not publish a complete empty section");
        assertNull(CaptureInboxTest.take(inbox));
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
        var batches = inbox.seal().batches();
        assertEquals(2, batches.size(), "One live replacement plus one bulk withdrawal");
        var removed = java.nio.ByteBuffer
                              .wrap(batches.stream()
                                            .filter(CaptureInbox.Batch::removal)
                                            .findFirst()
                                            .orElseThrow()
                                            .packets()
                                            .getFirst())
                              .order(java.nio.ByteOrder.LITTLE_ENDIAN);
        assertEquals(2, removed.getInt(24));
        assertEquals(java.util.Set.of(SECTION.asLong(), sameColumn.asLong()),
                     java.util.Set.of(removed.getLong(32), removed.getLong(48)));
        var survivor = batches.stream().filter(batch -> !batch.removal()).findFirst().orElseThrow();
        assertEquals(otherColumn.asLong(), survivor.section());
        assertEquals(survivingWorker.revision(), survivor.revision());

        inbox.capture(inbox.begin(SECTION), empty());
        assertEquals(SECTION.asLong(), CaptureInboxTest.take(inbox).section());
        inbox.dropChunk(SECTION.x(), SECTION.z());
        var removal = CaptureInboxTest.take(inbox);
        assertTrue(removal.removal());
        assertEquals(SECTION.asLong(), java.nio.ByteBuffer.wrap(removal.packets().getFirst())
                                               .order(java.nio.ByteOrder.LITTLE_ENDIAN)
                                               .getLong(32));
        assertNull(CaptureInboxTest.take(inbox));
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
        assertEquals(fresh.revision(), CaptureInboxTest.take(inbox).revision());
        assertNull(CaptureInboxTest.take(inbox));

        inbox.capture(inbox.begin(SECTION), empty());
        var newest = inbox.begin(SECTION);
        inbox.capture(newest, empty());
        assertEquals(newest.revision(), CaptureInboxTest.take(inbox).revision());
        assertNull(CaptureInboxTest.take(inbox));
    }

    @Test
    void sealedBatchExceedsOldQuotaAndBulkUnloadRetiresCompletedHistory() {
        var inbox = new CaptureInbox(true);
        for (int x = 0; x < 5000; x++)
            inbox.capture(inbox.begin(SectionPos.of(x, 0, 0)), empty());
        var first = inbox.seal();
        assertEquals(5000, first.batches().size());
        assertTrue(first.completedSequence() > 0);
        for (int x = 0; x < 5000; x++)
            inbox.dropChunk(x, 0);
        var removed = inbox.seal();
        assertEquals(1, removed.batches().size());
        var packet = java.nio.ByteBuffer.wrap(removed.batches().getFirst().packets().getFirst())
                             .order(java.nio.ByteOrder.LITTLE_ENDIAN);
        assertEquals(11, packet.getInt(8));
        assertEquals(5000, packet.getInt(24));
        assertNull(inbox.failure());
        assertEquals(0, inbox.sections().size());
        var pending = inbox.begin(SECTION);
        inbox.dropChunk(SECTION.x(), SECTION.z());
        assertEquals(pending.revision() - 1, inbox.seal().completedSequence());
        inbox.complete(pending);
        assertTrue(inbox.seal().completedSequence() > pending.revision());
        inbox.capture(pending, quad());
        assertTrue(inbox.seal().batches().isEmpty());
    }

    @Test
    void longStreamingHistoryIsBoundedByLiveSourcesAndUnfinishedTokens() throws Exception {
        var inbox = new CaptureInbox(true);
        for (int batch = 0; batch < 65; batch++) {
            for (int item = 0; item < 4097; item++) {
                int x = batch * 4097 + item;
                inbox.capture(inbox.begin(SectionPos.of(x, 0, 0)), empty());
                inbox.dropChunk(x, 0);
            }
            var sealed = inbox.seal();
            assertEquals(1, sealed.batches().size());
            assertTrue(sealed.completedSequence() > 0);
            assertTrue(inbox.sections().isEmpty());
            for (String name : new String[] {"revisions", "chunkSections", "chunkRevisions",
                                             "producers", "inFlight", "pending"}) {
                var field = CaptureInbox.class.getDeclaredField(name);
                field.setAccessible(true);
                assertTrue(((Map<?,?>)field.get(inbox)).isEmpty(),name+" must release completed history");
            }
        }
        assertNull(inbox.failure());
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
        assertNull(CaptureInboxTest.take(inbox));
        inbox.capture(inbox.begin(SECTION), empty());
        assertNotNull(CaptureInboxTest.take(inbox));
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
        assertNull(CaptureInboxTest.take(inbox));
        inbox.captureAtlas(upload);
        assertEquals(8, inbox.atlas().rgba().length);
        assertNull(inbox.failure());
    }
}
