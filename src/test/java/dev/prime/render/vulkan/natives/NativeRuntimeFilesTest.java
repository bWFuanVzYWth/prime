package dev.prime.render.vulkan.natives;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class NativeRuntimeFilesTest {
    @Test
    void publishRepairsAStaleRuntimeAndRemainsIdempotent(@TempDir Path directory)
            throws Exception {
        Path target = directory.resolve("runtime.dll");
        byte[] expected = {1, 2, 3, 4};
        Files.write(target, new byte[] {9, 8});

        NativeRuntimeFiles.publish(target, () -> new ByteArrayInputStream(expected));
        NativeRuntimeFiles.publish(target, () -> new ByteArrayInputStream(expected));

        assertArrayEquals(expected, Files.readAllBytes(target));
    }

    @Test
    void failedCopyPreservesThePreviouslyPublishedFile(@TempDir Path directory)
            throws Exception {
        Path target = directory.resolve("runtime.dll");
        byte[] stale = {9, 8, 7};
        byte[] expected = {1, 2, 3, 4};
        Files.write(target, stale);
        AtomicInteger opens = new AtomicInteger();

        assertThrows(
                IllegalStateException.class,
                () -> NativeRuntimeFiles.publish(target, () ->
                        opens.incrementAndGet() == 2
                                ? new FailingInputStream(expected, 2)
                                : new ByteArrayInputStream(expected)));

        assertArrayEquals(stale, Files.readAllBytes(target));
        try (var files = Files.list(directory)) {
            assertEquals(List.of(target), files.toList());
        }
    }

    @Test
    void concurrentPublishersConvergeOnTheVerifiedPayload(@TempDir Path directory)
            throws Exception {
        Path target = directory.resolve("runtime.dll");
        byte[] expected = new byte[32 * 1024];
        for (int index = 0; index < expected.length; index++) {
            expected[index] = (byte) (index * 31);
        }
        ExecutorService executor = Executors.newFixedThreadPool(4);
        try {
            List<Callable<Void>> tasks = new ArrayList<>();
            for (int index = 0; index < 16; index++) {
                tasks.add(() -> {
                    NativeRuntimeFiles.publish(
                            target, () -> new ByteArrayInputStream(expected));
                    return null;
                });
            }
            for (Future<Void> result : executor.invokeAll(tasks)) {
                result.get();
            }
        } finally {
            executor.shutdownNow();
        }

        assertArrayEquals(expected, Files.readAllBytes(target));
    }

    @Test
    void bundleAddressPreservesFileAndPayloadBoundaries(@TempDir Path directory) {
        List<String> names = List.of("a.dll", "b.dll");
        Path first = address(
                directory,
                names,
                Map.of("a.dll", new byte[] {1}, "b.dll", new byte[] {2, 3}));
        Path second = address(
                directory,
                names,
                Map.of("a.dll", new byte[] {1, 2}, "b.dll", new byte[] {3}));
        Path repeated = address(
                directory,
                names,
                Map.of("a.dll", new byte[] {1}, "b.dll", new byte[] {2, 3}));

        assertNotEquals(first, second);
        assertEquals(first, repeated);
    }

    @Test
    void missingBundledLibraryNeverPublishesATarget(@TempDir Path directory) {
        NativeLibrary library = new NativeLibrary(
                "missing.dll",
                () -> directory,
                "/prime/natives/windows-x86_64/does-not-exist.dll",
                "missing test library");

        assertThrows(IllegalStateException.class, library::tryToExtract);
        assertFalse(Files.exists(directory.resolve("missing.dll")));
    }

    private static Path address(
            Path directory, List<String> names, Map<String, byte[]> payloads) {
        return NativeLibraries.contentAddressedDirectory(
                directory,
                "test",
                names,
                name -> new ByteArrayInputStream(payloads.get(name)));
    }

    private static final class FailingInputStream extends InputStream {
        private final byte[] bytes;
        private final int failAfter;
        private int position;

        private FailingInputStream(byte[] bytes, int failAfter) {
            this.bytes = bytes;
            this.failAfter = failAfter;
        }

        @Override
        public int read() throws IOException {
            if (this.position >= this.failAfter) {
                throw new IOException("injected copy failure");
            }
            return this.bytes[this.position++] & 0xff;
        }

        @Override
        public int read(byte[] destination, int offset, int length) throws IOException {
            if (this.position >= this.failAfter) {
                throw new IOException("injected copy failure");
            }
            int count = Math.min(length, this.failAfter - this.position);
            System.arraycopy(this.bytes, this.position, destination, offset, count);
            this.position += count;
            return count;
        }
    }
}
