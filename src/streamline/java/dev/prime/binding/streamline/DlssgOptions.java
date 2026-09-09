// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

package dev.prime.binding.streamline;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.StructLayout;
import java.lang.invoke.VarHandle;

import static java.lang.foreign.MemoryLayout.PathElement.groupElement;
import static java.lang.foreign.MemoryLayout.paddingLayout;
import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_FLOAT;
import static java.lang.foreign.ValueLayout.JAVA_INT;

/** sl::DLSSGOptions — {FAC5F1CB-2DFD-4F36-A1E6-3A9E865256C5}, kStructVersion5 */
public final class DlssgOptions {
    private static final int MODE_ON = 1;

    public static final StructLayout LAYOUT = StructHeader.structWith(
            JAVA_INT.withName("mode"),
            JAVA_INT.withName("numFramesToGenerate"),
            JAVA_INT.withName("flags"),
            JAVA_INT.withName("dynamicResWidth"),
            JAVA_INT.withName("dynamicResHeight"),
            JAVA_INT.withName("numBackBuffers"),
            JAVA_INT.withName("mvecDepthWidth"),
            JAVA_INT.withName("mvecDepthHeight"),
            JAVA_INT.withName("colorWidth"),
            JAVA_INT.withName("colorHeight"),
            JAVA_INT.withName("colorBufferFormat"),
            JAVA_INT.withName("mvecBufferFormat"),
            JAVA_INT.withName("depthBufferFormat"),
            JAVA_INT.withName("hudLessBufferFormat"),
            JAVA_INT.withName("uiBufferFormat"),
            paddingLayout(4),
            ADDRESS.withName("onErrorCallback"),
            JAVA_BYTE.withName("reserved15"),
            paddingLayout(3),
            JAVA_INT.withName("queueParallelismMode"),
            JAVA_BYTE.withName("enableUserInterfaceRecomposition"),
            paddingLayout(3),
            JAVA_FLOAT.withName("dynamicTargetFrameRate"));

    private static final VarHandle MODE = LAYOUT.varHandle(groupElement("mode"));
    private static final VarHandle NUM_FRAMES_TO_GENERATE = LAYOUT.varHandle(groupElement("numFramesToGenerate"));
    private static final VarHandle NUM_BACK_BUFFERS = LAYOUT.varHandle(groupElement("numBackBuffers"));
    private static final VarHandle MVEC_DEPTH_WIDTH = LAYOUT.varHandle(groupElement("mvecDepthWidth"));
    private static final VarHandle MVEC_DEPTH_HEIGHT = LAYOUT.varHandle(groupElement("mvecDepthHeight"));
    private static final VarHandle COLOR_WIDTH = LAYOUT.varHandle(groupElement("colorWidth"));
    private static final VarHandle COLOR_HEIGHT = LAYOUT.varHandle(groupElement("colorHeight"));
    private static final VarHandle COLOR_BUFFER_FORMAT = LAYOUT.varHandle(groupElement("colorBufferFormat"));
    private static final VarHandle MVEC_BUFFER_FORMAT = LAYOUT.varHandle(groupElement("mvecBufferFormat"));
    private static final VarHandle DEPTH_BUFFER_FORMAT = LAYOUT.varHandle(groupElement("depthBufferFormat"));
    private static final VarHandle HUD_LESS_BUFFER_FORMAT = LAYOUT.varHandle(groupElement("hudLessBufferFormat"));
    private static final VarHandle UI_BUFFER_FORMAT = LAYOUT.varHandle(groupElement("uiBufferFormat"));
    private static final VarHandle RESERVED_15 = LAYOUT.varHandle(groupElement("reserved15"));
    private static final VarHandle ENABLE_USER_INTERFACE_RECOMPOSITION = LAYOUT.varHandle(groupElement("enableUserInterfaceRecomposition"));

    private final MemorySegment segment;

    private DlssgOptions(MemorySegment segment) {
        this.segment = segment;
    }

    public static DlssgOptions disabled(Arena arena) {
        return allocate(arena);
    }

    public static DlssgOptions enabled(
            Arena arena,
            int framesToGenerate,
            int backBuffers,
            int motionWidth,
            int motionHeight,
            int colorWidth,
            int colorHeight,
            int colorFormat,
            int motionFormat,
            int depthFormat,
            int hudlessFormat,
            int uiFormat,
            boolean uiRecomposition) {
        DlssgOptions options = allocate(arena);
        MODE.set(options.segment, 0L, MODE_ON);
        NUM_FRAMES_TO_GENERATE.set(options.segment, 0L, framesToGenerate);
        NUM_BACK_BUFFERS.set(options.segment, 0L, backBuffers);
        MVEC_DEPTH_WIDTH.set(options.segment, 0L, motionWidth);
        MVEC_DEPTH_HEIGHT.set(options.segment, 0L, motionHeight);
        COLOR_WIDTH.set(options.segment, 0L, colorWidth);
        COLOR_HEIGHT.set(options.segment, 0L, colorHeight);
        COLOR_BUFFER_FORMAT.set(options.segment, 0L, colorFormat);
        MVEC_BUFFER_FORMAT.set(options.segment, 0L, motionFormat);
        DEPTH_BUFFER_FORMAT.set(options.segment, 0L, depthFormat);
        HUD_LESS_BUFFER_FORMAT.set(options.segment, 0L, hudlessFormat);
        UI_BUFFER_FORMAT.set(options.segment, 0L, uiFormat);
        ENABLE_USER_INTERFACE_RECOMPOSITION.set(
                options.segment, 0L, (byte) (uiRecomposition ? 1 : 0));
        return options;
    }

    private static DlssgOptions allocate(Arena arena) {
        MemorySegment segment = arena.allocate(LAYOUT);
        StructHeader.init(segment, 0xfac5f1cb, (short) 0x2dfd, (short) 0x4f36, 0xC55652869E3AE6A1L, 5);
        NUM_FRAMES_TO_GENERATE.set(segment, 0L, 1);
        RESERVED_15.set(segment, 0L, SlBoolean.INVALID.value);
        return new DlssgOptions(segment);
    }

    public MemorySegment segment() {
        return this.segment;
    }

}
