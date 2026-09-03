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
    private static final int SIGNAL_USAGE =
            VK12.VK_IMAGE_USAGE_STORAGE_BIT
                    | VK12.VK_IMAGE_USAGE_SAMPLED_BIT
                    | VK12.VK_IMAGE_USAGE_TRANSFER_SRC_BIT;
    private enum Role {
        NOISY_DIFFUSE(VK12.VK_FORMAT_R16G16B16A16_SFLOAT, "diffuse"),
        NOISY_SPECULAR(VK12.VK_FORMAT_R16G16B16A16_SFLOAT, "specular"),
        NORMAL_ROUGHNESS(VK12.VK_FORMAT_R32G32B32A32_SFLOAT, "world normal and roughness"),
        VIEW_Z(VK12.VK_FORMAT_R32_SFLOAT, "view Z"),
        TRANSPORT_METADATA(VK12.VK_FORMAT_R16G16B16A16_SFLOAT, "transport metadata"),
        MATERIAL(VK12.VK_FORMAT_R16G16B16A16_SFLOAT, "material"),
        SPECULAR_MATERIAL(VK12.VK_FORMAT_R16G16B16A16_SFLOAT, "specular material"),
        PRIMARY_POSITION(VK12.VK_FORMAT_R32G32B32A32_SFLOAT, "primary position"),
        SUN_LIGHTING(VK12.VK_FORMAT_R16G16B16A16_SFLOAT, "sun lighting"),
        SUN_PENUMBRA(VK12.VK_FORMAT_R16_SFLOAT, "sun penumbra"),
        RECONSTRUCTION_CONTROL(VK12.VK_FORMAT_R8_UINT, "reconstruction control"),
        LINEAR_OUTPUT(VK12.VK_FORMAT_R16G16B16A16_SFLOAT, "linear HDR output");

        final int format;
        final String label;

        Role(int format, String label) {
            this.format = format;
            this.label = label;
        }
    }

    private final VulkanImage[] images;
    private final boolean hasLinearOutput;
    private boolean destroyed;

    private BasicRawWavefrontFrame(ArrayList<VulkanImage> images, boolean hasLinearOutput) {
        this.images = images.toArray(VulkanImage[]::new);
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
            for (Role role : Role.values()) {
                if (role == Role.LINEAR_OUTPUT && !hasLinearOutput) {
                    break;
                }
                images.add(context.createImage2D(
                        width, height, role.format, SIGNAL_USAGE, label + " " + role.label));
            }
            return new BasicRawWavefrontFrame(images, hasLinearOutput);
        } catch (RuntimeException exception) {
            for (int index = images.size() - 1; index >= 0; index--) {
                images.get(index).destroy();
            }
            throw exception;
        }
    }

    public void prepareForRayTrace(
            org.lwjgl.vulkan.VkCommandBuffer commandBuffer,
            VulkanImageInitializationBatch initialization) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkImageMemoryBarrier2.Buffer barriers =
                    VkImageMemoryBarrier2.calloc(this.images.length, stack);
            for (int index = 0; index < this.images.length; index++) {
                VulkanImage image = this.images[index];
                boolean initialized = initialization.prepare(image);
                boolean linearOutput = this.hasLinearOutput
                        && index == Role.LINEAR_OUTPUT.ordinal();
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

    private VulkanImage image(Role role) { return this.images[role.ordinal()]; }

    @Override public VulkanImage noisyDiffuse() { return image(Role.NOISY_DIFFUSE); }
    @Override public VulkanImage noisySpecular() { return image(Role.NOISY_SPECULAR); }
    @Override public VulkanImage normalRoughness() { return image(Role.NORMAL_ROUGHNESS); }
    @Override public VulkanImage viewZ() { return image(Role.VIEW_Z); }
    @Override public VulkanImage transportScratch() { return image(Role.TRANSPORT_METADATA); }
    @Override public VulkanImage reconstructionMotion() { return image(Role.TRANSPORT_METADATA); }
    @Override public VulkanImage material() { return image(Role.MATERIAL); }
    @Override public VulkanImage specularMaterial() { return image(Role.SPECULAR_MATERIAL); }
    @Override public VulkanImage reconstructionControl() { return image(Role.RECONSTRUCTION_CONTROL); }
    @Override public VulkanImage primaryPosition() { return image(Role.PRIMARY_POSITION); }
    @Override public VulkanImage sunLighting() { return image(Role.SUN_LIGHTING); }
    @Override public VulkanImage sunPenumbra() { return image(Role.SUN_PENUMBRA); }
    VulkanImage linearOutput() { return image(Role.LINEAR_OUTPUT); }

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
        for (int index = this.images.length - 1; index >= 0; index--) {
            this.images[index].destroy();
        }
    }
}
