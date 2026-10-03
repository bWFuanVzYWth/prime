// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.
package dev.primept.display;

import org.lwjgl.sdl.SDLProperties;
import org.lwjgl.sdl.SDLVideo;
import org.lwjgl.system.Platform;

/** 26.3 exposes the native HMONITOR through its SDL display properties. */
public final class HostHdrDisplay {
    private HostHdrDisplay() {}

    public static WindowsHdrDisplay.Snapshot queryWindow(long window) {
        return WindowsHdrDisplay.queryMonitor(monitorIdentity(window));
    }

    /** Cheap SDL display identity; the shared DXGI probe runs only when calibration refreshes. */
    public static long monitorIdentity(long window) {
        if (Platform.get() != Platform.WINDOWS || window == 0)
            return 0;
        try {
            int display = SDLVideo.SDL_GetDisplayForWindow(window);
            int properties = display == 0 ? 0 : SDLVideo.SDL_GetDisplayProperties(display);
            long monitor = properties == 0
                                   ? 0
                                   : SDLProperties.SDL_GetPointerProperty(
                                             properties,
                                             SDLVideo.SDL_PROP_DISPLAY_WINDOWS_HMONITOR_POINTER, 0);
            return monitor;
        } catch (RuntimeException | LinkageError exception) {
            return 0;
        }
    }
}
