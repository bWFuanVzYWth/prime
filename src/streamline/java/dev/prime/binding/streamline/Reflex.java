package dev.prime.binding.streamline;

import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;

/** Convenience wrapper for the Reflex (low latency) feature functions. Required by frame generation. */
public final class Reflex {
    /** sl::kFeatureReflex */
    public static final int FEATURE_ID = 3;

    private static final FunctionDescriptor GET_STATE_DESC = FunctionDescriptor.of(JAVA_INT, ADDRESS);
    private static final FunctionDescriptor SLEEP_DESC = FunctionDescriptor.of(JAVA_INT, ADDRESS);
    private static final FunctionDescriptor SET_OPTIONS_DESC = FunctionDescriptor.of(JAVA_INT, ADDRESS);

    private final MethodHandle slReflexGetState;
    private final MethodHandle slReflexSleep;
    private final MethodHandle slReflexSetOptions;

    private Reflex(MethodHandle slReflexGetState, MethodHandle slReflexSleep, MethodHandle slReflexSetOptions) {
        this.slReflexGetState = slReflexGetState;
        this.slReflexSleep = slReflexSleep;
        this.slReflexSetOptions = slReflexSetOptions;
    }

    public static Reflex load(Streamline streamline, int featureId) {
        return new Reflex(
                streamline.getFeatureFunction(featureId, "slReflexGetState", GET_STATE_DESC),
                streamline.getFeatureFunction(featureId, "slReflexSleep", SLEEP_DESC),
                streamline.getFeatureFunction(featureId, "slReflexSetOptions", SET_OPTIONS_DESC));
    }

    public int getState(ReflexState stateOut) {
        try {
            return (int) this.slReflexGetState.invokeExact(stateOut.segment());
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }

    /** @param frameToken address-carrying segment for the {@code sl::FrameToken*} from {@link Streamline#getNewFrameToken} */
    public int sleep(MemorySegment frameToken) {
        try {
            return (int) this.slReflexSleep.invokeExact(frameToken);
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }

    public int setOptions(ReflexOptions options) {
        try {
            return (int) this.slReflexSetOptions.invokeExact(options.segment());
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }
}
