package dev.prime.render.vulkan.terrain;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Arrays;
import java.util.Random;
import org.junit.jupiter.api.Test;

final class SurfaceRecordsTest {
    @Test void sharesAcrossClustersButWaitsForEveryGpuLeaseBeforeReuse() throws Exception {
        SurfaceRecords table = new SurfaceRecords();
        var first = table.lease();
        var second = table.lease();
        int[] record = {1, 2, 3, 4, 5, 6, 7, 8};
        int key = first.encode(record)[0];
        assertEquals(key, second.encode(record)[0]);
        assertEquals(key, first.encode(record)[0]);
        first.destroy();
        table.drain();
        assertEquals(1, table.size());
        var third = table.lease();
        int other = third.encode(new int[8])[0];
        assertNotEquals(key, other);
        Thread completion = new Thread(second::destroy);
        completion.start();
        completion.join();
        var replacement = table.lease();
        int[] changed = record.clone(); changed[7]++;
        assertEquals(key, replacement.encode(changed)[0]);
        assertEquals(other, third.encode(new int[8])[0]);
        first.destroy(); second.destroy(); third.destroy(); replacement.destroy();
        table.drain();
        assertEquals(0, table.size());
        assertEquals(2, table.extent());
        assertTrue(table.dirty().isEmpty());
    }

    @Test void comparesEveryBitAndResolvesHashCollisions() {
        SurfaceRecords table = new SurfaceRecords();
        var lease = table.lease();
        int[] zero = new int[8];
        int[] collision = new int[8]; collision[6] = 1; collision[7] = -31;
        assertEquals(SurfaceRecords.Record.read(zero, 0).hashCode(),
                SurfaceRecords.Record.read(collision, 0).hashCode());
        int key = lease.encode(zero)[0];
        assertNotEquals(key, lease.encode(collision)[0]);
        for (int word = 0; word < 8; word++) {
            for (int bit = 0; bit < 32; bit++) {
                int[] value = new int[8]; value[word] = 1 << bit;
                int distinct = lease.encode(value)[0];
                assertNotEquals(key, distinct);
                value[word] = 0; // The table must not borrow the caller's mutable array.
            }
        }
        assertEquals(258, table.size());
        assertEquals(key, lease.encode(zero)[0]);
    }

    @Test void uploadSnapshotPreservesWordsAndLaterDirtyEntries() {
        SurfaceRecords table = new SurfaceRecords();
        var lease = table.lease();
        int[] words = new int[800];
        Random random = new Random(413);
        for (int i = 0; i < words.length; i++) words[i] = random.nextInt();
        int[] keys = lease.encode(words);
        var upload = table.dirty();
        for (int i = 0; i < keys.length; i++) {
            int[] decoded = new int[8];
            upload.get(keys[i]).record.write(decoded, 0);
            assertArrayEquals(Arrays.copyOfRange(words, i * 8, i * 8 + 8), decoded);
        }
        lease.encode(new int[8]);
        table.uploaded(upload);
        assertEquals(1, table.dirty().size());
        table.uploaded(table.dirty());
        assertTrue(table.dirty().isEmpty());
        table.uploaded(upload);
        assertTrue(table.dirty().isEmpty());
        assertThrows(IllegalArgumentException.class, () -> lease.encode(new int[9]));
    }
}
