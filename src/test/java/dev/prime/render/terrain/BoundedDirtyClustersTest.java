package dev.prime.render.runtime.terrain;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import net.minecraft.core.SectionPos;
import org.junit.jupiter.api.Test;

final class BoundedDirtyClustersTest {
    @Test
    void duplicateKeysAreCoalesced() {
        BoundedDirtyClusters dirty = new BoundedDirtyClusters(2);
        dirty.addCluster(7L);
        dirty.addCluster(7L);
        BoundedDirtyClusters.Batch batch = dirty.drain();
        assertFalse(batch.fullInvalidation());
        assertArrayEquals(new long[] {7L}, batch.keys());
    }

    @Test
    void overflowCollapsesToOneFullInvalidation() {
        BoundedDirtyClusters dirty = new BoundedDirtyClusters(2);
        dirty.addCluster(1L);
        dirty.addCluster(2L);
        dirty.addCluster(3L);
        dirty.addCluster(4L);
        BoundedDirtyClusters.Batch batch = dirty.drain();
        assertTrue(batch.fullInvalidation());
        assertArrayEquals(new long[0], batch.keys());
        assertFalse(dirty.drain().fullInvalidation());
    }

    @Test
    void explicitFullInvalidationSupersedesKeys() {
        BoundedDirtyClusters dirty = new BoundedDirtyClusters(4);
        dirty.addCluster(9L);
        dirty.invalidateAll();
        BoundedDirtyClusters.Batch batch = dirty.drain();
        Arrays.sort(batch.keys());
        assertTrue(batch.fullInvalidation());
        assertArrayEquals(new long[0], batch.keys());
    }

    @Test
    void blockInvalidationExpandsAcrossAllSectionFaces() {
        BoundedDirtyClusters dirty = new BoundedDirtyClusters(16);
        dirty.addExpandedBlockRange(15, 15, 15, 15, 15, 15);
        long[] actual = dirty.drain().keys();
        Arrays.sort(actual);
        long[] expected = new long[] {
            SectionPos.asLong(0, 0, 0)
        };
        Arrays.sort(expected);
        assertArrayEquals(expected, actual);
    }

    @Test
    void reversedAndNegativeBlockRangesUseFloorDivision() {
        BoundedDirtyClusters dirty = new BoundedDirtyClusters(16);
        dirty.addExpandedBlockRange(0, 0, 0, -1, -1, -1);
        long[] actual = dirty.drain().keys();
        Arrays.sort(actual);
        long[] expected = new long[] {
            SectionPos.asLong(-4, -4, -4),
            SectionPos.asLong(0, -4, -4),
            SectionPos.asLong(-4, 0, -4),
            SectionPos.asLong(0, 0, -4),
            SectionPos.asLong(-4, -4, 0),
            SectionPos.asLong(0, -4, 0),
            SectionPos.asLong(-4, 0, 0),
            SectionPos.asLong(0, 0, 0)
        };
        Arrays.sort(expected);
        assertArrayEquals(expected, actual);
    }

    @Test
    void oversizedBlockRangeCollapsesWithoutEnumeration() {
        BoundedDirtyClusters dirty = new BoundedDirtyClusters(2);
        dirty.addExpandedBlockRange(-1000, 0, 0, 1000, 0, 0);
        BoundedDirtyClusters.Batch batch = dirty.drain();
        assertTrue(batch.fullInvalidation());
        assertArrayEquals(new long[0], batch.keys());
    }

    @Test
    void concurrentProducersPreserveEveryAcceptedKeyBelowCapacity() throws Exception {
        int producerCount = 8;
        int keysPerProducer = 128;
        BoundedDirtyClusters dirty =
                new BoundedDirtyClusters(producerCount * keysPerProducer);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> producers = new ArrayList<>();
        try (ExecutorService executor = Executors.newFixedThreadPool(producerCount)) {
            for (int producer = 0; producer < producerCount; producer++) {
                int owner = producer;
                producers.add(executor.submit(() -> {
                    start.await();
                    for (int key = 0; key < keysPerProducer; key++) {
                        dirty.addCluster(SectionPos.asLong(
                                owner * keysPerProducer + key, 0, 0));
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> producer : producers) {
                producer.get();
            }
        }

        BoundedDirtyClusters.Batch batch = dirty.drain();
        assertFalse(batch.fullInvalidation());
        long[] actual = batch.keys();
        Arrays.sort(actual);
        long[] expected = new long[producerCount * keysPerProducer];
        for (int index = 0; index < expected.length; index++) {
            expected[index] = SectionPos.asLong(index, 0, 0);
        }
        Arrays.sort(expected);
        assertArrayEquals(expected, actual);
    }
}
