package dev.prime.render.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.prime.render.FrameCamera;
import dev.prime.render.post.PostProcessingMode;
import dev.prime.render.post.ReconstructionQualityMode;
import dev.prime.render.vulkan.MaterialTexturePages;
import dev.prime.render.vulkan.VulkanMemorySnapshot;
import dev.prime.render.vulkan.terrain.TerrainScene;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class RendererDataMeasurementRecorderTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void publishesParseableAggregateWithoutWorldCoordinates() throws Exception {
        Path output = this.temporaryDirectory.resolve("measurements/latest.json");
        RendererDataMeasurementRecorder recorder =
                new RendererDataMeasurementRecorder(output, 1, "test \"gpu\"");
        MaterialTexturePages.ByteRange full =
                new MaterialTexturePages.ByteRange(0, 255, 256);
        MaterialTexturePages.ChannelMeasurement channel =
                new MaterialTexturePages.ChannelMeasurement(
                        3, 2, 1, 4096L, 8, 2, 8192L, 2048L, 1536L,
                        1024, 512, 900, 400, full, full, full, full);
        MaterialTexturePages.MeasurementSnapshot textures =
                new MaterialTexturePages.MeasurementSnapshot(
                        3L, 2048, 1024, 5, 5, 7, 2, 1, 64, 32, 4,
                        11_173_888L, channel, channel, 256L, 512L);
        TerrainScene.SceneStatistics scene =
                new TerrainScene.SceneStatistics(12, 34L, 56L, 7, 8);
        TerrainScene.MediumIdStatistics mediumIds =
                new TerrainScene.MediumIdStatistics(4, 4L);
        RealtimeRenderer.DiagnosticSnapshot renderer =
                new RealtimeRenderer.DiagnosticSnapshot(
                        PostProcessingMode.DLSS_RR,
                        ReconstructionQualityMode.PERFORMANCE,
                        960,
                        540,
                        1920,
                        1080,
                        3,
                        9,
                        10L,
                        null);
        VulkanMemorySnapshot firstMemory = new VulkanMemorySnapshot(
                9,
                3,
                8192L,
                2048L,
                List.of(new VulkanMemorySnapshot.Heap(0, 8192L, 1024L, 4096L, 16384L)));
        VulkanMemorySnapshot secondMemory = new VulkanMemorySnapshot(
                2,
                8,
                4096L,
                3072L,
                List.of(
                        new VulkanMemorySnapshot.Heap(0, 4096L, 3072L, 8192L, 8192L),
                        new VulkanMemorySnapshot.Heap(1, 1024L, 512L, 768L, 4096L)));

        recorder.recordFrame(
                textures, scene, mediumIds, camera(1.0, 2.0, 3.0), renderer, firstMemory);
        recorder.recordFrame(
                textures, scene, mediumIds, camera(4.0, 6.0, 3.0), renderer, secondMemory);

        String encoded = Files.readString(output);
        // Gson is supplied by Minecraft but its optional Error Prone annotations are absent from
        // the compile classpath. Reflective invocation proves syntax without weakening -Werror.
        Class<?> parser = Class.forName("com.google.gson.JsonParser");
        Object element = parser.getMethod("parseString", String.class).invoke(null, encoded);
        assertEquals(true, element.getClass().getMethod("isJsonObject").invoke(element));
        assertTrue(encoded.contains("\"schema\": \"prime-renderer-measurement-v1\""));
        assertTrue(encoded.contains("\"device\": \"test \\\"gpu\\\"\""));
        assertTrue(encoded.contains("\"frames\": 2"));
        assertTrue(encoded.contains("\"maximumCameraStepBlocks\": 5.00000000"));
        assertTrue(encoded.contains("\"maximumTextureId\": 7"));
        assertTrue(encoded.contains("\"baseAtlasRgba8Bytes\": 11173888"));
        assertTrue(encoded.contains("\"highWaterId\": 4"));
        assertTrue(encoded.contains("\"maximumSampledMemory\""));
        assertTrue(encoded.contains("\"blockCount\": 9"));
        assertTrue(encoded.contains("\"allocationCount\": 8"));
        assertTrue(encoded.contains("\"blockBytes\": 8192"));
        assertTrue(encoded.contains("\"allocationBytes\": 3072"));
        assertFalse(encoded.contains("\"cameraX\"")
                || encoded.contains("\"cameraY\"")
                || encoded.contains("\"cameraZ\""));
    }

    private static FrameCamera camera(double x, double y, double z) {
        return new FrameCamera(
                new Matrix4f(), new Matrix4f(), new Matrix4f(),
                x, y, z, x, y, z);
    }
}
