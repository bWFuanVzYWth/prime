// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.
package dev.primept.display;

import org.lwjgl.glfw.GLFWNativeWin32;
import org.lwjgl.system.JNI;
import org.lwjgl.system.Library;
import org.lwjgl.system.Platform;
import org.lwjgl.system.SharedLibrary;

/** 26.2 uses GLFW; keep its HWND route outside the shared DXGI/DisplayConfig probe. */
public final class HostHdrDisplay {
    private HostHdrDisplay() {}

    public static WindowsHdrDisplay.Snapshot queryWindow(long window) {
        return WindowsHdrDisplay.queryMonitor(monitorIdentity(window));
    }

    /** Cheap native display identity; no DXGI enumeration or display calibration query. */
    public static long monitorIdentity(long window) {
        if (Platform.get() != Platform.WINDOWS || window == 0)
            return 0;
        try {
            long function = MonitorApi.FUNCTION;
            long hwnd = GLFWNativeWin32.glfwGetWin32Window(window);
            return function == 0 || hwnd == 0 ? 0 : JNI.invokePP(hwnd, 2, function);
        } catch (RuntimeException | LinkageError exception) {
            return 0;
        }
    }

    private static final class MonitorApi {
        // user32 is a process-lifetime Windows system dependency. Retain its native owner so
        // repeated monitor checks reuse the function pointer without LoadLibrary/FreeLibrary.
        static final SharedLibrary LIBRARY =
                Library.loadNative(HostHdrDisplay.class, "primept", "user32");
        static final long FUNCTION = LIBRARY.getFunctionAddress("MonitorFromWindow");
    }
}
