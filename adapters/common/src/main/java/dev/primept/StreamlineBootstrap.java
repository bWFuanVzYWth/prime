package dev.primept;

import dev.primept.abi.PrimeAbi;
import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.SymbolLookup;
import java.nio.file.Files;
import java.util.Locale;
import java.util.function.Consumer;
import java.lang.invoke.MethodHandle;

/** Process-owned SDK initialization before Minecraft creates any Vulkan objects. */
public final class StreamlineBootstrap {
    private static boolean installed;
    private static MethodHandle frame;

    private StreamlineBootstrap() {}

    public static boolean installed() {
        return installed;
    }

    public static void install(Consumer<String> setVulkanLoader) throws IOException {
        if (installed || !StartupOptions.enabled() ||
            !System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows"))
            return;
        var library = NativeBridge.resolveLibrary().toAbsolutePath();
        var interposer = library.resolveSibling("sl.interposer.dll");
        if (!Files.isRegularFile(interposer))
            throw new IOException("Missing bundled Streamline Vulkan interposer: " + interposer);
        try {
            var lookup = SymbolLookup.libraryLookup(library, Arena.global());
            var abi = PrimeAbi.bind(lookup, "prime_abi_version");
            if ((int)abi.invokeExact() != PrimeAbi.PRIME_ABI_VERSION)
                throw new IllegalStateException("Native ABI version mismatch during SDK bootstrap");
            var bootstrap = PrimeAbi.bind(lookup, "prime_streamline_bootstrap");
            int status = (int)bootstrap.invokeExact();
            if (status != 0)
                throw new IllegalStateException("Early Streamline initialization failed: " +
                                                status);
            setVulkanLoader.accept(interposer.toString());
            frame = PrimeAbi.bind(lookup, "prime_streamline_frame");
            installed = true;
        } catch (RuntimeException | Error failure) {
            throw failure;
        } catch (Throwable failure) {
            throw new IllegalStateException("Cannot initialize the Streamline Vulkan interposer",
                                            failure);
        }
    }

    public static void frame(int action, boolean enabled) {
        if (!installed)
            return;
        try {
            int status = (int)frame.invokeExact(action, enabled ? 1 : 0);
            if (status != 0)
                throw new IllegalStateException("Streamline frame lifecycle failed: " + status);
        } catch (RuntimeException | Error failure) {
            throw failure;
        } catch (Throwable failure) {
            throw new IllegalStateException("Cannot advance Streamline frame lifecycle", failure);
        }
    }
}
