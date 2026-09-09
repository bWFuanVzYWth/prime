// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

package dev.prime.client;

import dev.prime.infrastructure.PrimeInfo;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import org.lwjgl.PointerBuffer;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWNativeWin32;
import org.lwjgl.system.JNI;
import org.lwjgl.system.Library;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.system.Platform;
import org.lwjgl.system.Pointer;
import org.lwjgl.system.SharedLibrary;

/** Queries the active Windows display's live HDR mode, peak luminance and SDR white level. */
public final class WindowsHdrDisplay {
    private static final int DXGI_ERROR_NOT_FOUND = 0x887A0002;
    private static final int DXGI_COLOR_SPACE_RGB_FULL_G10_NONE_P709 = 1;
    private static final int DXGI_COLOR_SPACE_RGB_FULL_G2084_NONE_P2020 = 12;
    private static final int QDC_ONLY_ACTIVE_PATHS = 0x2;
    private static final int ERROR_INSUFFICIENT_BUFFER = 122;
    private static final int MAXIMUM_DISPLAY_CONFIG_QUERY_ATTEMPTS = 4;
    private static final int DISPLAYCONFIG_GET_SOURCE_NAME = 1;
    private static final int DISPLAYCONFIG_GET_SDR_WHITE_LEVEL = 11;
    private static final int PATH_INFO_SIZE = 72;
    private static final int MODE_INFO_SIZE = 64;
    private static final int SOURCE_NAME_SIZE = 84;
    private static final int SDR_WHITE_LEVEL_SIZE = 24;
    private static final int TARGET_INFO_OFFSET = 20;
    private static final int OUTPUT_DESC_SIZE = 96;
    private static final int OUTPUT_DESC1_SIZE = 152;
    private static final int OUTPUT_DESC1_COLOR_SPACE_OFFSET = 100;
    private static final int OUTPUT_DESC1_MAX_LUMINANCE_OFFSET = 140;
    private static boolean warned;

    private WindowsHdrDisplay() {
    }

    public static Snapshot query(long window) {
        if (Platform.get() != Platform.WINDOWS) {
            return Snapshot.UNAVAILABLE;
        }
        try {
            String displayName = currentDisplayName(window);
            if (displayName == null) {
                return Snapshot.UNAVAILABLE;
            }
            return queryDxgi(displayName);
        } catch (RuntimeException | LinkageError exception) {
            if (!warned) {
                warned = true;
                PrimeInfo.LOGGER.warn(
                        "Could not query Windows HDR display state; HDR output is unavailable",
                        exception);
            }
            return Snapshot.UNAVAILABLE;
        }
    }

    private static String currentDisplayName(long window) {
        long monitor = GLFW.glfwGetWindowMonitor(window);
        if (monitor == MemoryUtil.NULL) {
            monitor = overlappingMonitor(window);
        }
        return monitor == MemoryUtil.NULL
                ? null
                : GLFWNativeWin32.glfwGetWin32Adapter(monitor);
    }

    private static long overlappingMonitor(long window) {
        PointerBuffer monitors = GLFW.glfwGetMonitors();
        if (monitors == null || !monitors.hasRemaining()) {
            return MemoryUtil.NULL;
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer windowX = stack.mallocInt(1);
            IntBuffer windowY = stack.mallocInt(1);
            IntBuffer windowWidth = stack.mallocInt(1);
            IntBuffer windowHeight = stack.mallocInt(1);
            GLFW.glfwGetWindowPos(window, windowX, windowY);
            GLFW.glfwGetWindowSize(window, windowWidth, windowHeight);
            int left = windowX.get(0);
            int top = windowY.get(0);
            int right = left + Math.max(windowWidth.get(0), 1);
            int bottom = top + Math.max(windowHeight.get(0), 1);
            long selected = monitors.get(monitors.position());
            long largestArea = -1L;
            IntBuffer monitorX = stack.mallocInt(1);
            IntBuffer monitorY = stack.mallocInt(1);
            IntBuffer monitorWidth = stack.mallocInt(1);
            IntBuffer monitorHeight = stack.mallocInt(1);
            for (int index = monitors.position(); index < monitors.limit(); index++) {
                long candidate = monitors.get(index);
                GLFW.glfwGetMonitorWorkarea(
                        candidate, monitorX, monitorY, monitorWidth, monitorHeight);
                int monitorLeft = monitorX.get(0);
                int monitorTop = monitorY.get(0);
                int monitorRight = monitorLeft + monitorWidth.get(0);
                int monitorBottom = monitorTop + monitorHeight.get(0);
                long overlapWidth = Math.max(
                        0, Math.min(right, monitorRight) - Math.max(left, monitorLeft));
                long overlapHeight = Math.max(
                        0, Math.min(bottom, monitorBottom) - Math.max(top, monitorTop));
                long area = overlapWidth * overlapHeight;
                if (area > largestArea) {
                    largestArea = area;
                    selected = candidate;
                }
            }
            return selected;
        }
    }

