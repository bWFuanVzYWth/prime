package dev.primept;

import java.nio.file.Path;

public final class CpuDiagnosticsSmoke {
    public static void main(String[] args) throws Exception {
        try (var bridge = new NativeBridge(Path.of(args[0]))) {
            var initial = bridge.cpuDiagnostics();
            if (!initial.contains("available=false"))
                throw new AssertionError(initial);
            bridge.reset(1);
            if (!bridge.cpuDiagnostics().contains("available=false"))
                throw new AssertionError("Source submission cannot fabricate renderer timings");
            var settings =
                    dev.primept.settings.RenderSettings.defaults()
                            .withLightSampling(
                                    dev.primept.settings.RenderSettings.LightSampling.TREE_SPHERE)
                            .withIgnoreGlobalHistoryResets(true);
            bridge.configure(settings, false, dev.primept.settings.RenderSettings.View.OUTPUT);
            bridge.diagnosticsConfigure(3);
            bridge.diagnosticsFrame(91);
            bridge.configure(settings.withIgnoreGlobalHistoryResets(false), false,
                             dev.primept.settings.RenderSettings.View.OUTPUT);
            long before = bridge.diagnosticsClock();
            bridge.reset(2);
            String captured = bridge.diagnosticsRead();
            if (before < 0 || bridge.diagnosticsClock() < before || !captured.contains("\"cpu\"") ||
                !captured.contains("\"f\":91") || !captured.contains("reset"))
                throw new AssertionError("Real native reset interval missing from capture: " +
                                         captured);
            verifyNativeAttribute(captured, "ls", 2);
            verifyNativeAttribute(captured, "ignore_global_resets", 1);
            verifyNativeAttribute(captured, "ignore_global_resets", 0);
            bridge.diagnosticsConfigure(0);
            long stopped = bridge.diagnosticsClock();
            bridge.diagnosticsRead();
            if (stopped < before)
                throw new AssertionError("Stopped capture must retain its monotonic origin");
            if (args.length > 1) {
                Diagnostics.setOutputDirectory(Path.of(args[1]));
                Diagnostics.setCaptureRequested(true);
                Diagnostics.beginFrame(92, true);
                var tail = Diagnostics.span("ffm_tail");
                bridge.reset(3);
                Diagnostics.endFrame();
                Diagnostics.setCaptureRequested(false);
                tail.close();
                Path exported =
                        Diagnostics.lastExport().get(10, java.util.concurrent.TimeUnit.SECONDS);
                String report = java.nio.file.Files.readString(exported);
                if (!report.contains("\"sync\":[{"))
                    throw new AssertionError("Java/native capture must retain clock brackets");
                verifyTransport(report, 92);
                // Java transport events must not repopulate the stopped native recorder.
                if (!bridge.diagnosticsRead().isEmpty() || !bridge.diagnosticsRead().isEmpty())
                    throw new AssertionError("Diagnostic reads recursively generated native data");
                // Inject a real ABI lifecycle error by intentionally leaving the previous tail undrained.
                bridge.diagnosticsConfigure(3);
                bridge.diagnosticsFrame(99);
                bridge.reset(4);
                bridge.diagnosticsConfigure(0);
                Diagnostics.setCaptureRequested(true);
                Diagnostics.beginFrame(100, true);
                if (Diagnostics.captureRequested() || Diagnostics.capturing() ||
                    Diagnostics.enabled())
                    throw new AssertionError(
                            "Diagnostic control error must stop recording and clear requests");
                exported = Diagnostics.lastExport().get(10, java.util.concurrent.TimeUnit.SECONDS);
                if (!java.nio.file.Files.readString(exported).contains("\"partial\":true"))
                    throw new AssertionError(
                            "Diagnostic failure must export an explicit partial document");
                bridge.reset(5); // The healthy native owner remains usable after diagnostics fail.
            }
            var wrongThread = new Thread(() -> {
                try {
                    bridge.diagnosticsRead();
                    throw new AssertionError("Wrong thread accepted");
                } catch (IllegalStateException expected) {}
            });
            var failure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
            wrongThread.setUncaughtExceptionHandler((thread, error) -> failure.set(error));
            wrongThread.start();
            wrongThread.join();
            if (failure.get() != null)
                throw new AssertionError(failure.get());
        }
        if (args.length > 1) {
            // The Java capture and frame can start before the first native owner exists.
            Diagnostics.setCaptureRequested(true);
            Diagnostics.beginFrame(93, true);
            try (var bridge = new NativeBridge(Path.of(args[0]))) {
                bridge.reset(1);
                Diagnostics.endFrame();
                Diagnostics.setCaptureRequested(false);
            }
            Path exported = Diagnostics.lastExport().get(10, java.util.concurrent.TimeUnit.SECONDS);
            String json = java.nio.file.Files.readString(exported);
            if (!json.contains("\"f\":93"))
                throw new AssertionError("First native owner must inherit the current Java frame");
        }
        System.out.println(
                "PRIME_CPU_DIAGNOSTICS_FFM_OK: ABI13 108B settings, bounds-sphere ID2, global-reset bool true/false consumed by native and captured, real reset and transport spans/frame, final tail, UTF-8, retained clock and owner thread; no GPU");
    }

