package dev.prime.render.vulkan;

import com.mojang.blaze3d.vulkan.Destroyable;
import java.util.ArrayList;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.KHRRayTracingPipeline;
import org.lwjgl.vulkan.KHRSynchronization2;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkDependencyInfo;
import org.lwjgl.vulkan.VkImageMemoryBarrier2;

/** Minimal image-backed wavefront signal set for unfiltered realtime presentation. */
public final class BasicRawWavefrontFrame implements RawWavefrontFrame, Destroyable {
    private static final int SIGNAL_USAGE = VK12.VK_IMAGE_USAGE_STORAGE_BIT;
    private static final int LINEAR_OUTPUT_USAGE =
            SIGNAL_USAGE | VK12.VK_IMAGE_USAGE_SAMPLED_BIT;

    private final VulkanImage noisyDiffuse;
    private final VulkanImage noisySpecular;
    private final VulkanImage normalRoughness;
    private final VulkanImage viewZ;
    private final VulkanImage transportMetadata;
    private final VulkanImage material;
    private final VulkanImage specularMaterial;
    private final VulkanImage primaryPosition;
    private final VulkanImage sunLighting;
    private final VulkanImage sunPenumbra;
    private final VulkanImage materialClass;
    private final VulkanImage linearOutput;
    private final VulkanImage[] owned;
    private final boolean hasLinearOutput;
    private boolean destroyed;

    private BasicRawWavefrontFrame(ArrayList<VulkanImage> images, boolean hasLinearOutput) {
        this.noisyDiffuse = images.get(0);
        this.noisySpecular = images.get(1);
        this.normalRoughness = images.get(2);
        this.viewZ = images.get(3);
        this.transportMetadata = images.get(4);
        this.material = images.get(5);
        this.specularMaterial = images.get(6);
        this.primaryPosition = images.get(7);
        this.sunLighting = images.get(8);
        this.sunPenumbra = images.get(9);
        this.materialClass = images.get(10);
        this.linearOutput = hasLinearOutput ? images.get(11) : null;
        this.owned = images.toArray(VulkanImage[]::new);
        this.hasLinearOutput = hasLinearOutput;
    }

    static BasicRawWavefrontFrame createRealtime(
            VulkanContext context, int width, int height) {
        return create(
                context, width, height, "Prime unfiltered", true);
    }

    private static BasicRawWavefrontFrame create(
            VulkanContext context,
            int width,
            int height,
            String label,
            boolean hasLinearOutput) {
        ArrayList<VulkanImage> images = new ArrayList<>();
        try {
            add(context, images, width, height, VK12.VK_FORMAT_R16G16B16A16_SFLOAT,
                    label + " diffuse");
            add(context, images, width, height, VK12.VK_FORMAT_R16G16B16A16_SFLOAT,
                    label + " specular");
            add(context, images, width, height, VK12.VK_FORMAT_R32G32B32A32_SFLOAT,
                    label + " world normal and roughness");
            add(context, images, width, height, VK12.VK_FORMAT_R32_SFLOAT,
                    label + " view Z");
            add(context, images, width, height, VK12.VK_FORMAT_R16G16B16A16_SFLOAT,
                    label + " transport metadata");
            add(context, images, width, height, VK12.VK_FORMAT_R16G16B16A16_SFLOAT,
                    label + " material");
            add(context, images, width, height, VK12.VK_FORMAT_R16G16B16A16_SFLOAT,
                    label + " specular material");
            add(context, images, width, height, VK12.VK_FORMAT_R32G32B32A32_SFLOAT,
                    label + " primary position");
            add(context, images, width, height, VK12.VK_FORMAT_R16G16B16A16_SFLOAT,
                    label + " sun lighting");
            add(context, images, width, height, VK12.VK_FORMAT_R16_SFLOAT,
                    label + " sun penumbra");
            add(context, images, width, height, VK12.VK_FORMAT_R8_UNORM,
                    label + " material class");
            if (hasLinearOutput) {
                add(context, images, width, height, VK12.VK_FORMAT_R16G16B16A16_SFLOAT,
                        LINEAR_OUTPUT_USAGE, label + " linear HDR output");
            }
            return new BasicRawWavefrontFrame(images, hasLinearOutput);
        } catch (RuntimeException exception) {
            for (int index = images.size() - 1; index >= 0; index--) {
                images.get(index).destroy();
            }
            throw exception;
        }
    }