    private static Snapshot queryDxgi(String requestedDisplayName) {
        try (SharedLibrary dxgi = Library.loadNative(
                WindowsHdrDisplay.class, "prime", "dxgi")) {
            long createFactory = requireFunction(dxgi, "CreateDXGIFactory1");
            try (MemoryStack stack = MemoryStack.stackPush()) {
                ByteBuffer factoryIid = guid(
                        stack,
                        0x770aae78,
                        0xf26f,
                        0x4dba,
                        0xa8, 0x29, 0x25, 0x3c, 0x83, 0xd1, 0xb3, 0x87);
                PointerBuffer pointer = stack.callocPointer(1);
                checkHresult(
                        JNI.invokePPI(
                                MemoryUtil.memAddress(factoryIid),
                                MemoryUtil.memAddress(pointer),
                                createFactory),
                        "CreateDXGIFactory1");
                long factory = pointer.get(0);
                try {
                    return findOutput(factory, requestedDisplayName, stack);
                } finally {
                    release(factory);
                }
            }
        }
    }

    private static Snapshot findOutput(
            long factory,
            String requestedDisplayName,
            MemoryStack stack) {
        PointerBuffer pointer = stack.callocPointer(1);
        for (int adapterIndex = 0; ; adapterIndex++) {
            pointer.put(0, MemoryUtil.NULL);
            int adapterResult = JNI.invokePPI(
                    factory,
                    adapterIndex,
                    MemoryUtil.memAddress(pointer),
                    vtable(factory, 12));
            if (adapterResult == DXGI_ERROR_NOT_FOUND) {
                break;
            }
            checkHresult(adapterResult, "IDXGIFactory1::EnumAdapters1");
            long adapter = pointer.get(0);
            try {
                Snapshot found = findAdapterOutput(
                        adapter, requestedDisplayName, stack, pointer);
                if (found.available()) {
                    return found;
                }
            } finally {
                release(adapter);
            }
        }
        return Snapshot.UNAVAILABLE;
    }

    private static Snapshot findAdapterOutput(
            long adapter,
            String requestedDisplayName,
            MemoryStack stack,
            PointerBuffer pointer) {
        ByteBuffer description = stack.calloc(OUTPUT_DESC_SIZE);
        for (int outputIndex = 0; ; outputIndex++) {
            pointer.put(0, MemoryUtil.NULL);
            int outputResult = JNI.invokePPI(
                    adapter,
                    outputIndex,
                    MemoryUtil.memAddress(pointer),
                    vtable(adapter, 7));
            if (outputResult == DXGI_ERROR_NOT_FOUND) {
                return Snapshot.UNAVAILABLE;
            }
            checkHresult(outputResult, "IDXGIAdapter::EnumOutputs");
            long output = pointer.get(0);
            try {
                description.clear();
                checkHresult(
                        JNI.invokePPI(
                                output,
                                MemoryUtil.memAddress(description),
                                vtable(output, 7)),
                        "IDXGIOutput::GetDesc");
                String outputName = utf16(description, 0, 32);
                if (requestedDisplayName.equalsIgnoreCase(outputName)) {
                    return queryOutput6(output, outputName, stack, pointer);
                }
            } finally {
                release(output);
            }
        }
    }

