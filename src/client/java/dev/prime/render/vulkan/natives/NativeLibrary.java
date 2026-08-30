package dev.prime.render.vulkan.natives;

import org.jetbrains.annotations.NotNull;
import org.lwjgl.system.APIUtil;
import org.lwjgl.system.SharedLibrary;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.function.Supplier;

public class NativeLibrary {
    protected final String fileName;
    protected Path extractedPath;
    protected boolean available;
    protected Supplier<Path> targetPath;
    protected String label;
    protected String bundledPath;

    public NativeLibrary(
            @NotNull String fileName,
            @NotNull Supplier<Path> targetPath,
            @NotNull String bundledPath,
            String label
    ) {
        this.fileName = fileName;
        this.targetPath = targetPath;
        this.label = label;
        this.bundledPath = bundledPath;
    }

    private static MessageDigest createSha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 digest is unavailable", e);
        }
    }

    private static String calculateChecksum(InputStream in) throws IOException {
        MessageDigest digest = createSha256Digest();
        byte[] buffer = new byte[8192];
        int length;
        while ((length = in.read(buffer)) != -1) {
            digest.update(buffer, 0, length);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    public boolean isAvailable() {
        return available;
    }

    public SharedLibrary getOrCreateLibrary() {
        tryToExtract();
        return APIUtil.apiCreateLibrary(this.extractedPath.toAbsolutePath().toString());
    }

    public Path tryToExtract() {
        if (isAvailable()) {
            return this.extractedPath;
        }
        Path path = this.targetPath.get().resolve(this.fileName);
        File file = path.toFile();
        if (file.exists()) {
            String bundledChecksum = getBundledChecksum();
            String existsChecksum = getChecksum(path);
            this.extractedPath = path;
            this.available = true;
            if (bundledChecksum.equals(existsChecksum)) {
                return path;
            }
        }
        try {
            Files.createDirectories(path.getParent());
        } catch (FileAlreadyExistsException _) {
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        try (InputStream input = NativeLibrary.class.getResourceAsStream(this.bundledPath)) {
            if (input == null) {
                throw new IllegalStateException("Missing bundled " + label + " " + this.bundledPath);
            }
            Files.copy(input, path, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException exception) {
            throw new RuntimeException("Failed to extract bundled library", exception);
        }
        this.extractedPath = path;
        this.available = true;
        return path;
    }

    private String getChecksum(Path file) {
        try (InputStream input = Files.newInputStream(file)) {
            return calculateChecksum(input);
        } catch (IOException exception) {
            return null;
        }
    }

    private String getBundledChecksum() {
        try (InputStream input = NativeLibrary.class.getResourceAsStream(bundledPath)) {
            if (input == null) {
                throw new IllegalStateException("Missing bundled " + label + " " + bundledPath);
            }
            return calculateChecksum(input);
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to calculate bundled library checksum" + label, exception);
        }
    }

    public Path getPath() {
        return this.extractedPath;
    }
}
