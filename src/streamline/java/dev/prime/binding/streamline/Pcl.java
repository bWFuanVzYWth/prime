package dev.prime.binding.streamline;

import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;

/** Convenience wrapper for the PCL (PC latency) feature functions. */
public final class Pcl {
    /** sl::kFeaturePCL */
    public static final int FEATURE_ID = 4;

    private static final FunctionDescriptor GET_STATE_DESC = FunctionDescriptor.of(JAVA_INT, ADDRESS);
    private static final FunctionDescriptor SET_MARKER_DESC = FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS);
    private static final FunctionDescriptor SET_OPTIONS_DESC = FunctionDescriptor.of(JAVA_INT, ADDRESS);

    private final MethodHandle slPCLGetState;
    private final MethodHandle slPCLSetMarker;
    private final MethodHandle slPCLSetOptions;

    private Pcl(MethodHandle slPCLGetState, MethodHandle slPCLSetMarker, MethodHandle slPCLSetOptions) {
        this.slPCLGetState = slPCLGetState;
        this.slPCLSetMarker = slPCLSetMarker;
        this.slPCLSetOptions = slPCLSetOptions;
    }

    public static Pcl load(Streamline streamline, int featureId) {
        return new Pcl(
                streamline.getFeatureFunction(featureId, "slPCLGetState", GET_STATE_DESC),
                streamline.getFeatureFunction(featureId, "slPCLSetMarker", SET_MARKER_DESC),
                streamline.getFeatureFunction(featureId, "slPCLSetOptions", SET_OPTIONS_DESC));
    }

    public int getState(PclState stateOut) {
        try {
            return (int) this.slPCLGetState.invokeExact(stateOut.segment());
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }

    /** @param frameToken address-carrying segment for the {@code sl::FrameToken*} from {@link Streamline#getNewFrameToken} */
    public int setMarker(PclMarker marker, MemorySegment frameToken) {
        try {
            return (int) this.slPCLSetMarker.invokeExact(marker.value, frameToken);
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }

    public int setOptions(PclOptions options) {
        try {
            return (int) this.slPCLSetOptions.invokeExact(options.segment());
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }
}
