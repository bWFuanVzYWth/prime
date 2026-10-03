package dev.primept;

/** Exercises real coarse clocks without a device or native renderer. Raw exporter tests live in common. */
public final class RenderProfileCpuSmoke {
    public static void run() throws Exception {
        var profile = new RenderProfile();
        if (profile.begin(0).start != 0)
            throw new AssertionError("Disabled frame clock must be a no-op");
        Diagnostics.setCaptureRequested(true);
        profile.beginExtraction();
        profile.endExtraction();
        var first = profile.begin(0);
        if (first.extraction <= 0 || first.start <= 0 || first.interval != 0)
            throw new AssertionError(
                    "Coarse clocks must preserve measured extraction/start and unavailable initial interval");
        var second = profile.begin(first.start);
        if (second.interval <= 0 || second.beforePT <= 0)
            throw new AssertionError(
                    "Subsequent cadence and actual host boundary must be measured");
        profile.reset();
        if (profile.begin(0).interval != 0)
            throw new AssertionError("Reset must invalidate previous cadence");
        Diagnostics.setCaptureRequested(false);
        System.out.println(
                "PRIME_PT_FRAME_CPU_OK: actual coarse frame clocks and reset; raw JSON capture in common tests");
    }
}
