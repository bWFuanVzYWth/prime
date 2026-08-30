package dev.prime.render.vulkan;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.IOException;
import java.io.InputStream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("artifact")
final class NativeBridgeArtifactsTest {
    private static final String ROOT = "/prime/natives/windows-x86_64/";

    @Test
    void everyBundledWindowsRuntimeIsAPeImage() throws IOException {
        for (String name : new String[] {
            "prime_nrd.dll",
            "amd_fidelityfx_vk.dll",
            "prime_dlss_rr.dll",
            "nvngx_dlssd.dll",
            "nvngx_dlssg.dll",
            "sl.common.dll",
            "sl.interposer.dll",
            "sl.pcl.dll",
            "sl.reflex.dll",
            "sl.dlss_g.dll",
            "NvLowLatencyVk.dll"
        }) {
            try (InputStream input = NativeBridgeArtifactsTest.class.getResourceAsStream(ROOT + name)) {
                assertNotNull(input, "missing bundled native runtime " + name);
                assertArrayEquals(
                        new byte[] {(byte) 'M', (byte) 'Z'},
                        input.readNBytes(2),
                        "invalid PE header for " + name);
            }
        }
    }
}