    private record Event(long id, long parent, long frame, int name, long start, long duration,
                         boolean ok, String counts) {}
    private static void verifyNativeAttribute(String captured, String key, int expected) {
        // Native attributes use dictionary IDs rather than repeating their field names.
        var keys = java.util.regex.Pattern.compile("\"kb\":(\\d+),\"k\":\\[([^]]*)]")
                           .matcher(captured);
        if (!keys.find())
            throw new AssertionError("Missing native attribute dictionary");
        int base = Integer.parseInt(keys.group(1));
        String[] entries = keys.group(2).split(",");
        for (int index = 0; index < entries.length; index++) {
            if (entries[index].equals("\"" + key + "\"")) {
                if (!captured.contains("[" + (base + index) + "," + expected + "]"))
                    throw new AssertionError("Incorrect native " + key + " metadata: " + captured);
                return;
            }
        }
        throw new AssertionError("Missing native " + key + " metadata");
    }
    private static void verifyTransport(String report, long frame) {
        var names = new java.util.HashMap<Integer, String>();
        int namesStart = report.indexOf("\"j\":{\"names\":[");
        int namesEnd = report.indexOf("],\"threads\":[", namesStart);
        if (namesStart < 0 || namesEnd < 0)
            throw new AssertionError("Missing Java dictionary");
        var dictionary = java.util.regex.Pattern.compile("\\{\"i\":(\\d+),\"n\":\"([a-z0-9_.]+)\"}")
                                 .matcher(report.substring(namesStart, namesEnd));
        while (dictionary.find())
            names.put(Integer.parseInt(dictionary.group(1)), dictionary.group(2));
        var records =
                java.util.regex.Pattern
                        .compile(
                                "\\{\"i\":(\\d+),\"p\":(null|\\d+),\"f\":(\\d+),\"n\":(\\d+),\"s\":(-?\\d+),\"d\":(\\d+),\"t\":\\d+,\"ok\":(true|false),\"a\":\\{([^}]*)}}")
                        .matcher(report);
        var events = new java.util.HashMap<Long, Event>();
        while (records.find()) {
            var event = new Event(
                    Long.parseLong(records.group(1)),
                    records.group(2).equals("null") ? -1 : Long.parseLong(records.group(2)),
                    Long.parseLong(records.group(3)), Integer.parseInt(records.group(4)),
                    Long.parseLong(records.group(5)), Long.parseLong(records.group(6)),
                    Boolean.parseBoolean(records.group(7)), records.group(8));
            events.put(event.id, event);
        }
        for (String stage :
             java.util.List.of("diag.flush", "diag.read", "diag.drain", "diag.alloc", "diag.copy",
                               "diag.array", "diag.utf8", "diag.queue", "diag.stop")) {
            boolean found = false;
            for (var event : events.values()) {
                if (!stage.equals(names.get(event.name)))
                    continue;
                found = true;
                if (event.frame != frame || !event.ok || event.duration < 0)
                    throw new AssertionError("Invalid transport interval: " + stage + " " + event);
                if (event.parent >= 0) {
                    var parent = events.get(event.parent);
                    if (parent == null || parent.frame != frame || event.start < parent.start ||
                        event.start + event.duration > parent.start + parent.duration)
                        throw new AssertionError("Invalid transport parent: " + stage + " " +
                                                 event);
                }
                if (java.util.List.of("diag.drain", "diag.alloc", "diag.copy", "diag.array")
                            .contains(stage) &&
                    !event.counts.contains("\"bytes\":"))
                    throw new AssertionError("Missing measured transport bytes: " + stage);
            }
            if (!found)
                throw new AssertionError("Missing real transport stage: " + stage);
        }
    }
}
