// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

package dev.prime.render.vulkan.natives;

import net.fabricmc.loader.api.FabricLoader;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.system.SharedLibrary;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Function;

public final class NativeLibraries {
    private static final String EXTRACTED_PATH_PROPERTY = "prime.native.libraryDirectory";
    public static final String BUNDLED_NATIVE_PATH = "/prime/natives/";
    public static final NativeLibrary NATIVE_DLSSRR_BRIDGE =
            createLibrary("prime_dlss_rr.dll", "Prime DLSS RR Bridge");
    public static final NativeLibrary NATIVE_DLSSRR_FEATURE =
            createLibrary("nvngx_dlssd.dll", "DLSS RR Feature");
    public static final NativeLibrary NATIVE_NRD =
            createLibrary("prime_nrd.dll", "Prime NRD Library");
    public static final NativeLibrary NATIVE_FFXFSR =
            createLibrary("amd_fidelityfx_vk.dll", "FidelityFX Library");
    public static final NativeLibrary NATIVE_STREAMLINE_COMMON =
            createLibrary("sl.common.dll", "Streamline SDK Common");
    public static final NativeLibrary NATIVE_STREAMLINE_INTERPOSER =
            createLibrary("sl.interposer.dll", "Streamline SDK Interposer");
    public static final NativeLibrary NATIVE_STREAMLINE_PCL =
            createLibrary("sl.pcl.dll", "Streamline SDK PCL Plugin");
    public static final NativeLibrary NATIVE_STREAMLINE_REFLEX =
            createLibrary("sl.reflex.dll", "Streamline SDK Reflex Plugin");
    public static final NativeLibrary NATIVE_STREAMLINE_DLSSG =
            createLibrary("sl.dlss_g.dll", "Streamline SDK DLSS-FG Plugin");
    public static final NativeLibrary NATIVE_STREAMLINE_DLSSG_FEATURE =
            createLibrary("nvngx_dlssg.dll", "DLSS FG Feature");
    public static final NativeLibrary NATIVE_STREAMLINE_LOWLATENCY_FEATURE =
            createLibrary("NvLowLatencyVk.dll", "Reflex Low Latency Feature");
    private static final List<NativeLibrary> WINDOWS_RUNTIME = List.of(
            NATIVE_DLSSRR_BRIDGE,
            NATIVE_DLSSRR_FEATURE,
            NATIVE_NRD,
            NATIVE_FFXFSR,
            NATIVE_STREAMLINE_COMMON,
            NATIVE_STREAMLINE_INTERPOSER,
            NATIVE_STREAMLINE_PCL,
            NATIVE_STREAMLINE_REFLEX,
            NATIVE_STREAMLINE_DLSSG,
            NATIVE_STREAMLINE_DLSSG_FEATURE,
            NATIVE_STREAMLINE_LOWLATENCY_FEATURE);

    private NativeLibraries() {

    }

    public static Path extractedNativePath() {
        String override = System.getProperty(EXTRACTED_PATH_PROPERTY);
        if (override != null && !override.isBlank()) {
            return Path.of(override).toAbsolutePath();
        }
        return DefaultRuntimePath.VALUE;
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

    static Path contentAddressedDirectory(
            Path root,
            String namespace,
            List<String> names,
            Function<String, InputStream> open) {
        Objects.requireNonNull(root, "root");
        Objects.requireNonNull(namespace, "namespace");
        Objects.requireNonNull(names, "names");
        Objects.requireNonNull(open, "open");
        MessageDigest bundle = sha256();
        for (String name : names) {
            byte[] encodedName = name.getBytes(StandardCharsets.UTF_8);
            bundle.update((byte) (encodedName.length >>> 24));
            bundle.update((byte) (encodedName.length >>> 16));
            bundle.update((byte) (encodedName.length >>> 8));
            bundle.update((byte) encodedName.length);
            bundle.update(encodedName);
            MessageDigest payload = sha256();
            try (InputStream input = open.apply(name)) {
                if (input == null) {
                    throw new IllegalStateException(
                            "Missing bundled native runtime " + name);
                }
                byte[] buffer = new byte[8192];
                int count;
                while ((count = input.read(buffer)) >= 0) {
                    if (count > 0) {
                        payload.update(buffer, 0, count);
                    }
                }
            } catch (IOException exception) {
                throw new IllegalStateException(
                        "Unable to fingerprint bundled native runtime " + name,
                        exception);
            }
            bundle.update(payload.digest());
        }
        return root.resolve(namespace)
                .resolve(HexFormat.of().formatHex(bundle.digest()))
                .toAbsolutePath();
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 digest is unavailable", exception);
        }
    }

    private static final class DefaultRuntimePath {
        private static final Path VALUE = create();

        private static Path create() {
            Path root = FabricLoader.getInstance()
                    .getConfigDir()
                    .resolve("prime")
                    .resolve("libraries");
            if (!isWindowsX64()) {
                return root.resolve("unsupported-platform").toAbsolutePath();
            }
            String resourceRoot = getBundledNativePath();
            List<String> names = WINDOWS_RUNTIME.stream()
                    .map(NativeLibrary::fileName)
                    .toList();
            return contentAddressedDirectory(
                    root,
                    "windows-x86_64",
                    names,
                    name -> NativeLibraries.class.getResourceAsStream(resourceRoot + name));
        }
    }
}
