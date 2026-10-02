package dev.primept;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;

/** Extracts one immutable engine/plugin bundle so native code can load plugins beside the engine. */
final class NativeRuntime {
    static final List<String> WINDOWS_FILES =
            List.of("prime_engine.dll", "sl.interposer.dll", "sl.common.dll", "sl.dlss_d.dll",
                    "nvngx_dlssd.dll");
    @FunctionalInterface
    interface Resource {
        InputStream open(String name) throws IOException;
    }
    private NativeRuntime() {}

    static Path extract(Path root, String platform, List<String> files, Resource resources)
            throws IOException {
        var hashes = new LinkedHashMap<String, byte[]>();
        var bundle = sha256();
        for (String name : files) {
            byte[] digest;
            try (var input = requireResource(resources, name)) {
                digest = digest(input);
            }
            hashes.put(name, digest);
            bundle.update(name.getBytes(StandardCharsets.UTF_8));
            bundle.update((byte)0);
            bundle.update(digest);
        }
        Path directory = root.resolve(platform).resolve(HexFormat.of().formatHex(bundle.digest()));
        Files.createDirectories(directory);
        for (var entry : hashes.entrySet()) {
            Path target = directory.resolve(entry.getKey());
            if (!Files.exists(target)) {
                Path staging = Files.createTempFile(directory, ".extract-", ".tmp");
                try {
                    try (var input = requireResource(resources, entry.getKey())) {
                        Files.copy(input, staging, StandardCopyOption.REPLACE_EXISTING);
                    }
                    try {
                        Files.move(staging, target, StandardCopyOption.ATOMIC_MOVE);
                    } catch (IOException concurrentExtraction) {
                        if (!Files.exists(target))
                            throw concurrentExtraction;
                    }
                } finally {
                    Files.deleteIfExists(staging);
                }
            }
            try (var input = Files.newInputStream(target)) {
                if (!MessageDigest.isEqual(entry.getValue(), digest(input)))
                    throw new IOException("Cached native library hash mismatch: " + target);
            }
        }
        // Windows cannot delete loaded DLLs; content identity safely reuses the complete bundle.
        return directory.resolve(files.getFirst());
    }

    private static InputStream requireResource(Resource resources, String name) throws IOException {
        InputStream input = resources.open(name);
        if (input == null)
            throw new IOException(
                    "Native runtime missing: " + name +
                    "; use nativeJar or -Dprimept.native.path=<absolute library path>");
        return input;
    }
    private static byte[] digest(InputStream input) throws IOException {
        var hash = sha256();
        byte[] buffer = new byte[65536];
        int count;
        while ((count = input.read(buffer)) != -1)
            hash.update(buffer, 0, count);
        return hash.digest();
    }
    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }
}
