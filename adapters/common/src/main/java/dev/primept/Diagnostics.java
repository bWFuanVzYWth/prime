package dev.primept;

import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;

/** Session controls are independent of shader RenderSettings. Owner-thread native access only. */
public final class Diagnostics {
    private static volatile boolean captureRequested;
    private static volatile PerformanceCapture capture;
    private static volatile long frameId;
    private static final AtomicLong ids = new AtomicLong();
    private static final ThreadLocal<Scope> parent = new ThreadLocal<>();
    private static final ThreadLocal<Context> context = new ThreadLocal<>();
    private static final Scope NOOP = new Scope();
    private static final Context EMPTY_CONTEXT = new Context(null, 0, -1);
    private static final ContextGuard NOOP_CONTEXT = new ContextGuard();
    private static NativeBridge bridge;
    private static int appliedFlags = -1;
    private static boolean controlFailed;
    private static Path directory = Path.of("artifacts", "performance");
    private static CompletableFuture<Path> lastExport = CompletableFuture.completedFuture(null);
    private static long nextAlignment;
    private static final boolean legacyTiming = Boolean.getBoolean("primept.profile") ||
                                                System.getProperty("primept.profile.csv") != null;
    private Diagnostics() {}

    public static boolean enabled() {
        return captureRequested || capture != null;
    }
    public static boolean timingEnabled() {
        return enabled() || legacyTiming;
    }
    public static long clock() {
        return timingEnabled() ? System.nanoTime() : 0;
    }
    public static boolean captureRequested() {
        return captureRequested;
    }
    public static boolean capturing() {
        return capture != null;
    }
    public static void setCaptureRequested(boolean value) {
        captureRequested = value;
        if (value)
            controlFailed = false;
        if (!value)
            stop("disabled", "");
    }
    public static void setOutputDirectory(Path path) {
        directory = path;
    }
    public static CompletableFuture<Path> lastExport() {
        return lastExport;
    }
    static void attached(NativeBridge value) {
        bridge = value;
        appliedFlags = -1;
        controlFailed = false;
        try {
            if (enabled())
                value.diagnosticsFrame(frameId);
            applyFlags();
            if (capture != null)
                synchronizeClock("native_attached");
        } catch (RuntimeException failure) {
            diagnosticFailure(failure);
        }
    }
    static void detaching(NativeBridge value) {
        if (bridge != value)
            return;
        stop("native_owner_closed", "Capture ended while its native owner closed");
        captureRequested = false;
        bridge = null;
        appliedFlags = -1;
    }
    public static void beginFrame(long id, boolean worldPresent) {
        try {
            beginFrameInternal(id, worldPresent);
        } catch (RuntimeException failure) {
            diagnosticFailure(failure);
        }
    }
    private static void beginFrameInternal(long id, boolean worldPresent) {
        frameId = id;
        if (bridge != null && enabled())
            bridge.diagnosticsFrame(id);
        if (!worldPresent) {
            stop("world_exit", "");
            captureRequested = false;
        } else if (captureRequested && capture == null) {
            capture = new PerformanceCapture(directory);
            System.getLogger("PrimePT").log(System.Logger.Level.INFO,
                                            "Performance capture started");
            applyFlags();
            synchronizeClock("start");
            nextAlignment = System.nanoTime() + 1_000_000_000L;
        } else if (!captureRequested && capture != null)
            stop("disabled", "");
        if (capture != null && capture.failed()) {
            captureRequested = false;
            stop("export_failure", "");
        }
        applyFlags();
        if (capture != null && System.nanoTime() >= nextAlignment) {
            synchronizeClock("periodic");
            nextAlignment = System.nanoTime() + 1_000_000_000L;
        }
    }
    public static void endFrame() {
        var session = capture;
        var owner = bridge;
        if (session == null || owner == null)
            return;
        try (var flush = span(session, "diag.flush")) {
            try {
                queueNative(session, owner.diagnosticsRead(session));
            } catch (RuntimeException failure) {
                flush.fail();
                diagnosticFailure(failure);
            }
        }
    }
    private static void queueNative(PerformanceCapture session, String chunk) {
        if (chunk.isEmpty())
            return;
        try (var queued = span(session, "diag.queue")) {
            queued.count("chars", chunk.length());
            session.nativeChunk(chunk);
            if (session.failed())
                queued.fail();
        }
    }
    public static void boundary(String reason) {
        stop(reason, reason.equals("renderer_failure") || reason.equals("world_reset")
                             ? "Capture ended at " + reason
                             : "");
        captureRequested = false;
        try {
            applyFlags();
        } catch (RuntimeException failure) {
            diagnosticFailure(failure);
        }
    }
    private static void applyFlags() {
        int flags = capture != null ? 3 : 0;
        if (bridge != null && !controlFailed && flags != appliedFlags) {
            bridge.diagnosticsConfigure(flags);
            appliedFlags = flags;
        }
    }
    private static void synchronizeClock(String phase) {
        synchronizeClock(capture, phase);
    }
    private static void synchronizeClock(PerformanceCapture session, String phase) {
        if (session == null || bridge == null)
            return;
        long before = System.nanoTime();
        long nativeNs = bridge.diagnosticsClock();
        long after = System.nanoTime();
        session.alignment("{\"why\":" + PerformanceCapture.quote(phase) + ",\"jb\":" + before +
                          ",\"ja\":" + after + ",\"n\":" + nativeNs + "}");
    }
    private static void stop(String reason, String failure) {
        var previous = capture;
        if (previous == null)
            return;
        String error = failure;
        capture = null;
        boolean stopped = true;
        if (bridge != null) {
            // Bind the final measurements to the old session after normal scopes are disabled.
            try (var stopping = span(previous, "diag.stop")) {
                try {
                    bridge.diagnosticsConfigure(0);
                    appliedFlags = 0;
                } catch (RuntimeException nativeFailure) {
                    stopped = false;
                    error += " " + nativeFailure;
                }
                try {
                    synchronizeClock(previous, "stop");
                } catch (RuntimeException nativeFailure) {
                    error += " " + nativeFailure;
                }
                try {
                    // Java read events cannot create native chunks, so the final empty read terminates.
                    // A cached mid-frame chunk can precede the final stop tail. Consume both.
                    for (String chunk; !(chunk = bridge.diagnosticsRead(previous)).isEmpty();) {
                        queueNative(previous, chunk);
                        // An active recorder can return empty-event chunks forever after failed stop.
                        if (!stopped)
                            break;
                    }
                } catch (RuntimeException nativeFailure) {
                    error += " " + nativeFailure;
                }
                if (!error.isEmpty())
                    stopping.fail();
            }
        }
        lastExport = previous.stop(reason, error);
    }
    private static void diagnosticFailure(RuntimeException failure) {
        captureRequested = false;
        controlFailed = true;
        if (capture != null)
            stop("diagnostics_failed", failure.toString());
        else if (bridge != null) {
            try {
                bridge.diagnosticsConfigure(0);
                appliedFlags = 0;
            } catch (RuntimeException secondary) {
                failure.addSuppressed(secondary);
            }
        }
        System.getLogger("PrimePT").log(System.Logger.Level.ERROR,
                                        "Performance diagnostics disabled after failure", failure);
    }
    public static Scope span(String name) {
        return measuredSpan(name, 0, -1);
    }
    static PerformanceCapture captureSession() {
        return capture;
    }
    /** Explicit session binding is limited to diagnostic transport and its final stop tail. */
    static Scope span(PerformanceCapture session, String name) {
        return measuredSpan(session, name, 0, -1);
    }
    /** A real measured interval from an existing coarse clock, with no invented zero timestamps. */
    public static Scope measuredSpan(String name, long started, long duration) {
        return measuredSpan(capture, name, started, duration);
    }
    private static Scope measuredSpan(PerformanceCapture session, String name, long started,
                                      long duration) {
        if (session == null || !session.beginScope())
            return NOOP;
        var scope = new Scope(session, name, parent.get(), started, duration);
        parent.set(scope);
        return scope;
    }
    /** Snapshot at actual task dispatch; entering it preserves that frame across worker execution. */
    public static Context captureContext() {
        var session = capture;
        if (session == null)
            return EMPTY_CONTEXT;
        var scope = parent.get();
        if (scope != null && scope.session == session)
            return new Context(session, scope.frame, scope.id);
        var inherited = context.get();
        if (inherited != null && inherited.session == session)
            return inherited;
        return new Context(session, frameId, -1);
    }
    public static final class Context {
        private final PerformanceCapture session;
        private final long frame, parentId;
        private Context(PerformanceCapture session, long frame, long parentId) {
            this.session = session;
            this.frame = frame;
            this.parentId = parentId;
        }
        public ContextGuard enter() {
            if (session == null || capture != session)
                return NOOP_CONTEXT;
            var guard = new ContextGuard(context.get(), parent.get());
            parent.remove();
            context.set(this);
            return guard;
        }
    }
    public static final class ContextGuard implements AutoCloseable {
        private final Context previous;
        private final Scope previousParent;
        private boolean closed;
        private final boolean noop;
        private ContextGuard() {
            previous = null;
            previousParent = null;
            noop = true;
        }
        private ContextGuard(Context previous, Scope previousParent) {
            this.previous = previous;
            this.previousParent = previousParent;
            noop = false;
        }
        @Override
        public void close() {
            if (noop || closed)
                return;
            closed = true;
            if (previous == null)
                context.remove();
            else
                context.set(previous);
            if (previousParent == null)
                parent.remove();
            else
                parent.set(previousParent);
        }
    }
    public static final class Scope implements AutoCloseable {
        private final PerformanceCapture session;
        private final int name;
        private final Scope previous;
        private final long id, frame, started, thread, measuredDuration, parentId;
        private StringBuilder counts;
        private boolean closed, ok = true;
        private Scope() {
            session = null;
            name = 0;
            previous = null;
            id = frame = started = thread = measuredDuration = 0;
            parentId = -1;
        }
        private Scope(PerformanceCapture session, String name, Scope previous, long start,
                      long duration) {
            this.session = session;
            this.name = session.name(name);
            this.previous = previous;
            id = ids.incrementAndGet();
            var inherited = context.get();
            boolean sameParent = previous != null && previous.session == session;
            boolean sameContext = inherited != null && inherited.session == session;
            frame = sameParent ? previous.frame : sameContext ? inherited.frame : frameId;
            parentId = sameParent ? previous.id : sameContext ? inherited.parentId : -1;
            started = duration < 0 ? System.nanoTime() : start;
            measuredDuration = duration;
            var owner = Thread.currentThread();
            thread = owner.threadId();
            session.thread(thread, owner.getName());
        }
        public void fail() {
            if (session != null)
                ok = false;
        }
        void succeed() {
            if (session != null)
                ok = true;
        }
        public void count(String key, long value) {
            if (session == null)
                return;
            if (counts == null)
                counts = new StringBuilder();
            else
                counts.append(',');
            counts.append(PerformanceCapture.quote(key)).append(':').append(value);
        }
        @Override
        public void close() {
            if (session == null || closed)
                return;
            long duration = measuredDuration < 0 ? System.nanoTime() - started : measuredDuration;
            closed = true;
            parent.set(previous);
            try {
                session.javaEvent("{\"i\":" + id + ",\"p\":" + (parentId < 0 ? "null" : parentId) +
                                  ",\"f\":" + frame + ",\"n\":" + name + ",\"s\":" + started +
                                  ",\"d\":" + duration + ",\"t\":" + thread + ",\"ok\":" + ok +
                                  ",\"a\":{" + (counts == null ? "" : counts) + "}}");
            } finally {
                session.endScope();
            }
        }
    }
}