    private static void add(
            VulkanContext context,
            ArrayList<VulkanImage> images,
            int width,
            int height,
            int format,
            String label) {
        add(context, images, width, height, format, SIGNAL_USAGE, label);
    }

    private static void add(
            VulkanContext context,
            ArrayList<VulkanImage> images,
            int width,
            int height,
            int format,
            int usage,
            String label) {
        images.add(context.createImage2D(width, height, format, usage, label));
    }

    public void prepareForRayTrace(
            org.lwjgl.vulkan.VkCommandBuffer commandBuffer,
            VulkanImageInitializationBatch initialization) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkImageMemoryBarrier2.Buffer barriers =
                    VkImageMemoryBarrier2.calloc(this.owned.length, stack);
            for (int index = 0; index < this.owned.length; index++) {
                VulkanImage image = this.owned[index];
                boolean initialized = initialization.prepare(image);
                boolean linearOutput = this.hasLinearOutput
                        && index == this.owned.length - 1;
                long destinationStages = destinationStages(
                        this.hasLinearOutput, linearOutput);
                barriers.get(index).sType$Default()
                        .srcStageMask(initialized
                                ? VK12.VK_PIPELINE_STAGE_ALL_COMMANDS_BIT
                                : VK12.VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT)
                        .srcAccessMask(initialized
                                ? VK12.VK_ACCESS_MEMORY_READ_BIT | VK12.VK_ACCESS_MEMORY_WRITE_BIT
                                : 0L)
                        .dstStageMask(destinationStages)
                        .dstAccessMask(VK12.VK_ACCESS_SHADER_READ_BIT | VK12.VK_ACCESS_SHADER_WRITE_BIT)
                        .oldLayout(initialized
                                ? VK12.VK_IMAGE_LAYOUT_GENERAL
                                : VK12.VK_IMAGE_LAYOUT_UNDEFINED)
                        .newLayout(VK12.VK_IMAGE_LAYOUT_GENERAL)
                        .srcQueueFamilyIndex(VK12.VK_QUEUE_FAMILY_IGNORED)
                        .dstQueueFamilyIndex(VK12.VK_QUEUE_FAMILY_IGNORED)
                        .image(image.image());
                barriers.get(index).subresourceRange()
                        .aspectMask(VK12.VK_IMAGE_ASPECT_COLOR_BIT)
                        .baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1);
            }
            KHRSynchronization2.vkCmdPipelineBarrier2KHR(
                    commandBuffer,
                    VkDependencyInfo.calloc(stack).sType$Default()
                            .pImageMemoryBarriers(barriers));
        }
    }

    @Override public VulkanImage noisyDiffuse() { return this.noisyDiffuse; }
    @Override public VulkanImage noisySpecular() { return this.noisySpecular; }
    @Override public VulkanImage normalRoughness() { return this.normalRoughness; }
    @Override public VulkanImage viewZ() { return this.viewZ; }
    @Override public VulkanImage transportMetadata() { return this.transportMetadata; }
    @Override public VulkanImage material() { return this.material; }
    @Override public VulkanImage specularMaterial() { return this.specularMaterial; }
    @Override public VulkanImage materialClass() { return this.materialClass; }
    @Override public VulkanImage primaryPosition() { return this.primaryPosition; }
    @Override public VulkanImage sunLighting() { return this.sunLighting; }
    @Override public VulkanImage sunPenumbra() { return this.sunPenumbra; }
    VulkanImage linearOutput() { return this.linearOutput; }

    static int imageUsage(boolean linearOutput) {
        return linearOutput ? LINEAR_OUTPUT_USAGE : SIGNAL_USAGE;
    }

    static long destinationStages(boolean hasLinearOutput, boolean linearOutput) {
        if (linearOutput) {
            if (!hasLinearOutput) {
                throw new IllegalArgumentException(
                        "Raw wavefront scratch has no linear output image");
            }
            return VK12.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT;
        }
        return KHRRayTracingPipeline.VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR
                | (hasLinearOutput ? VK12.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT : 0L);
    }

    @Override
    public void destroy() {
        if (this.destroyed) return;
        this.destroyed = true;
        for (int index = this.owned.length - 1; index >= 0; index--) {
            this.owned[index].destroy();
        }
    }
}
