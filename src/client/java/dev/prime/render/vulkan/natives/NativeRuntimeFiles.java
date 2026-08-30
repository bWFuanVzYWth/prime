package dev.prime.render.vulkan.natives;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Objects;

/** Publishes verified native runtime files without exposing partial writes. */
public final class NativeRuntimeFiles {
    private static final int BUFFER_SIZE = 8192;

    private NativeRuntimeFiles() {}

    @FunctionalInterface
    public interface Source {
        InputStream open() throws IOException;
    }

    public static void publish(Path target, Source source) {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(source, "source");
        Path parent = target.getParent();
        if (parent == null) {
            throw new IllegalArgumentException(
                    "Native runtime target must have a parent directory");
        }

        Path temporary = null;
        RuntimeException failure = null;
        try {
            Files.createDirectories(parent);
            if (matches(target, source)) {
                return;
            }
            temporary = Files.createTempFile(
                    parent, target.getFileName().toString() + "-", ".tmp");
            try (InputStream input = requireInput(source)) {
                Files.copy(input, temporary, StandardCopyOption.REPLACE_EXISTING);
            }
            if (!matches(temporary, source)) {
                throw new IOException("temporary contents do not match the bundled runtime");
            }
            moveOrAcceptVerifiedTarget(temporary, target, source);
            temporary = null;
            if (!matches(target, source)) {
                throw new IOException("published contents do not match the bundled runtime");
            }
        } catch (IOException exception) {
            failure = new IllegalStateException(
                    "Unable to publish native runtime " + target, exception);
            throw failure;
        } catch (RuntimeException exception) {
            failure = exception;
            throw exception;
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException exception) {
                    if (failure != null) {
                        failure.addSuppressed(exception);
                    } else {
                        throw new IllegalStateException(
                                "Unable to remove temporary native runtime " + temporary,
                                exception);
                    }
                }
            }
        }
    }

    private static void moveOrAcceptVerifiedTarget(
            Path temporary, Path target, Source source) throws IOException {
        try {
            try {
                Files.move(
                        temporary,
                        target,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException exception) {
            // Windows denies replacement while another publisher verifies the target. If that
            // publisher already installed the same complete payload, this publication is done.
            if (!matches(target, source)) {
                throw exception;
            }
            Files.deleteIfExists(temporary);
        }
    }

    private static boolean matches(Path target, Source source) throws IOException {
        if (!Files.isRegularFile(target)) {
            return false;
        }
        try (InputStream expected = requireInput(source);
                InputStream actual = Files.newInputStream(target)) {
            byte[] expectedBuffer = new byte[BUFFER_SIZE];
            byte[] actualBuffer = new byte[BUFFER_SIZE];
            while (true) {
                int expectedCount = expected.readNBytes(expectedBuffer, 0, BUFFER_SIZE);
                int actualCount = actual.readNBytes(actualBuffer, 0, BUFFER_SIZE);
                if (expectedCount != actualCount) {
                    return false;
                }
                for (int index = 0; index < expectedCount; index++) {
                    if (expectedBuffer[index] != actualBuffer[index]) {
                        return false;
                    }
                }
                if (expectedCount == 0) {
                    return true;
                }
            }
        }
    }

    private static InputStream requireInput(Source source) throws IOException {
        InputStream input = source.open();
        if (input == null) {
            throw new IOException("Native runtime source returned no data");
        }
        return input;
    }
}
