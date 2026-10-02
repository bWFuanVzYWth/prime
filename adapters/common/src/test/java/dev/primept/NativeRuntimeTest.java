package dev.primept;

import static org.junit.jupiter.api.Assertions.*;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class NativeRuntimeTest {
    @Test
    void extractsAndVerifiesAllPluginsInOneReusableBundle(@TempDir Path root) throws Exception {
        var resources = resources();
        Path engine = extract(root, resources);
        for (String name : NativeRuntime.WINDOWS_FILES)
            assertArrayEquals(resources.get(name), Files.readAllBytes(engine.resolveSibling(name)));
        assertEquals(engine, extract(root, resources));
        resources.put("nvngx_dlssd.dll", new byte[] {1, 2, 3});
        Path updated = extract(root, resources);
        assertNotEquals(engine.getParent(), updated.getParent());
        assertArrayEquals(resources.get("prime_engine.dll"), Files.readAllBytes(engine));
        assertArrayEquals(new byte[] {1, 2, 3},
                          Files.readAllBytes(updated.resolveSibling("nvngx_dlssd.dll")));
        try (var files = Files.list(updated.getParent())) {
            assertEquals(NativeRuntime.WINDOWS_FILES.size(), files.count());
        }
    }
    @Test
    void corruptCachedPluginAndMissingResourceFailExplicitly(@TempDir Path root) throws Exception {
        var resources = resources();
        Path engine = extract(root, resources);
        Files.write(engine.resolveSibling("sl.common.dll"), new byte[] {0});
        IOException corrupt = assertThrows(IOException.class, () -> extract(root, resources));
        assertTrue(corrupt.getMessage().contains("sl.common.dll"));
        resources.remove("sl.dlss_d.dll");
        IOException missing = assertThrows(IOException.class, () -> extract(root, resources));
        assertTrue(missing.getMessage().contains("sl.dlss_d.dll"));
    }
    private static Map<String, byte[]> resources() {
        var resources = new HashMap<String, byte[]>();
        for (String name : NativeRuntime.WINDOWS_FILES)
            resources.put(name, (name + " payload").getBytes(StandardCharsets.UTF_8));
        return resources;
    }
    private static Path extract(Path root, Map<String, byte[]> resources) throws IOException {
        return NativeRuntime.extract(root, "windows-x86_64", NativeRuntime.WINDOWS_FILES, name -> {
            byte[] contents = resources.get(name);
            return contents == null ? null : new ByteArrayInputStream(contents);
        });
    }
}
