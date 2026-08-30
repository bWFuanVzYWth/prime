package dev.prime.binding.streamline;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.StructLayout;
import java.lang.invoke.VarHandle;

import static java.lang.foreign.MemoryLayout.PathElement.groupElement;
import static java.lang.foreign.MemoryLayout.paddingLayout;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/** sl::ReflexReport — {0D569B37-A1C8-4453-BE4D-40F4DE57952B}, kStructVersion1. Out-only struct. */
public final class ReflexReport {
    public static final StructLayout LAYOUT = StructHeader.structWith(
            JAVA_LONG.withName("frameID"),
            JAVA_LONG.withName("inputSampleTime"),
            JAVA_LONG.withName("simStartTime"),
            JAVA_LONG.withName("simEndTime"),
            JAVA_LONG.withName("renderSubmitStartTime"),
            JAVA_LONG.withName("renderSubmitEndTime"),
            JAVA_LONG.withName("presentStartTime"),
            JAVA_LONG.withName("presentEndTime"),
            JAVA_LONG.withName("driverStartTime"),
            JAVA_LONG.withName("driverEndTime"),
            JAVA_LONG.withName("osRenderQueueStartTime"),
            JAVA_LONG.withName("osRenderQueueEndTime"),
            JAVA_LONG.withName("gpuRenderStartTime"),
            JAVA_LONG.withName("gpuRenderEndTime"),
            paddingLayout(4),
            JAVA_INT.withName("gpuActiveRenderTimeUs"),
            JAVA_INT.withName("gpuFrameTimeUs"),
            paddingLayout(4));

    private static final VarHandle FRAME_ID = LAYOUT.varHandle(groupElement("frameID"));
    private static final VarHandle INPUT_SAMPLE_TIME = LAYOUT.varHandle(groupElement("inputSampleTime"));
    private static final VarHandle SIM_START_TIME = LAYOUT.varHandle(groupElement("simStartTime"));
    private static final VarHandle SIM_END_TIME = LAYOUT.varHandle(groupElement("simEndTime"));
    private static final VarHandle RENDER_SUBMIT_START_TIME = LAYOUT.varHandle(groupElement("renderSubmitStartTime"));
    private static final VarHandle RENDER_SUBMIT_END_TIME = LAYOUT.varHandle(groupElement("renderSubmitEndTime"));
    private static final VarHandle PRESENT_START_TIME = LAYOUT.varHandle(groupElement("presentStartTime"));
    private static final VarHandle PRESENT_END_TIME = LAYOUT.varHandle(groupElement("presentEndTime"));
    private static final VarHandle DRIVER_START_TIME = LAYOUT.varHandle(groupElement("driverStartTime"));
    private static final VarHandle DRIVER_END_TIME = LAYOUT.varHandle(groupElement("driverEndTime"));
    private static final VarHandle OS_RENDER_QUEUE_START_TIME = LAYOUT.varHandle(groupElement("osRenderQueueStartTime"));
    private static final VarHandle OS_RENDER_QUEUE_END_TIME = LAYOUT.varHandle(groupElement("osRenderQueueEndTime"));
    private static final VarHandle GPU_RENDER_START_TIME = LAYOUT.varHandle(groupElement("gpuRenderStartTime"));
    private static final VarHandle GPU_RENDER_END_TIME = LAYOUT.varHandle(groupElement("gpuRenderEndTime"));
    private static final VarHandle GPU_ACTIVE_RENDER_TIME_US = LAYOUT.varHandle(groupElement("gpuActiveRenderTimeUs"));
    private static final VarHandle GPU_FRAME_TIME_US = LAYOUT.varHandle(groupElement("gpuFrameTimeUs"));

    private final MemorySegment segment;

    private ReflexReport(MemorySegment segment) {
        this.segment = segment;
    }

    public static ReflexReport wrap(MemorySegment segment) {
        return new ReflexReport(segment);
    }

    public MemorySegment segment() {
        return this.segment;
    }

    public long frameID() {
        return (long) FRAME_ID.get(this.segment, 0L);
    }

    public long inputSampleTime() {
        return (long) INPUT_SAMPLE_TIME.get(this.segment, 0L);
    }

    public long simStartTime() {
        return (long) SIM_START_TIME.get(this.segment, 0L);
    }

    public long simEndTime() {
        return (long) SIM_END_TIME.get(this.segment, 0L);
    }

    public long renderSubmitStartTime() {
        return (long) RENDER_SUBMIT_START_TIME.get(this.segment, 0L);
    }

    public long renderSubmitEndTime() {
        return (long) RENDER_SUBMIT_END_TIME.get(this.segment, 0L);
    }

    public long presentStartTime() {
        return (long) PRESENT_START_TIME.get(this.segment, 0L);
    }

    public long presentEndTime() {
        return (long) PRESENT_END_TIME.get(this.segment, 0L);
    }

    public long driverStartTime() {
        return (long) DRIVER_START_TIME.get(this.segment, 0L);
    }

    public long driverEndTime() {
        return (long) DRIVER_END_TIME.get(this.segment, 0L);
    }

    public long osRenderQueueStartTime() {
        return (long) OS_RENDER_QUEUE_START_TIME.get(this.segment, 0L);
    }

    public long osRenderQueueEndTime() {
        return (long) OS_RENDER_QUEUE_END_TIME.get(this.segment, 0L);
    }

    public long gpuRenderStartTime() {
        return (long) GPU_RENDER_START_TIME.get(this.segment, 0L);
    }

    public long gpuRenderEndTime() {
        return (long) GPU_RENDER_END_TIME.get(this.segment, 0L);
    }

    public int gpuActiveRenderTimeUs() {
        return (int) GPU_ACTIVE_RENDER_TIME_US.get(this.segment, 0L);
    }

    public int gpuFrameTimeUs() {
        return (int) GPU_FRAME_TIME_US.get(this.segment, 0L);
    }
}