    private static Snapshot queryOutput6(
            long output,
            String displayName,
            MemoryStack stack,
            PointerBuffer pointer) {
        ByteBuffer output6Iid = guid(
                stack,
                0x068346e8,
                0xaaec,
                0x4b84,
                0xad, 0xd7, 0x13, 0x7f, 0x51, 0x3f, 0x77, 0xa1);
        pointer.put(0, MemoryUtil.NULL);
        int queryResult = JNI.invokePPPI(
                output,
                MemoryUtil.memAddress(output6Iid),
                MemoryUtil.memAddress(pointer),
                vtable(output, 0));
        if (queryResult < 0) {
            return Snapshot.UNAVAILABLE;
        }
        long output6 = pointer.get(0);
        try {
            ByteBuffer description = stack.calloc(OUTPUT_DESC1_SIZE)
                    .order(ByteOrder.nativeOrder());
            checkHresult(
                    JNI.invokePPI(
                            output6,
                            MemoryUtil.memAddress(description),
                            vtable(output6, 27)),
                    "IDXGIOutput6::GetDesc1");
            int colorSpace = description.getInt(OUTPUT_DESC1_COLOR_SPACE_OFFSET);
            boolean hdrActive = colorSpace == DXGI_COLOR_SPACE_RGB_FULL_G10_NONE_P709
                    || colorSpace == DXGI_COLOR_SPACE_RGB_FULL_G2084_NONE_P2020;
            if (!hdrActive) {
                return new Snapshot(true, false, 0.0F, 0.0F);
            }
            float maximumNits = description.getFloat(
                    OUTPUT_DESC1_MAX_LUMINANCE_OFFSET);
            float sdrWhiteNits = sdrWhiteNits(displayName);
            if (!Float.isFinite(maximumNits)
                    || maximumNits <= 0.0F
                    || !Float.isFinite(sdrWhiteNits)
                    || sdrWhiteNits <= 0.0F) {
                return Snapshot.UNAVAILABLE;
            }
            return new Snapshot(true, true, maximumNits, sdrWhiteNits);
        } finally {
            release(output6);
        }
    }

    private static float sdrWhiteNits(String displayName) {
        try (SharedLibrary user32 = Library.loadNative(
                WindowsHdrDisplay.class, "prime", "user32")) {
            long getSizes = requireFunction(user32, "GetDisplayConfigBufferSizes");
            long query = requireFunction(user32, "QueryDisplayConfig");
            long getInfo = requireFunction(user32, "DisplayConfigGetDeviceInfo");
            try (MemoryStack stack = MemoryStack.stackPush()) {
                IntBuffer pathCount = stack.callocInt(1);
                IntBuffer modeCount = stack.callocInt(1);
                for (int attempt = 0;
                        attempt < MAXIMUM_DISPLAY_CONFIG_QUERY_ATTEMPTS;
                        attempt++) {
                    pathCount.put(0, 0);
                    modeCount.put(0, 0);
                    if (JNI.invokePPI(
                            QDC_ONLY_ACTIVE_PATHS,
                            MemoryUtil.memAddress(pathCount),
                            MemoryUtil.memAddress(modeCount),
                            getSizes) != 0) {
                        return Float.NaN;
                    }
                    int paths = pathCount.get(0);
                    int modes = modeCount.get(0);
                    if (paths <= 0 || modes <= 0) {
                        return Float.NaN;
                    }
                    ByteBuffer pathInfo = stack.calloc(
                                    Math.multiplyExact(paths, PATH_INFO_SIZE))
                            .order(ByteOrder.nativeOrder());
                    ByteBuffer modeInfo = stack.calloc(
                                    Math.multiplyExact(modes, MODE_INFO_SIZE))
                            .order(ByteOrder.nativeOrder());
                    int queryResult = JNI.invokePPPPPI(
                            QDC_ONLY_ACTIVE_PATHS,
                            MemoryUtil.memAddress(pathCount),
                            MemoryUtil.memAddress(pathInfo),
                            MemoryUtil.memAddress(modeCount),
                            MemoryUtil.memAddress(modeInfo),
                            MemoryUtil.NULL,
                            query);
                    if (queryResult == ERROR_INSUFFICIENT_BUFFER) {
                        continue;
                    }
                    if (queryResult != 0) {
                        return Float.NaN;
                    }
                    for (int index = 0; index < pathCount.get(0); index++) {
                        int pathOffset = index * PATH_INFO_SIZE;
                        ByteBuffer sourceName = stack.calloc(SOURCE_NAME_SIZE)
                                .order(ByteOrder.nativeOrder());
                        sourceName.putInt(0, DISPLAYCONFIG_GET_SOURCE_NAME);
                        sourceName.putInt(4, SOURCE_NAME_SIZE);
                        copyAdapterAndId(pathInfo, pathOffset, sourceName);
                        if (JNI.invokePI(MemoryUtil.memAddress(sourceName), getInfo) != 0
                                || !displayName.equalsIgnoreCase(
                                        utf16(sourceName, 20, 32))) {
                            continue;
                        }
                        ByteBuffer whiteLevel = stack.calloc(SDR_WHITE_LEVEL_SIZE)
                                .order(ByteOrder.nativeOrder());
                        whiteLevel.putInt(0, DISPLAYCONFIG_GET_SDR_WHITE_LEVEL);
                        whiteLevel.putInt(4, SDR_WHITE_LEVEL_SIZE);
                        copyAdapterAndId(
                                pathInfo, pathOffset + TARGET_INFO_OFFSET, whiteLevel);
                        if (JNI.invokePI(MemoryUtil.memAddress(whiteLevel), getInfo) == 0) {
                            long encoded = Integer.toUnsignedLong(whiteLevel.getInt(20));
                            float nits = encoded * 80.0F / 1000.0F;
                            if (Float.isFinite(nits) && nits > 0.0F) {
                                return nits;
                            }
                        }
                    }
                    return Float.NaN;
                }
            }
        }
        return Float.NaN;
    }

