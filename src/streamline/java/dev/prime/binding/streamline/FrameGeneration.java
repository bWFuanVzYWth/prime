package dev.prime.binding.streamline;

import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;

/** Convenience wrapper for the DLSS-G (frame generation) feature functions. */
public final class FrameGeneration {
    /** sl::kFeatureDLSS_G */
    public static final int FEATURE_ID = 1000;

    private static final FunctionDescriptor GET_STATE_DESC = FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS);
    private static final FunctionDescriptor SET_OPTIONS_DESC = FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS);

    private final MethodHandle slDLSSGGetState;
    private final MethodHandle slDLSSGSetOptions;

    private FrameGeneration(MethodHandle slDLSSGGetState, MethodHandle slDLSSGSetOptions) {
        this.slDLSSGGetState = slDLSSGGetState;
        this.slDLSSGSetOptions = slDLSSGSetOptions;
    }

    public static FrameGeneration load(Streamline streamline, int featureId) {
        return new FrameGeneration(
                streamline.getFeatureFunction(featureId, "slDLSSGGetState", GET_STATE_DESC),
                streamline.getFeatureFunction(featureId, "slDLSSGSetOptions", SET_OPTIONS_DESC));
    }

    /** @param options optional {@link DlssgOptions} queried together with the state, may be null */
    public int getState(ViewportHandle viewport, DlssgState stateOut, DlssgOptions options) {
        MemorySegment optionsSegment = options == null
                ? MemorySegment.NULL
                : options.segment();
        try {
            return (int) this.slDLSSGGetState.invokeExact(
                    viewport.segment(), stateOut.segment(), optionsSegment);
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }

    public int setOptions(ViewportHandle viewport, DlssgOptions options) {
        try {
            return (int) this.slDLSSGSetOptions.invokeExact(viewport.segment(), options.segment());
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }
}
