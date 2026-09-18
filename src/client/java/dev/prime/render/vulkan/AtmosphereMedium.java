// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

package dev.prime.render.vulkan;

import dev.prime.render.AtmosphereSettings;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Base64;
import java.util.zip.GZIPInputStream;

/** Builds one immutable GPU input from the pinned four-wave physical coefficients. */
public final class AtmosphereMedium {
    // Exported layout shared with atmosphere/parameters.slang, covered by source-generated data.
    private static final int BYTE_SIZE = 199_584;
    private static final int PROFILE_OFFSET = 40;
    private static final int PROFILE_COUNT = 50;
    private static final int SOURCE_OFFSET = 12_234;
    private static final int SOURCE_COUNT = 40;

    private AtmosphereMedium() {}

    public static byte[] load(int aerosolDensitySteps) {
        float scale = AtmosphereSettings.densityScale(aerosolDensitySteps);
        byte[] bytes = read("medium", BYTE_SIZE);
        if (scale == 1.0F) {
            return bytes;
        }
        ByteBuffer data = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        ByteBuffer extinction = ByteBuffer.wrap(read("extinction", PROFILE_COUNT * 32))
                .order(ByteOrder.LITTLE_ENDIAN);
        for (int height = 0; height < PROFILE_COUNT; height++) {
            int profile = (PROFILE_OFFSET + height * 7) * 16;
            for (int lane = 0; lane < 4; lane++) {
                float gas = extinction.getFloat(height * 32 + lane * 4);
                float aerosol = extinction.getFloat(height * 32 + 16 + lane * 4);
                data.putFloat(profile + 16 + lane * 4, gas + scale * aerosol);
            }
            scaleSpecies(data, profile + 3 * 16, scale);
        }
        for (int height = 0; height < SOURCE_COUNT; height++) {
            int source = (SOURCE_OFFSET + height * 6) * 16;
            scaleSpecies(data, source + 16, scale);
            for (int lane = 0; lane < 4; lane++) {
                float sum = data.getFloat(source + lane * 4);
                for (int species = 1; species < 5; species++) {
                    sum += data.getFloat(source + species * 16 + lane * 4);
                }
                data.putFloat(source + 5 * 16 + lane * 4, sum);
            }
        }
        return bytes;
    }

    private static void scaleSpecies(ByteBuffer data, int offset, float scale) {
        for (int component = 0; component < 16; component++) {
            int address = offset + component * 4;
            data.putFloat(address, data.getFloat(address) * scale);
        }
    }

    private static byte[] read(String name, int size) {
        try (InputStream encoded = AtmosphereMedium.class.getResourceAsStream(
                "/prime/atmosphere/" + name + ".bin.gz.b64")) {
            if (encoded == null) {
                throw new IllegalStateException("Missing Prime atmosphere " + name);
            }
            try (InputStream decoded = Base64.getMimeDecoder().wrap(encoded);
                    GZIPInputStream decompressed = new GZIPInputStream(decoded)) {
                byte[] bytes = decompressed.readNBytes(size + 1);
                if (bytes.length != size) {
                    throw new IllegalStateException("Unexpected Prime atmosphere " + name + " size " + bytes.length);
                }
                ByteBuffer values = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
                while (values.hasRemaining()) {
                    if (!Float.isFinite(values.getFloat())) {
                        throw new IllegalStateException("Non-finite Prime atmosphere " + name);
                    }
                }
                return bytes;
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to read Prime atmosphere " + name, exception);
        }
    }
}
