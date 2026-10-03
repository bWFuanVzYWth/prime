package dev.primept;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicInteger;

/** Bounded producer queue, background spool, and streaming final JSON. No render-thread file IO. */
final class PerformanceCapture {
    private record Row(int stream, String json) {}
    private final ArrayBlockingQueue<Row> queue;
    private final AtomicLong rejected = new AtomicLong();
    private final AtomicLong blockedNs = new AtomicLong(), blockedCount = new AtomicLong();
    private final AtomicInteger nameIds = new AtomicInteger(), activeScopes = new AtomicInteger();
    private final ConcurrentHashMap<String, Integer> names = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, Boolean> threads = new ConcurrentHashMap<>();
    private final String id = UUID.randomUUID().toString();
    private final String started = Instant.now().toString();
    private final Path directory;
    private volatile boolean stopping, overflow, broken;
    private volatile String reason = "", error = "";
    private final CompletableFuture<Path> completion = new CompletableFuture<>();

    PerformanceCapture(Path directory) {
        this(directory, 8192);
    }
    PerformanceCapture(Path directory, int capacity) {
        this.directory = directory.toAbsolutePath();
        queue = new ArrayBlockingQueue<>(capacity);
        var worker = new Thread(this::write, "Prime performance export " + id);
        // An orderly game exit must let already queued records finish exporting.
        worker.setDaemon(false);
        worker.start();
    }
    void javaEvent(String json) {
        offer(0, json, true);
    }
    void nativeChunk(String json) {
        if (!json.isEmpty())
            offer(1, json);
    }
    void alignment(String json) {
        offer(2, json);
    }
    int name(String value) {
        return names.computeIfAbsent(value, key -> {
            int next = nameIds.getAndIncrement();
            offer(3, "{\"i\":" + next + ",\"n\":" + quote(key) + "}", true);
            return next;
        });
    }
    void thread(long id, String name) {
        threads.computeIfAbsent(id, key -> {
            offer(4, "{\"i\":" + key + ",\"n\":" + quote(name) + "}", true);
            return true;
        });
    }
    boolean failed() {
        return overflow || !error.isEmpty();
    }
    synchronized boolean beginScope() {
        if (stopping || failed())
            return false;
        activeScopes.incrementAndGet();
        return true;
    }
    void endScope() {
        activeScopes.decrementAndGet();
    }
    private void offer(int stream, String json) {
        offer(stream, json, false);
    }
    private void offer(int stream, String json, boolean closingScope) {
        if ((stopping && !closingScope) || broken) {
            rejected.incrementAndGet();
            overflow = true;
            return;
        }
        Row row = new Row(stream, json);
        if (queue.offer(row))
            return;
        long started = System.nanoTime();
        blockedCount.incrementAndGet();
        try {
            while (!queue.offer(row, 25, TimeUnit.MILLISECONDS)) {
                if ((stopping && !closingScope) || broken) {
                    rejected.incrementAndGet();
                    overflow = true;
                    return;
                }
            }
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            rejected.incrementAndGet();
            error = "Capture producer interrupted while waiting for export queue";
            broken = true;
        } finally {
            blockedNs.addAndGet(System.nanoTime() - started);
        }
    }
    synchronized CompletableFuture<Path> stop(String why, String failure) {
        reason = why;
        if (failure != null && !failure.isEmpty())
            error = failure;
        stopping = true;
        return completion;
    }
    private void write() {
        Path[] spools = new Path[5];
        try {
            Files.createDirectories(directory);
            for (int i = 0; i < spools.length; i++)
                spools[i] = directory.resolve(id + "." + i + ".ndjson");
            try (var java = Files.newBufferedWriter(spools[0], StandardCharsets.UTF_8);
                 var nativeWriter = Files.newBufferedWriter(spools[1], StandardCharsets.UTF_8);
                 var clock = Files.newBufferedWriter(spools[2], StandardCharsets.UTF_8);
                 var nameWriter = Files.newBufferedWriter(spools[3], StandardCharsets.UTF_8);
                 var threadWriter = Files.newBufferedWriter(spools[4], StandardCharsets.UTF_8)) {
                BufferedWriter[] writers = {java, nativeWriter, clock, nameWriter, threadWriter};
                while (!stopping || activeScopes.get() != 0 || !queue.isEmpty()) {
                    Row row = queue.poll(100, TimeUnit.MILLISECONDS);
                    if (row != null) {
                        writers[row.stream].write(row.json);
                        writers[row.stream].newLine();
                    }
                }
            }
            Path result = directory.resolve("performance-" + id + ".json");
            Path temporary = directory.resolve(id + ".json.tmp");
            try (var output = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
                output.write("{\"v\":1,\"id\":" + quote(id) + ",\"utc\":" + quote(started) +
                             ",\"why\":" + quote(reason) + ",\"partial\":" + failed() +
                             ",\"reject\":" + rejected.get() + ",\"wait_ns\":" + blockedNs.get() +
                             ",\"waits\":" + blockedCount.get() + ",\"err\":[");
                if (!error.isEmpty())
                    output.write(quote(error));
                if (overflow) {
                    if (!error.isEmpty())
                        output.write(',');
                    output.write(quote(
                            "Incomplete capture: late scope or failed writer; inspect reject"));
                }
                output.write(
                        "],\"clk\":{\"j\":\"System.nanoTime ns\",\"n\":\"native monotonic origin ns\",\"g\":\"native raw ticks with queue bits/period\"},\"sync\":[");
                copyArray(spools[2], output);
                output.write("],\"j\":{\"names\":[");
                copyArray(spools[3], output);
                output.write("],\"threads\":[");
                copyArray(spools[4], output);
                output.write("],\"ev\":[");
                copyArray(spools[0], output);
                output.write("]},\"n\":{\"chunks\":[");
                copyArray(spools[1], output);
                output.write("]}}\n");
            }
            Files.move(temporary, result, StandardCopyOption.REPLACE_EXISTING);
            for (var spool : spools)
                Files.delete(spool);
            System.getLogger("PrimePT").log(System.Logger.Level.INFO,
                                            "Performance capture exported" +
                                                    (failed() ? " (partial): " : ": ") + result);
            completion.complete(result);
        } catch (IOException | InterruptedException failure) {
            error = failure.toString();
            broken = true;
            stopping = true;
            // Keep recoverable NDJSON and an explicit failure record if the destination still works.
            try {
                Files.createDirectories(directory);
                Files.writeString(directory.resolve("performance-" + id + ".partial.json"),
                                  "{\"v\":1,\"id\":" + quote(id) + ",\"partial\":true,\"err\":[" +
                                          quote(error) + "],\"reject\":" + rejected.get() +
                                          ",\"pending\":" + queue.size() +
                                          ",\"recovery\":\"NDJSON spool files retained\"}\n");
            } catch (IOException secondary) {
                failure.addSuppressed(secondary);
            }
            System.getLogger("PrimePT").log(
                    System.Logger.Level.ERROR,
                    "Performance export failed; spools retained in " + directory, failure);
            completion.completeExceptionally(failure);
        }
    }
    private static void copyArray(Path path, BufferedWriter writer) throws IOException {
        try (BufferedReader source = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            boolean first = true;
            for (String line; (line = source.readLine()) != null;) {
                if (!first)
                    writer.write(',');
                writer.write(line);
                first = false;
            }
        }
    }
    static String quote(String value) {
        var result = new StringBuilder(value.length() + 2).append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
            case '"' -> result.append("\\\"");
            case '\\' -> result.append("\\\\");
            case '\n' -> result.append("\\n");
            case '\r' -> result.append("\\r");
            case '\t' -> result.append("\\t");
            default -> {
                if (c < 32 || Character.isSurrogate(c)) {
                    result.append("\\u");
                    for (int shift = 12; shift >= 0; shift -= 4)
                        result.append(Character.forDigit((c >>> shift) & 15, 16));
                } else
                    result.append(c);
            }
            }
        }
        return result.append('"').toString();
    }
}
