// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

package dev.prime.render.vulkan;

import com.mojang.blaze3d.systems.TimerQuery;
import jdk.jfr.Category;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;
import jdk.jfr.Timespan;

/** Render-thread-owned optional timing using Minecraft's nonblocking, three-sample GPU timer. */
final class SubmissionTiming implements AutoCloseable {
    private final TimerQuery timer = new TimerQuery();
    private final String phase;
    private Sample sample;

    SubmissionTiming(String phase) {
        this.phase = phase;
    }

    void begin() {
        // An outstanding query is skipped, never waited for. GPU time is the engine's moving
        // average of three completed samples, not an exact pairing with this CPU recording span.
        if (this.timer.getStatus() != TimerQuery.Status.NOT_RECORDING) return;
        this.sample = new Sample();
        this.sample.phase = this.phase;
        this.sample.previousGpuNanos = this.timer.get();
        this.sample.begin();
        this.timer.beginProfile();
    }

    void end() {
        if (this.sample == null) return;
        this.timer.endProfile();
        this.sample.end();
        this.sample.commit();
        this.sample = null;
    }

    void abandon() {
        if (this.sample == null) return;
        this.timer.endProfile();
        this.sample = null;
    }

    @Override public void close() {
        this.timer.close();
    }

    @Name("prime.Submission")
    @Label("Prime submission recording")
    @Category("Prime")
    @StackTrace(false)
    static final class Sample extends Event {
        @Label("Phase") public String phase;
        @Label("Previous GPU time (three completed samples)")
        @Timespan(Timespan.NANOSECONDS) public long previousGpuNanos;
    }
}
