package dev.primept;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PerformanceCaptureTest {
    @TempDir Path directory;
    private static final Pattern EVENT = Pattern.compile(
            "\\{\"i\":(\\d+),\"p\":(null|\\d+),\"f\":(\\d+),\"n\":(\\d+),\"s\":(-?\\d+),\"d\":(\\d+),\"t\":(\\d+),\"ok\":(true|false),\"a\":\\{([^}]*)}}");

    @AfterEach
    void disable() throws Exception {
        Diagnostics.setCaptureRequested(false);
        Diagnostics.lastExport().get(10, TimeUnit.SECONDS);
    }

    @Test
    void disabledScopesAndTransportAreTheSameNoop() throws Exception {
        Diagnostics.setOutputDirectory(directory);
        var first = Diagnostics.span("disabled");
        first.count("bytes", Long.MAX_VALUE);
        first.fail();
        first.close();
        assertSame(first, Diagnostics.span("another"));
        assertSame(first, Diagnostics.span(null, "diag.read"));
        first.succeed();
        assertSame(Diagnostics.captureContext(), Diagnostics.captureContext());
        assertSame(Diagnostics.captureContext().enter(), Diagnostics.captureContext().enter());
        assertEquals(0, Diagnostics.clock());
        assertFalse(Diagnostics.enabled());
        assertFalse(Diagnostics.timingEnabled());
        assertSame(first, Diagnostics.span("diag.flush"));
        try (var files = Files.list(directory)) {
            assertEquals(0, files.count());
        }
    }

    @Test
    void explicitTransportSessionKeepsItsFrameAndOpenStopTail() throws Exception {
        Diagnostics.beginFrame(31, true);
        assertFalse(Diagnostics.capturing());
        var capture = new PerformanceCapture(directory);
        var stop = Diagnostics.span(capture, "diag.stop");
        try (var read = Diagnostics.span(capture, "diag.read")) {
            try (var copy = Diagnostics.span(capture, "diag.copy")) {
                copy.fail();
                copy.count("bytes", 17);
                copy.succeed();
            }
        }
        var exported = capture.stop("disabled", "");
        assertFalse(exported.isDone(), "The explicit final scope must finish before finalization");
        assertSame(Diagnostics.span("disabled"), Diagnostics.span(capture, "late_read"));
        stop.close();
        String json = Files.readString(exported.get(10, TimeUnit.SECONDS));
        assertTrue(json.contains("\"partial\":false"));
        assertTrue(json.contains("\"reject\":0"));
        var event = EVENT.matcher(json);
        assertTrue(event.find());
        assertEquals("31", event.group(3));
        assertEquals("true", event.group(8));
        assertEquals("\"bytes\":17", event.group(9));
        String readId = event.group(2);
        assertTrue(event.find());
        assertEquals(readId, event.group(1));
        String stopId = event.group(2);
        assertTrue(event.find());
        assertEquals(stopId, event.group(1));
        assertEquals("null", event.group(2));
        assertFalse(event.find());
    }

    @Test
    void stoppedCaptureKeepsActiveTailAndIntegerTimes() throws Exception {
        Diagnostics.setOutputDirectory(directory);
        Diagnostics.setCaptureRequested(true);
        Diagnostics.beginFrame(23, true);
        var outer = Diagnostics.span("outer");
        long started = System.nanoTime();
        try (var inner = Diagnostics.measuredSpan("integer", started, 9_007_199_254_740_993L)) {
            inner.count("bytes", Long.MAX_VALUE);
        }
        Diagnostics.setCaptureRequested(false);
        assertFalse(Diagnostics.lastExport().isDone(),
                    "An open interval must finish before finalization");
        outer.close();
        String json = Files.readString(Diagnostics.lastExport().get(10, TimeUnit.SECONDS));
        assertTrue(json.contains("\"partial\":false"));
        assertTrue(json.contains("\"reject\":0"));
        assertTrue(json.contains("\"d\":9007199254740993"));
        assertTrue(json.contains("\"bytes\":9223372036854775807"));
        var event = EVENT.matcher(json);
        assertTrue(event.find());
        String outerId = event.group(2);
        assertNotEquals("null", outerId);
        assertEquals("23", event.group(3));
        assertEquals(Long.toString(started), event.group(5));
        assertTrue(event.find());
        assertEquals(outerId, event.group(1));
        assertEquals("null", event.group(2));
        assertFalse(event.find());
        assertFalse(json.strip().contains("\n"), "Only a final newline is allowed in compact JSON");
    }

    @Test
    void dispatchedContextKeepsItsOriginalFrameAndParentAcrossThreads() throws Exception {
        Diagnostics.setOutputDirectory(directory);
        Diagnostics.setCaptureRequested(true);
        Diagnostics.beginFrame(7, true);
        Diagnostics.Context dispatched;
        try (var source = Diagnostics.span("dispatch")) {
            dispatched = Diagnostics.captureContext();
        }
        Diagnostics.beginFrame(8, true);
        Thread worker = new Thread(() -> {
            try (var entered = dispatched.enter(); var event = Diagnostics.span("worker")) {
                event.count("jobs", 1);
            }
        });
        worker.start();
        worker.join();
        try (var current = Diagnostics.span("current")) {
        }
        Diagnostics.setCaptureRequested(false);
        String json = Files.readString(Diagnostics.lastExport().get(10, TimeUnit.SECONDS));
        var event = EVENT.matcher(json);
        assertTrue(event.find());
        String dispatchId = event.group(1);
        assertEquals("7", event.group(3));
        assertTrue(event.find());
        assertEquals(dispatchId, event.group(2));
        assertEquals("7", event.group(3));
        assertTrue(event.find());
        assertEquals("null", event.group(2));
        assertEquals("8", event.group(3));
    }

    @Test
    void partialBoundaryStillKeepsTheFailedActiveTail() throws Exception {
        Diagnostics.setOutputDirectory(directory);
        Diagnostics.setCaptureRequested(true);
        Diagnostics.beginFrame(77, true);
        var tail = Diagnostics.span("failed_tail");
        Diagnostics.boundary("renderer_failure");
        tail.fail();
        tail.close();
        String json = Files.readString(Diagnostics.lastExport().get(10, TimeUnit.SECONDS));
        assertTrue(json.contains("\"partial\":true"));
        assertTrue(json.contains("\"reject\":0"));
        assertTrue(json.contains("failed_tail"));
        assertTrue(json.contains("\"ok\":false"));
    }

    @Test
    void multithreadEventsKeepAllParentsAndThreadNames() throws Exception {
        Diagnostics.setOutputDirectory(directory);
        Diagnostics.setCaptureRequested(true);
        Diagnostics.beginFrame(45, true);
        var failure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        Thread[] workers = new Thread[4];
        for (int i = 0; i < workers.length; ++i) {
            final int index = i;
            workers[i] = new Thread(() -> {
                try (var root = Diagnostics.span("worker_" + index)) {
                    for (int j = 0; j < 100; ++j)
                        try (var child = Diagnostics.span("child")) {
                            child.count("seq", j);
                        }
                } catch (Throwable error) {
                    failure.set(error);
                }
            }, "quoted\" thread\n" + i);
            workers[i].start();
        }
        for (var worker : workers)
            worker.join();
        assertNull(failure.get());
        Diagnostics.boundary("world_exit");
        String json = Files.readString(Diagnostics.lastExport().get(10, TimeUnit.SECONDS));
        var event = EVENT.matcher(json);
        var roots = new HashSet<String>();
        var parents = new java.util.ArrayList<String>();
        var threads = new HashSet<String>();
        int count = 0;
        while (event.find()) {
            ++count;
            assertEquals("45", event.group(3));
            assertTrue(Long.parseLong(event.group(6)) >= 0);
            threads.add(event.group(7));
            if (event.group(2).equals("null"))
                roots.add(event.group(1));
            else
                parents.add(event.group(2));
        }
        assertEquals(404, count);
        assertEquals(4, roots.size());
        assertEquals(4, threads.size());
        assertTrue(roots.containsAll(parents));
        var dictionary = Pattern.compile("\\{\"i\":(\\d+),\"n\":\"worker_\\d\"}").matcher(json);
        var nameIds = new HashSet<String>();
        while (dictionary.find())
            assertTrue(nameIds.add(dictionary.group(1)));
        assertEquals(4, nameIds.size());
        assertTrue(json.contains("quoted\\\" thread\\n"));
    }

    @Test
    void boundedQueuePreservesNativeIncrementalChunksAndAllEvents() throws Exception {
        var capture = new PerformanceCapture(directory, 1);
        capture.nativeChunk("");
        capture.nativeChunk("{\"dict\":{\"nb\":0,\"n\":[\"reset\"]},\"cpu\":[{\"i\":1}]}");
        capture.nativeChunk("{\"dict\":{\"nb\":1,\"n\":[]},\"cpu\":[{\"i\":2}]}");
        for (int i = 0; i < 2000; ++i)
            capture.javaEvent("{\"seq\":" + i + "}");
        String json = Files.readString(capture.stop("disabled", "").get(10, TimeUnit.SECONDS));
        assertTrue(json.contains("\"partial\":false"));
        assertTrue(json.contains("\"reject\":0"));
        assertTrue(json.contains("\"nb\":0"));
        assertTrue(json.contains("\"nb\":1"));
        var sequence = Pattern.compile("\\{\"seq\":(\\d+)}").matcher(json);
        for (int i = 0; i < 2000; ++i) {
            assertTrue(sequence.find());
            assertEquals(Integer.toString(i), sequence.group(1));
        }
        assertFalse(sequence.find());
        assertFalse(json.contains(".ndjson"));
        try (var files = Files.list(directory)) {
            assertEquals(1, files.count());
        }
    }

    @Test
    void exactJsonEscapingAndWriterFailureDoesNotDeadlockProducers() throws Exception {
        assertEquals("\"quote\\\" slash\\\\ \\n\\r\\t\\u0001\\ud83d\\ude00\"",
                     PerformanceCapture.quote("quote\" slash\\ \n\r\t\u0001😀"));
        Path file = directory.resolve("file");
        Files.writeString(file, "cannot be a directory");
        var capture = new PerformanceCapture(file, 1);
        assertTimeoutPreemptively(java.time.Duration.ofSeconds(5), () -> {
            for (int i = 0; i < 100; ++i)
                capture.javaEvent("{}");
            assertThrows(java.util.concurrent.ExecutionException.class,
                         () -> capture.stop("failure", "").get(4, TimeUnit.SECONDS));
        });
        assertTrue(capture.failed());
    }
}
