package dev.prime.render.vulkan.natives;

import java.io.InputStream;
import java.nio.file.Path;
import java.util.Objects;
import java.util.function.Supplier;
import org.jetbrains.annotations.NotNull;
import org.lwjgl.system.APIUtil;
import org.lwjgl.system.SharedLibrary;

/** One bundled native library published into a shared, content-addressed runtime directory. */
public final class NativeLibrary {
    private final String fileName;
    private final Supplier<Path> targetPath;
    private final String bundledPath;
    private final String label;

    public NativeLibrary(
            @NotNull String fileName,
            @NotNull Supplier<Path> targetPath,
            @NotNull String bundledPath,
            String label) {
        this.fileName = Objects.requireNonNull(fileName, "fileName");
        this.targetPath = Objects.requireNonNull(targetPath, "targetPath");
        this.bundledPath = Objects.requireNonNull(bundledPath, "bundledPath");
        this.label = Objects.requireNonNull(label, "label");
    }

    public SharedLibrary getOrCreateLibrary() {
        Path path = tryToExtract();
        return APIUtil.apiCreateLibrary(path.toAbsolutePath().toString());
    }

    public Path tryToExtract() {
        Path directory = Objects.requireNonNull(
                this.targetPath.get(), "Native runtime target directory");
        Path target = directory.resolve(this.fileName);
        NativeRuntimeFiles.publish(target, this::openBundled);
        return target;
    }

    private InputStream openBundled() {
        InputStream input = NativeLibrary.class.getResourceAsStream(this.bundledPath);
        if (input == null) {
            throw new IllegalStateException(
                    "Missing bundled " + this.label + " " + this.bundledPath);
        }
        return input;
    }
}
