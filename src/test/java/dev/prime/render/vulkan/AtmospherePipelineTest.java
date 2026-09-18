// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

package dev.prime.render.vulkan;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.prime.render.AtmosphereCoordinates;
import dev.prime.render.AtmosphereSettings;
import dev.prime.render.shader.ShaderAbi;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.Test;

final class AtmospherePipelineTest {
    @Test
    void scaledMediumMatchesIndependentSkyTracerModelsIncludingZeroAndMaximum() throws Exception {
        try (InputStream encoded = getClass().getResourceAsStream(
                        "/prime/atmosphere/medium-scale-reference.bin.gz.b64");
                InputStream decoded = Base64.getMimeDecoder().wrap(encoded);
                GZIPInputStream decompressed = new GZIPInputStream(decoded)) {
            ByteBuffer expected = ByteBuffer.wrap(decompressed.readAllBytes()).order(ByteOrder.LITTLE_ENDIAN);
            for (int steps : new int[] {0, 50, AtmosphereSettings.MAXIMUM_STEPS}) {
                ByteBuffer actual = ByteBuffer.wrap(AtmosphereMedium.load(steps))
                        .order(ByteOrder.LITTLE_ENDIAN);
                while (actual.hasRemaining()) {
                    float value = expected.getFloat();
                    assertEquals(value, actual.getFloat(), Math.max(1e-12F, Math.abs(value) * 2e-6F),
                            "source coefficient at scale " + AtmosphereSettings.densityScale(steps)
                                    + " byte " + (actual.position() - 4));
                }
            }
            assertEquals(0, expected.remaining());
        }
    }

    @Test
    void exportedPhysicalMediumAndQuadratureAreExact() throws Exception {
        byte[] bytes;
        try (InputStream encoded = AtmospherePipelineTest.class.getResourceAsStream(
                        "/prime/atmosphere/medium.bin.gz.b64");
                InputStream decoded = Base64.getMimeDecoder().wrap(encoded);
                GZIPInputStream decompressed = new GZIPInputStream(decoded)) {
            bytes = decompressed.readAllBytes();
        }
        assertEquals(199_584, bytes.length);
        assertEquals(
                "be1ae66c600c6df21ea730cd24b10bb88b9f6dfa00200539a4cc65420c7aefb5",
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
    }

    @Test
    void bedrockIsTheZeroDatumAndOffsetUsesPhysicalMeters() {
        for (int offset : new int[] {0, 300, 10_000}) {
            var settings = new AtmosphereSettings(100, offset);
            for (double y : new double[] {-64, -63, 64, 320}) {
                float expected = (float) ((y + 64 + offset) / 1000.0);
                assertEquals(expected, AtmosphereCoordinates.worldAltitudeKm(y, settings), 1e-6F);
                assertEquals(6360.0F + expected, AtmosphereCoordinates.eyeRadiusKm(y, settings));
            }
        }
        assertEquals(6360.0F, AtmosphereCoordinates.eyeRadiusKm(-64, new AtmosphereSettings(100, 0)));
        assertEquals(0.3F, AtmosphereCoordinates.worldAltitudeKm(-64, AtmosphereSettings.defaults()));
        assertEquals(0.428F, AtmosphereCoordinates.worldAltitudeKm(64, AtmosphereSettings.defaults()));
    }

    @Test
    void atmosphereRadiusNeverLeavesTheLutShell() {
        float shellMargin = ShaderAbi.ATMOSPHERE_WORLD_UNIT_SCALE_KM;
        assertEquals(
                ShaderAbi.ATMOSPHERE_BOTTOM_RADIUS_KM,
                AtmosphereCoordinates.eyeRadiusKm(-1.0e9, AtmosphereSettings.defaults()),
                0.001F);
        assertEquals(
                ShaderAbi.ATMOSPHERE_TOP_RADIUS_KM - shellMargin,
                AtmosphereCoordinates.eyeRadiusKm(1.0e9, AtmosphereSettings.defaults()),
                0.001F);
    }
}
