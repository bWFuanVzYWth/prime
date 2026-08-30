package dev.prime.render.vulkan;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.lwjgl.vulkan.VK12;

final class VulkanImageInitializationBatchTest {
    @Test
    void abandonedCandidateDoesNotAdvanceCommittedImageState() {
        VulkanImage image = image();
        VulkanImageInitializationBatch batch =
                new VulkanImageInitializationBatch();

        batch.begin();
        assertFalse(batch.prepare(image));
        assertTrue(batch.prepare(image));
        batch.abandon();

        assertFalse(image.initialized());
        batch.begin();
        assertFalse(batch.prepare(image));
        batch.submitted();
        assertTrue(image.initialized());
    }

    @Test
    void overlappingOrInactiveTransactionsAreRejected() {
        VulkanImageInitializationBatch batch =
                new VulkanImageInitializationBatch();

        assertThrows(IllegalStateException.class, batch::submitted);
        batch.begin();
        assertThrows(IllegalStateException.class, batch::begin);
        batch.abandon();
        assertThrows(IllegalStateException.class, batch::abandon);
    }

    private static VulkanImage image() {
        return new VulkanImage(
                0L,
                null,
                1L,
                2L,
                3L,
                new long[] {3L},
                VK12.VK_FORMAT_R16G16B16A16_SFLOAT,
                VK12.VK_IMAGE_USAGE_STORAGE_BIT,
                1,
                1,
                1);
    }
}
