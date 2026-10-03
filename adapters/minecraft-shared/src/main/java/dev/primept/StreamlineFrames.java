package dev.primept;

import static dev.primept.abi.PrimeAbi.*;

/** Host frame markers follow the old project's simulation/render/real-Present boundaries. */
public final class StreamlineFrames {
    private static boolean active;
    private static boolean prepared;
    private StreamlineFrames() {}

    public static void begin() {
        prepared = false;
        active = StreamlineBootstrap.installed() && HostVulkanRenderer.frameGenerationRequested();
        StreamlineBootstrap.frame(PRIME_STREAMLINE_BEGIN_FRAME, active);
        HostVulkanRenderer.beginPresentationFrame();
    }
    public static void renderBegin() {
        if (active)
            StreamlineBootstrap.frame(PRIME_STREAMLINE_RENDER_START, true);
    }
    public static boolean active() {
        return active;
    }
    public static void prepared(boolean value) {
        prepared = value;
    }
    public static void beforePresent() {
        if (active && !prepared)
            suspend();
    }
    public static void suspend() {
        StreamlineBootstrap.frame(PRIME_STREAMLINE_SUSPEND, false);
        active = prepared = false;
    }
    public static void shutdown() {
        suspend();
        StreamlineBootstrap.frame(PRIME_STREAMLINE_HOST_SHUTDOWN, false);
    }
}
