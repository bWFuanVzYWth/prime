// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

package dev.prime.render.shader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.Test;

final class StarmapBc6hGpuTest extends GpuShaderTest {
    @Test
    void packagedBlocksMatchIndependentDecoderAcrossStripesAndFilterInLinearLight() throws Exception {
        int width = ShaderAbi.STARMAP_WIDTH;
        int height = ShaderAbi.STARMAP_HEIGHT;
        ByteBuffer blocks = ByteBuffer.allocateDirect(width * height).order(ByteOrder.LITTLE_ENDIAN);
        for (int stripe = 0; stripe < height / ShaderAbi.STARMAP_STRIPE_ROWS; stripe++) {
            String name = "/prime/starmap/starmap_2020_16k_" + stripe + ".bc6h.gz";
            try (var resource = getClass().getResourceAsStream(name)) {
                assertNotNull(resource, name);
                try (var gzip = new GZIPInputStream(resource)) {
                    byte[] data = gzip.readAllBytes();
                    assertEquals(width * ShaderAbi.STARMAP_STRIPE_ROWS, data.length);
                    blocks.put(data);
                }
            }
        }
        blocks.flip();
        runner.bindSampledImage(2, ShaderComputeRunner.ImageDimension.TWO_D,
                ShaderComputeRunner.ImageFormat.BC6H_UFLOAT_BLOCK, blocks, width, height, 1);
        try (var resource = getClass().getResourceAsStream("/prime/starmap_2020_16k_samples.json")) {
            assertNotNull(resource);
            var fixture = new Gson().fromJson(new InputStreamReader(resource, StandardCharsets.UTF_8),
                    JsonObject.class);
            assertEquals(ShaderAbi.STARMAP_SOURCE_SHA256, fixture.get("sourceSha256").getAsString());
            var samples = fixture.getAsJsonArray("samples");
            ByteBuffer input = ByteBuffer.allocateDirect(samples.size() * 8).order(ByteOrder.LITTLE_ENDIAN);
            for (var value : samples) {
                var sample = value.getAsJsonObject();
                input.putInt(sample.get("x").getAsInt()).putInt(sample.get("y").getAsInt());
            }
            input.flip();
            ByteBuffer output = runner.dispatch("starmap_bc6h.comp.spv", input, samples.size() * 32,
                    new ShaderComputeRunner.Workgroups(samples.size(), 1, 1), null);
            for (int i = 0; i < samples.size(); i++) {
                var sample = samples.get(i).getAsJsonObject();
                for (int c = 0; c < 3; c++) {
                    assertEquals(sample.getAsJsonArray("rgb").get(c).getAsFloat(),
                            output.getFloat(i * 32 + c * 4), 1.0e-7F, "BC6H texel " + i);
                    float filtered = sample.getAsJsonArray("filteredRgb").get(c).getAsFloat();
                    // BC6H filtering can round to source-half precision; texel decode stays exact above.
                    float halfUlp = Math.scalb(1.0F, Math.max(-14, Math.getExponent(filtered)) - 10);
                    assertEquals(filtered, output.getFloat(i * 32 + 16 + c * 4),
                            halfUlp, "BC6H filtering " + i);
                }
                assertEquals(1.0F, output.getFloat(i * 32 + 12), 0.0F);
            }
        }
    }
}
