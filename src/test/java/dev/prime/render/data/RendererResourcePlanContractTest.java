package dev.prime.render.data;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.prime.render.shader.ShaderAbi;
import org.junit.jupiter.api.Test;

final class RendererResourcePlanContractTest {
    @Test
    void wavefrontStorageMatchesTheExecutableAbi() {
        int realtimeBytesPerPixel =
                ShaderAbi.WAVEFRONT_PATH_RECORD_SIZE
                                * ShaderAbi.WAVEFRONT_PATH_SLOTS_PER_PIXEL
                        + ShaderAbi.WAVEFRONT_AREA_RECORD_SIZE
                        + ShaderAbi.WAVEFRONT_QUEUE_STORAGE_ENTRIES_PER_PIXEL * Integer.BYTES;
        int offlineBytesPerPixel =
                ShaderAbi.OFFLINE_WAVEFRONT_PATH_RECORD_SIZE
                        + ShaderAbi.OFFLINE_WAVEFRONT_STAGE_RECORD_SIZE
                        + ShaderAbi.OFFLINE_WAVEFRONT_QUEUE_STORAGE_ENTRIES_PER_PIXEL
                                * Integer.BYTES;

        assertEquals(524, realtimeBytesPerPixel);
        assertEquals(244, offlineBytesPerPixel);
        assertEquals(
                ShaderAbi.WAVEFRONT_STAGED_LIGHT_RECORD_SIZE,
                ShaderAbi.WAVEFRONT_STAGED_RECEIVER_NORMAL_OFFSET
                        + 3 * Float.BYTES);
        assertEquals(
                ShaderAbi.WAVEFRONT_AREA_GUIDE_RECORD_SIZE
                        + ShaderAbi.WAVEFRONT_SURFACE_RECORD_SIZE
                                * ShaderAbi.WAVEFRONT_PATH_SLOTS_PER_PIXEL
                        + Math.max(
                                ShaderAbi.WAVEFRONT_DETACHED_GUIDE_RECORD_SIZE,
                                ShaderAbi.WAVEFRONT_STAGED_LIGHT_RECORD_SIZE
                                        * ShaderAbi.WAVEFRONT_PATH_SLOTS_PER_PIXEL),
                ShaderAbi.WAVEFRONT_AREA_RECORD_SIZE);
        assertEquals(4 * Integer.BYTES, ShaderAbi.WAVEFRONT_AREA_GUIDE_RECORD_SIZE);
        assertEquals(
                ShaderAbi.OFFLINE_WAVEFRONT_SURFACE_RECORD_SIZE
                        + ShaderAbi.OFFLINE_WAVEFRONT_STAGED_LIGHT_RECORD_SIZE,
                ShaderAbi.OFFLINE_WAVEFRONT_STAGE_RECORD_SIZE);
    }
}
