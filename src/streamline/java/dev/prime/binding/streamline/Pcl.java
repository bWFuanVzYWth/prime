// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

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

    private static final FunctionDescriptor SET_MARKER_DESC =
            FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS);

    private final MethodHandle slPCLSetMarker;

    private Pcl(MethodHandle slPCLSetMarker) {
        this.slPCLSetMarker = slPCLSetMarker;
    }

    public static Pcl load(Streamline streamline, int featureId) {
        return new Pcl(
                streamline.getFeatureFunction(featureId, "slPCLSetMarker", SET_MARKER_DESC));
    }

    /** @param frameToken address-carrying segment for the {@code sl::FrameToken*} from {@link Streamline#getNewFrameToken} */
    public int setMarker(PclMarker marker, MemorySegment frameToken) {
        try {
            return (int) this.slPCLSetMarker.invokeExact(marker.value, frameToken);
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }
}
