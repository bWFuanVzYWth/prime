package dev.primept;

import dev.primept.capture.DynamicCapture;
import dev.primept.capture.ModelCapture;
import dev.primept.capture.ExclusiveTerrainCapture;

/** Exercises actual warning formatting/units without a device or a native renderer. */
public final class RenderProfileCpuSmoke {
    public static void run() throws Exception {
        var profile = new RenderProfile();
        profile.beginExtraction();
        profile.endExtraction();
        var measured = profile.begin(0);
        if (measured.extraction <= 0)
            throw new AssertionError("Coarse clocks must run without profile flags");
        var frame = new RenderProfile.Frame(0, 90_000_000, 35_000_000, 10_000_000);
        frame.drain = 7_000_000;
        frame.submit = 6_000_000;
        frame.resourceSubmit = 2_000_000;
        frame.nativeRender = 8_000_000;
        frame.bytes = 123456;
        var timing = new FrameTimings(frame.interval, 20_000_000, frame.extraction, frame.beforePT,
                                      15_000_000);
        var terrain = new ExclusiveTerrainCapture.Stats(700, 2, 3, 4, 1, 200, 100, 100, 1_000_000,
                                                        2_000_000, 7_000_000, 123, 456, 3_000_000,
                                                        654321, 99, 500_000);
        var text = RenderProfile.slowMessage(11, 77, 1920, 1080, false, frame, timing, terrain,
                                             ModelCapture.stats(), DynamicCapture.stats(),
                                             "serial=77 static_prepare=3.000 slot_wait=1.000", 0);
        for (String required : new String[] {
                     "frame=11 serial=77", "currentWork=60.000ms", "extraction=10.000ms",
                     "nativeSubmit=6.000ms", "resourceSubmit=2.000ms", "sourcePack=2.000ms",
                     "sourceAccept=3.000ms", "sectionSourceBytes=654321", "tintQueries=99",
                     "tintCallback=0.500ms", "extractionOther=3.000ms", "outsideInterval=25.000ms",
                     "requestedSources=200 availableSources=100 missingSources=100",
                     "sourceBytes=123456", "lightEngineNotifications=123",
                     "lightPacketNotifications=456", "native={serial=77"})
            if (!text.contains(required))
                throw new AssertionError("Missing diagnostic value: " + required + " in " + text);
        var csv = new java.io.StringWriter();
        var writer = new java.io.BufferedWriter(csv);
        var samples = RenderProfile.class.getDeclaredField("samples");
        samples.setAccessible(true);
        samples.set(profile, writer);
        var opened = RenderProfile.class.getDeclaredField("samplesOpened");
        opened.setAccessible(true);
        opened.setBoolean(profile, true);
        var write = RenderProfile.class.getDeclaredMethod(
                "writeSample", RenderProfile.Frame.class, long.class, int.class, int.class,
                DynamicCapture.Stats.class, ModelCapture.Stats.class,
                ExclusiveTerrainCapture.Stats.class, long.class);
        write.setAccessible(true);
        var models = new ModelCapture.Stats(
                0, 0, 0, 0, 0, 392, 1, 0, 1,
                new dev.primept.capture.InstanceCapture.Stats(1, 1, 0, 1, 0, 392, 65536, 1, 96), 0);
        write.invoke(profile, frame, 15_000_000L, 1920, 1080, DynamicCapture.stats(), models,
                     terrain, 0L);
        writer.flush();
        String[] columns = RenderProfile.CSV_HEADER.strip().split(","),
                 values = csv.toString().strip().split(",");
        if (columns.length != values.length)
            throw new AssertionError("CSV columns must match actual emitted values");
        var actual = new java.util.HashMap<String, String>();
        for (int i = 0; i < columns.length; ++i)
            actual.put(columns[i], values[i]);
        if (!"392".equals(actual.get("op7_bytes")) ||
            !"200".equals(actual.get("terrain_requested_sources")) ||
            !"100".equals(actual.get("terrain_available_sources")) ||
            !"100".equals(actual.get("terrain_missing_sources")) ||
            actual.containsKey("terrain_pending") || actual.containsKey("terrain_waiting"))
            throw new AssertionError(
                    "CSV must report complete batches and host source response counts");
        System.out.println(
                "PRIME_PT_SLOW_FRAME_CPU_OK: always-on coarse clocks; actual 50ms warning fields, units and native correlation");
    }
}
