// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

package dev.prime.render.shader;

import static org.junit.jupiter.api.Assertions.*;

import dev.prime.render.AstronomySettings;
import dev.prime.render.IntegratorSettings;
import dev.prime.render.TransparentNeeMode;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

final class OpaqueBaseColorGpuTest extends GpuShaderTest {
    private static final int INPUT_WORDS = 2;
    private static final int OUTPUT_WORDS = 6;
    private static final float[] TARGET = {0.24f, 0.46f, 0.78f};

    @BeforeAll
    void bindEnergyTable() throws IOException {
        RoboCuteTestResources.bindTransmissionGgxEnergy(runner);
    }

    @Test
    void fixedPowerIgnoresOpticalParametersAndTheSwitchKeepsGuidesConsistent()
            throws IOException {
        int count = 231 * 65;
        var input = ShaderTestBuffer.inputWriter(count, INPUT_WORDS);
        for (int code = 0; code <= 230; code++) {
            for (int rough = 0; rough <= 64; rough++) {
                int index = code * 65 + rough;
                put(input, index, TARGET, rough / 64.0f, code, 0,
                        (rough & 1) == 0 ? 0 : 0.4f, (rough % 3 + 1) / 3.0f);
            }
        }
        for (boolean enabled : new boolean[] {false, true}) {
            ByteBuffer output = dispatch(input.buffer(), count, enabled);
            for (int index = 0; index < count; index++) {
                for (int c = 0; c < 3; c++) {
                    float base = get(output, index, 0, c);
                    assertEquals(enabled ? Math.pow(TARGET[c], 1.04) : TARGET[c], base, 2e-6);
                    assertEquals(get(output, 0, 0, c), base,
                            "Roughness and F0 must not change the color mapping");
                    assertTrue(Float.isFinite(base) && base >= 0 && base <= TARGET[c]);
                    assertEquals(get(output, index, 5, c),
                            get(output, index, 3, c) + get(output, index, 4, c), 2e-6f,
                            "Guide must match the active production closure");
                    if (!enabled) {
                        assertEquals(TARGET[c], base);
                        assertEquals(get(output, index, 2, c), get(output, index, 1, c), 2e-6f,
                                "Disabling compensation must restore authored material energy");
                    }
                }
            }
        }
    }

    @Test
    void blackWhiteAndExcludedTopologiesAreUnaffectedByTheSwitch() throws IOException {
        float[][] colors = {{0, 0, 0}, {1, 1, 1}, {0.001f, 0.02f, 0.8f},
                TARGET, TARGET, TARGET, TARGET};
        int[] optical = {0, 0, 230, 239, 0, 95 << 8, 190 << 8};
        int[] flags = {0, 0, 0, 0, 2 | 16, 16, 16};
        var input = ShaderTestBuffer.inputWriter(colors.length, INPUT_WORDS);
        for (int i = 0; i < colors.length; i++) {
            put(input, i, colors[i], 0.9f, optical[i], flags[i], 0, 0.25f);
        }
        ByteBuffer off = dispatch(input.buffer(), colors.length, false);
        ByteBuffer on = dispatch(input.buffer(), colors.length, true);
        for (int i = 0; i < colors.length; i++) {
            for (int c = 0; c < 3; c++) {
                float base = get(on, i, 0, c);
                assertTrue(Float.isFinite(base) && base >= 0 && base <= 1);
                if (i != 2) assertEquals(get(off, i, 0, c), base, 2e-6f);
                else assertTrue(base > 0 && base < colors[i][c]);
            }
        }
        for (int c = 0; c < 3; c++) {
            assertEquals(0, get(on, 0, 0, c));
            assertEquals(1, get(on, 1, 0, c));
            assertEquals(get(on, 0, 1, 3), get(on, 0, 1, c), 2e-6f);
            assertEquals(1, get(on, 1, 1, c), 2e-6f);
            assertEquals(TARGET[c], get(on, 4, 2, c), 2e-6f);
        }
        assertEquals(0.15f, get(on, 4, 2, 3));
        assertEquals(0.5f, get(on, 5, 3, 3));
        assertEquals(1, get(on, 6, 3, 3));
    }

    @Test
    void eachChannelStaysMonotoneAcrossDifferentColorsAndMaterials() throws IOException {
        int steps = 1024;
        int count = (steps + 1) * 6;
        var input = ShaderTestBuffer.inputWriter(count, INPUT_WORDS);
        for (int i = 0; i <= steps; i++) {
            for (int c = 0; c < 3; c++) {
                for (int variant = 0; variant < 2; variant++) {
                    float[] color = new float[3];
                    color[(c + 1) % 3] = variant == 0 ? 0 : 0.8f;
                    color[(c + 2) % 3] = variant == 0 ? 0.95f : 0.02f;
                    color[c] = i / (float) steps;
                    put(input, i * 6 + c * 2 + variant, color, variant,
                            variant == 0 ? i % 231 : 230 - i % 231, 0, 0, 0.5f);
                }
            }
        }
        ByteBuffer output = dispatch(input.buffer(), count, true);
        for (int i = 0; i <= steps; i++) {
            for (int c = 0; c < 3; c++) {
                float value = get(output, i * 6 + c * 2, 0, c);
                assertEquals(value, get(output, i * 6 + c * 2 + 1, 0, c),
                        "Other channels and material parameters must not alter this channel");
                assertTrue(Float.isFinite(value) && value >= 0 && value <= 1);
                if (i > 0) {
                    float delta = value - get(output, (i - 1) * 6 + c * 2, 0, c);
                    assertTrue(delta > 0 && delta < 1.05f / steps,
                            "Color ramps must remain strictly ordered without jumps");
                }
            }
        }
    }

    private ByteBuffer dispatch(ByteBuffer input, int count, boolean enabled) throws IOException {
        ByteBuffer push = ByteBuffer.allocateDirect(ShaderAbi.PUSH_CONSTANT_SIZE)
                .order(ByteOrder.nativeOrder());
        push.putInt(ShaderAbi.PUSH_PATH_OFFSET, IntegratorSettings.packSampleControl(
                0, AstronomySettings.defaults(), false, false, false, enabled,
                TransparentNeeMode.DEFAULT));
        return runner.dispatch("opaque_base_color.comp.spv", input,
                count * OUTPUT_WORDS * ShaderTestBuffer.WORD_BYTES, count, push);
    }

    private static void put(ShaderTestBuffer.InputWriter input, int index, float[] color,
            float roughness, int optical, int flags, float minimumRoughness, float cosine) {
        input.putVec4(index, 0, color[0], color[1], color[2], roughness);
        input.putInt(index, 1, 0, optical);
        input.putInt(index, 1, 1, flags);
        input.putFloat(index, 1, 2, minimumRoughness);
        input.putFloat(index, 1, 3, cosine);
    }

    private static float get(ByteBuffer output, int index, int word, int channel) {
        return ShaderTestBuffer.getFloat(output, index, OUTPUT_WORDS, word, channel);
    }
}