    private static void copyAdapterAndId(
            ByteBuffer source,
            int sourceOffset,
            ByteBuffer destination) {
        destination.putLong(8, source.getLong(sourceOffset));
        destination.putInt(16, source.getInt(sourceOffset + 8));
    }

    private static ByteBuffer guid(
            MemoryStack stack,
            int data1,
            int data2,
            int data3,
            int... data4) {
        if (data4.length != 8) {
            throw new IllegalArgumentException("A GUID must contain eight Data4 bytes");
        }
        ByteBuffer result = stack.malloc(16).order(ByteOrder.LITTLE_ENDIAN);
        result.putInt(0, data1);
        result.putShort(4, (short) data2);
        result.putShort(6, (short) data3);
        for (int index = 0; index < data4.length; index++) {
            result.put(8 + index, (byte) data4[index]);
        }
        return result;
    }

    private static String utf16(ByteBuffer source, int byteOffset, int capacity) {
        StringBuilder value = new StringBuilder(capacity);
        for (int index = 0; index < capacity; index++) {
            char character = source.getChar(byteOffset + index * Character.BYTES);
            if (character == '\0') {
                break;
            }
            value.append(character);
        }
        return value.toString();
    }

    private static long requireFunction(SharedLibrary library, String name) {
        long function = library.getFunctionAddress(name);
        if (function == MemoryUtil.NULL) {
            throw new IllegalStateException("Missing Windows function " + name);
        }
        return function;
    }

    private static long vtable(long instance, int index) {
        long table = MemoryUtil.memGetAddress(instance);
        return MemoryUtil.memGetAddress(
                table + (long) index * Pointer.POINTER_SIZE);
    }

    private static void release(long instance) {
        if (instance != MemoryUtil.NULL) {
            JNI.invokePI(instance, vtable(instance, 2));
        }
    }

    private static void checkHresult(int result, String operation) {
        if (result < 0) {
            throw new IllegalStateException(
                    operation + " failed with HRESULT 0x" + Integer.toHexString(result));
        }
    }

    public record Snapshot(
            boolean available,
            boolean hdrActive,
            float maximumNits,
            float sdrWhiteNits) {
        private static final Snapshot UNAVAILABLE =
                new Snapshot(false, false, 0.0F, 0.0F);

        public Snapshot {
            if (!Float.isFinite(maximumNits)
                    || !Float.isFinite(sdrWhiteNits)
                    || hdrActive && (!available
                            || maximumNits <= 0.0F
                            || sdrWhiteNits <= 0.0F)
                    || !hdrActive && (maximumNits != 0.0F
                            || sdrWhiteNits != 0.0F)) {
                throw new IllegalArgumentException("Invalid Windows HDR display snapshot");
            }
        }
    }
}
