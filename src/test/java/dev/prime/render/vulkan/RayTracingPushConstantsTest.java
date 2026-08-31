package dev.prime.render.vulkan;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.prime.render.AstronomySettings;
import dev.prime.render.AstronomyState;
import dev.prime.render.FrameCamera;
import dev.prime.render.IntegratorFrameInput;
import dev.prime.render.IntegratorSettings;
import dev.prime.render.LightingSettings;
import dev.prime.render.MaterialSettings;
import dev.prime.render.RayConeParameters;
import dev.prime.render.SunDirection;
import dev.prime.render.post.PostProcessingMode;
import dev.prime.render.post.ReconstructionQualityMode;
import dev.prime.render.post.TransparentGuideMode;
import dev.prime.render.shader.ShaderAbi;
import dev.prime.render.vulkan.terrain.TerrainScene;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;

final class RayTracingPushConstantsTest {
    @Test
    void fiveProductionProfilesKeepTheirHistoricalRayConeAbiBits() {
        int[] widths = {3840, 2560, 2258, 1920, 1280};
        int[] heights = {2160, 1440, 1270, 1080, 720};
        int[] expected = {
            0xbc00_110f, 0xbe57_1396, 0xbf10_144d, 0xc000_150f, 0xc12c_1796
        };
        ReconstructionQualityMode[] qualities = ReconstructionQualityMode.values();
        for (int index = 0; index < qualities.length; index++) {
            assertEquals(
                    expected[index],
                    RayTracingPushConstants.packRayCone(
                            qualities[index].rayConeParameters(
                                    1.25F,
                                    1.5F,
                                    widths[index],
                                    heights[index])));
        }
    }

    @Test
    void completeFrameInputEncodesDeterministicallyAndOverwritesEveryByte() {
        Fixture fixture = input(37);
        IntegratorFrameInput input = fixture.input();
        byte[] first = new byte[ShaderAbi.PUSH_CONSTANT_SIZE];
        byte[] second = new byte[ShaderAbi.PUSH_CONSTANT_SIZE];
        Arrays.fill(first, (byte) 0x55);
        Arrays.fill(second, (byte) 0xaa);
        ByteBuffer firstBuffer = ByteBuffer.wrap(first).order(ByteOrder.nativeOrder());
        ByteBuffer secondBuffer = ByteBuffer.wrap(second).order(ByteOrder.nativeOrder());

        RayTracingPushConstants.write(input, fixture.scene(), firstBuffer);
        RayTracingPushConstants.write(input, fixture.scene(), secondBuffer);

        assertArrayEquals(first, second);
        assertEquals(
                fixture.scene().sectionTableAddress(),
                firstBuffer.getLong(ShaderAbi.PUSH_SECTION_TABLE_ADDRESS_OFFSET));
        assertEquals(input.width(), firstBuffer.getInt(ShaderAbi.PUSH_OUTPUT_EXTENT_OFFSET));
        assertEquals(
                input.height(),
                firstBuffer.getInt(ShaderAbi.PUSH_OUTPUT_EXTENT_OFFSET + Integer.BYTES));
        assertEquals(
                0x1234_5678,
                firstBuffer.getInt(ShaderAbi.PUSH_RAY_CONE_OFFSET));
        assertEquals(
                IntegratorSettings.packSampleControl(
                        input.sampleIndex(),
                        input.astronomy().settings(),
                        input.material().seamlessGlass(),
                        input.material().airGap(),
                        input.material().vanillaPbrPresets()),
                firstBuffer.getInt(ShaderAbi.PUSH_PATH_OFFSET));
        assertEquals(
                IntegratorSettings.packSampleEpoch(
                        input.sampleEpoch(), input.historyValid()),
                firstBuffer.getInt(ShaderAbi.PUSH_PATH_OFFSET + Integer.BYTES));
        assertEquals(
                IntegratorSettings.packPathControl(
                        input.maximumBounces(),
                        input.jitterPhase(),
                        input.astronomy().settings(),
                        input.cameraInWater(),
                        input.transparentGuideMode()),
                firstBuffer.getInt(ShaderAbi.PUSH_PATH_OFFSET + 2 * Integer.BYTES));
    }

    @Test
    void sampleIdentityAndOutputExtentAreValidatedAtTheBoundary() {
        assertThrows(IllegalArgumentException.class, () -> input(1 << 16));
        IntegratorFrameInput valid = input(0).input();
        assertThrows(
                IllegalArgumentException.class,
                () -> new IntegratorFrameInput(
                        valid.camera(),
                        0,
                        valid.height(),
                        valid.astronomy(),
                        valid.rayCone(),
                        valid.maximumBounces(),
                        valid.sampleIndex(),
                        valid.sampleEpoch(),
                        valid.jitterPhase(),
                        valid.cameraInWater(),
                        valid.postProcessingMode(),
                        valid.transparentGuideMode(),
                        valid.lighting(),
                        valid.material(),
                        valid.shInput()));
        assertThrows(
                IllegalArgumentException.class,
                () -> new IntegratorFrameInput(
                        valid.camera(),
                        (1 << 18) + 1,
                        valid.height(),
                        valid.astronomy(),
                        valid.rayCone(),
                        valid.maximumBounces(),
                        valid.sampleIndex(),
                        valid.sampleEpoch(),
                        valid.jitterPhase(),
                        valid.cameraInWater(),
                        valid.postProcessingMode(),
                        valid.transparentGuideMode(),
                        valid.lighting(),
                        valid.material(),
                        valid.shInput()));
    }

    private static Fixture input(int sampleIndex) {
        FrameCamera camera = new FrameCamera(
                new Matrix4f().perspective(
                        (float) Math.toRadians(70.0), 16.0F / 9.0F, 512.0F, 0.05F, true),
                new Matrix4f(),
                new Matrix4f().translation(0.25F, -0.5F, 0.75F),
                101.0,
                64.0,
                -33.0,
                101.25,
                63.5,
                -32.75);
        TerrainScene.ResidentSceneView scene =
                new TerrainScene.ResidentSceneView(
                        3L, 0x1020_3040_5060_7080L, 96, 48, -48, 4L, 5L);
        LightingSettings.Snapshot lighting =
                new LightingSettings.Snapshot(4, -8, 12, 7L);
        MaterialSettings.Snapshot material =
                new MaterialSettings.Snapshot(90, true, 8L);
        IntegratorFrameInput input = new IntegratorFrameInput(
                camera,
                320,
                180,
                AstronomyState.atSolarHourAngle(
                        0.7F,
                        new AstronomySettings(-45, 270)),
                new RayConeParameters(
                        Float.float16ToFloat((short) 0x5678),
                        Float.float16ToFloat((short) 0x1234)),
                4,
                sampleIndex,
                19,
                7,
                true,
                PostProcessingMode.NRD_FSR,
                TransparentGuideMode.REFLECTION_AND_TRANSMISSION,
                lighting,
                material,
                true);
        return new Fixture(input, scene);
    }

    private record Fixture(
            IntegratorFrameInput input,
            TerrainScene.ResidentSceneView scene) {
    }
}
