package dev.primept;

import dev.primept.capture.CaptureInbox;
import java.util.Arrays;
import java.util.Locale;

/** Opt-in CPU timings. Recording is asynchronous; gpuLast is one completed GPU sample, not a window average. */
final class RenderProfile {
    private static final int WINDOW = 120;
    private final long[] hookTimes = new long[WINDOW];
    private long lastStart;
    private int frames, intervals, rasterSkipped;
    private long interval, vanilla, submit, drain, prune, nativeRender, total;
    private long batches, packets, bytes;

    static final class Frame {
        final long start;
        long submit, drain, prune, nativeRender;
        long batches, packets, bytes;
        Frame(long start) { this.start = start; }
    }

    Frame begin(long worldRenderStart) {
        long now = System.nanoTime();
        if (lastStart != 0) { interval += now - lastStart; ++intervals; }
        lastStart = now;
        if (worldRenderStart != 0) vanilla += now - worldRenderStart;
        return new Frame(now);
    }

    void finish(Frame frame, CaptureInbox inbox, int width, int height, HostVulkanRenderer renderer) {
        long duration = System.nanoTime() - frame.start;
        hookTimes[frames++] = duration;
        if (PrimeClient.skippedWorldRaster()) ++rasterSkipped;
        total += duration;
        submit += frame.submit;
        drain += frame.drain;
        prune += frame.prune;
        nativeRender += frame.nativeRender;
        batches += frame.batches;
        packets += frame.packets;
        bytes += frame.bytes;
        if (frames != WINDOW) return;
        long[] sorted = hookTimes.clone();
        Arrays.sort(sorted);
        var capture = inbox.profileSnapshot();
        PrimeClient.LOGGER.info(String.format(Locale.ROOT,
                "Prime PT CPU profile frames=%d size=%dx%d interval=%.3fms mcBeforePT=%.3fms hook=%.3fms p95=%.3fms max=%.3fms nativeSubmit=%.3fms drainIncludingSubmit=%.3fms prune=%.3fms nativeRecord=%.3fms gpuLast=%.3fms outputCpuBytes=0 rasterSkipped=%d batches=%d packets=%d bytes=%d trackedSections=%d queuedBatches=%d queuedBytes=%d",
                frames, width, height, intervals == 0 ? 0 : interval / (intervals * 1_000_000.0),
                mean(vanilla), mean(total), sorted[(int) (WINDOW * .95) - 1] / 1_000_000.0,
                sorted[WINDOW - 1] / 1_000_000.0, mean(submit), mean(drain), mean(prune), mean(nativeRender),
                renderer.lastGpuTimeNanos() / 1_000_000.0, rasterSkipped, batches, packets, bytes,
                capture.sections(), capture.batches(), capture.bytes()));
        clearWindow();
    }

    private double mean(long nanos) { return nanos / (frames * 1_000_000.0); }
    private void clearWindow() {
        frames = intervals = rasterSkipped = 0;
        interval = vanilla = submit = drain = prune = nativeRender = total = 0;
        batches = packets = bytes = 0;
    }
    void reset() { lastStart = 0; clearWindow(); }
}
