// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

package dev.prime.render.vulkan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.prime.render.shader.ShaderAbi;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;

final class RealtimeStbnTableTest {
    @Test
    void resourceMatchesThePropertyValidatedArtifact() throws Exception {
        byte[] bytes;
        try (InputStream input = RealtimeStbnTableTest.class.getResourceAsStream(
                RealtimeStbnTable.RESOURCE)) {
            assertTrue(input != null, "missing realtime STBN resource");
            bytes = input.readAllBytes();
        }
        assertEquals(RealtimeStbnTable.BYTE_SIZE, bytes.length);
        assertEquals(
                ShaderAbi.REALTIME_STBN_RESOURCE_SHA256,
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
    }
}
