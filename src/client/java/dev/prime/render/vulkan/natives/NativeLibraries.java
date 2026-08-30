package dev.prime.render.vulkan.natives;

import net.fabricmc.loader.api.FabricLoader;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.system.SharedLibrary;

import java.nio.file.Path;
import java.util.Locale;

public final class NativeLibraries {
    private static final String EXTRACTED_PATH_PROPERTY = "prime.native.libraryDirectory";
    public static final String BUNDLED_NATIVE_PATH = "/prime/natives/";


    public static final NativeLibrary NATIVE_DLSSRR_BRIDGE;
    public static final NativeLibrary NATIVE_DLSSRR_FEATURE;
    public static final NativeLibrary NATIVE_NRD;
    public static final NativeLibrary NATIVE_FFXFSR;

    public static final NativeLibrary NATIVE_STREAMLINE_COMMON;
    public static final NativeLibrary NATIVE_STREAMLINE_INTERPOSER;
    public static final NativeLibrary NATIVE_STREAMLINE_PCL;
    public static final NativeLibrary NATIVE_STREAMLINE_REFLEX;
    public static final NativeLibrary NATIVE_STREAMLINE_DLSSG;
    public static final NativeLibrary NATIVE_STREAMLINE_DLSSG_FEATURE;
    public static final NativeLibrary NATIVE_STREAMLINE_LOWLATENCY_FEATURE;


    static {
        if (isWindowsX64()) {
            NATIVE_DLSSRR_BRIDGE = createLibrary("prime_dlss_rr.dll", "Prime DLSS RR Bridge");
            NATIVE_DLSSRR_FEATURE = createLibrary("nvngx_dlssd.dll", "DLSS RR Feature");
            NATIVE_NRD = createLibrary("prime_nrd.dll", "Prime NRD Library");
            NATIVE_FFXFSR = createLibrary("amd_fidelityfx_vk.dll", "FidelityFX Library");
            NATIVE_STREAMLINE_COMMON = createLibrary("sl.common.dll", "Streamline SDK Common");
            NATIVE_STREAMLINE_INTERPOSER = createLibrary("sl.interposer.dll", "Streamline SDK Interposer");
            NATIVE_STREAMLINE_PCL = createLibrary("sl.pcl.dll", "Streamline SDK PCL Plugin");
            NATIVE_STREAMLINE_REFLEX = createLibrary("sl.reflex.dll", "Streamline SDK Reflex Plugin");
            NATIVE_STREAMLINE_DLSSG = createLibrary("sl.dlss_g.dll", "Streamline SDK DLSS-FG Plugin");
            NATIVE_STREAMLINE_DLSSG_FEATURE = createLibrary("nvngx_dlssg.dll", "DLSS FG Feature");
            NATIVE_STREAMLINE_LOWLATENCY_FEATURE = createLibrary("NvLowLatencyVk.dll", "Reflex Low Latency Feature");
        } else {
            // TODO
            NATIVE_DLSSRR_BRIDGE = null;
            NATIVE_DLSSRR_FEATURE = null;
            NATIVE_NRD = null;
            NATIVE_FFXFSR = null;
            NATIVE_STREAMLINE_COMMON = null;
            NATIVE_STREAMLINE_INTERPOSER = null;
            NATIVE_STREAMLINE_PCL = null;
            NATIVE_STREAMLINE_REFLEX = null;
            NATIVE_STREAMLINE_DLSSG = null;
            NATIVE_STREAMLINE_DLSSG_FEATURE = null;
            NATIVE_STREAMLINE_LOWLATENCY_FEATURE = null;
        }
    }

    private NativeLibraries() {

    }

    public static Path extractedNativePath() {
        String override = System.getProperty(EXTRACTED_PATH_PROPERTY);
        if (override != null && !override.isBlank()) {
            return Path.of(override).toAbsolutePath();
        }
        return FabricLoader.getInstance()
                .getConfigDir()
                .resolve("prime")
                .resolve("libraries");
    }

    private static NativeLibrary createLibrary(
            String fileName,
            String label
    ) {
        return new NativeLibrary(
                fileName,
                NativeLibraries::extractedNativePath,
                getBundledNativePath() + fileName,
                label
        );
    }

    public static String getBundledNativePath() {
        if (isWindowsX64()) {
            return BUNDLED_NATIVE_PATH + "windows-x86_64/";
        } else {
            return BUNDLED_NATIVE_PATH + "linux-x86_64/";
        }
    }

    public static boolean isWindowsX64() {
        return isWindowsX64(
                System.getProperty("os.name", ""),
                System.getProperty("os.arch", ""));
    }

    public static boolean isWindowsX64(String osName, String architecture) {
        String os = osName.toLowerCase(Locale.ROOT);
        String arch = architecture.toLowerCase(Locale.ROOT);
        return os.startsWith("windows")
                && (arch.equals("amd64") || arch.equals("x86_64"));
    }

    public static long requireFunction(
            SharedLibrary library, String functionName, String libraryName) {
        long address = library.getFunctionAddress(functionName);
        if (address == MemoryUtil.NULL) {
            throw new IllegalStateException(
                    libraryName + " is missing " + functionName);
        }
        return address;
    }

    public static void checkResult(int result, String operation) {
        if (result != 0) {
            throw new IllegalStateException(operation + " failed with native result " + result);
        }
    }
}
