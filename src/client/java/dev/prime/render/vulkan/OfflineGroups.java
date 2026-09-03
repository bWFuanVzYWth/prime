package dev.prime.render.vulkan;

/** Offline four-stage transport topology. */
final class OfflineGroups {
    static final int CAMERA_TRACE = 0;
    static final int BRIDGE_TRACE_0 = 1;
    static final int LIGHT_SELECT_0 = 2;
    static final int DIRECT_0 = 3;
    static final int SCATTER_0 = 4;
    static final int BRIDGE_TRACE_1 = 5;
    static final int LIGHT_SELECT_1 = 6;
    static final int DIRECT_1 = 7;
    static final int SCATTER_1 = 8;
    static final int SAMPLE_RESOLVE = 9;

    private OfflineGroups() {
    }

    static RaygenSchedule schedule(String suffix) {
        String mode = switch (suffix) {
            case ".rgen.spv" -> "scalar";
            case "_ser.rgen.spv" -> "ser";
            default -> throw new IllegalArgumentException("Unknown wavefront shader suffix: " + suffix);
        };
        return GeneratedShaderPrograms.schedule("offline." + mode);
    }

    static int bridgeTrace(int queue) {
        return queued(queue, BRIDGE_TRACE_0, BRIDGE_TRACE_1);
    }

    static int lightSelect(int queue) {
        return queued(queue, LIGHT_SELECT_0, LIGHT_SELECT_1);
    }

    static int direct(int queue) {
        return queued(queue, DIRECT_0, DIRECT_1);
    }

    static int scatter(int queue) {
        return queued(queue, SCATTER_0, SCATTER_1);
    }

    private static int queued(int queue, int first, int second) {
        return switch (queue) {
            case 0 -> first;
            case 1 -> second;
            default -> throw new IllegalArgumentException("Invalid wavefront queue");
        };
    }
}
