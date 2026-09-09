// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

package dev.prime.render.vulkan;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.prime.render.AtmosphereCoordinates;
import dev.prime.render.shader.ShaderAbi;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.Test;

final class AtmospherePipelineTest {
    @Test
    void measuredFourSpeciesPhaseTableIsExact() throws Exception {
        byte[] bytes;
        try (InputStream encoded = AtmospherePipelineTest.class.getResourceAsStream(
                        "/prime/atmosphere/phase_lut.bin.gz.b64");
                InputStream decoded = Base64.getMimeDecoder().wrap(encoded);
                GZIPInputStream decompressed = new GZIPInputStream(decoded)) {
            bytes = decompressed.readAllBytes();
        }
        assertEquals(131_072, bytes.length);
        assertEquals(
                "43a6b9b1be8c690d6ab58f247a61ae753009f7f8fa99c51962ac2c59aec1d37b",
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
    }

    @Test
    void minecraftBuildRangeUsesInternalAtmosphereScale() {
        float scale =
                ShaderAbi.ATMOSPHERE_WORLD_TO_ATMOSPHERE_SCALE;
        assertEquals(0.0F, AtmosphereCoordinates.worldAltitudeKm(-128.0));
        assertEquals(0.064F * scale, AtmosphereCoordinates.worldAltitudeKm(-64.0));
        assertEquals(0.448F * scale, AtmosphereCoordinates.worldAltitudeKm(320.0));
        // The one-block radius offset keeps ray/sphere tests numerically outside the ground while
        // the conceptual virtual-ground altitude remains exactly zero.
        assertEquals(
                ShaderAbi.ATMOSPHERE_BOTTOM_RADIUS_KM + 0.001F * scale,
                AtmosphereCoordinates.eyeRadiusKm(-128.0),
                0.001F);
        assertEquals(
                ShaderAbi.ATMOSPHERE_BOTTOM_RADIUS_KM + 0.064F * scale,
                AtmosphereCoordinates.eyeRadiusKm(-64.0),
                0.001F);
        assertEquals(
                ShaderAbi.ATMOSPHERE_BOTTOM_RADIUS_KM + 0.448F * scale,
                AtmosphereCoordinates.eyeRadiusKm(320.0),
                0.001F);
    }

    @Test
    void atmosphereRadiusNeverLeavesTheLutShell() {
        float shellMargin = ShaderAbi.ATMOSPHERE_WORLD_UNIT_SCALE_KM;
        assertEquals(
                ShaderAbi.ATMOSPHERE_BOTTOM_RADIUS_KM + shellMargin,
                AtmosphereCoordinates.eyeRadiusKm(-1.0e9),
                0.001F);
        assertEquals(
                ShaderAbi.ATMOSPHERE_TOP_RADIUS_KM - shellMargin,
                AtmosphereCoordinates.eyeRadiusKm(1.0e9),
                0.001F);
    }
}
