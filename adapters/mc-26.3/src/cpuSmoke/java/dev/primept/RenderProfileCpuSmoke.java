package dev.primept;

import dev.primept.capture.CaptureInbox;
import dev.primept.capture.DynamicCapture;
import dev.primept.capture.ModelCapture;
import dev.primept.capture.ExclusiveTerrainCapture;

/** Exercises actual warning formatting/units without a device or a native renderer. */
public final class RenderProfileCpuSmoke {
    public static void run() {
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
        var terrain = new ExclusiveTerrainCapture.Stats(700, 2, 3, 4, 1, 200, 8, 12, 100, 80, 90,
                                                        1_000_000, 2_000_000, 7_000_000, 123, 456,
                                                        3_000_000, 654321);
        var text = RenderProfile.slowMessage(11, 77, 1920, 1080, false, frame, timing, terrain,
                                             new CaptureInbox.ProfileSnapshot(12345, 0, 0),
                                             ModelCapture.stats(), DynamicCapture.stats(),
                                             "serial=77 static_prepare=3.000 slot_wait=1.000", 0);
        for (String required :
             new String[] {"frame=11 serial=77", "currentWork=60.000ms", "extraction=10.000ms",
                           "nativeSubmit=6.000ms", "resourceSubmit=2.000ms", "sourcePack=2.000ms",
                           "sourceAccept=3.000ms", "sectionSourceBytes=654321",
                           "extractionOther=3.000ms", "outsideInterval=25.000ms",
                           "sourceBytes=123456", "lightEngineNotifications=123",
                           "lightPacketNotifications=456", "native={serial=77"})
            if (!text.contains(required))
                throw new AssertionError("Missing diagnostic value: " + required + " in " + text);
        System.out.println(
                "PRIME_PT_SLOW_FRAME_CPU_OK: always-on coarse clocks; actual 50ms warning fields, units and native correlation");
    }
}
