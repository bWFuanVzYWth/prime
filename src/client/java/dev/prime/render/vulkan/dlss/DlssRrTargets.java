package dev.prime.render.vulkan.dlss;

import com.mojang.blaze3d.vulkan.Destroyable;
import dev.prime.render.vulkan.VulkanContext;
import dev.prime.render.vulkan.VulkanImage;
import dev.prime.render.vulkan.RawWavefrontFrame;
import dev.prime.render.vulkan.VulkanImageInitializationBatch;
import java.util.ArrayList;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.KHRRayTracingPipeline;
import org.lwjgl.vulkan.KHRSynchronization2;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkDependencyInfo;
import org.lwjgl.vulkan.VkImageMemoryBarrier2;

/** Owns every raw path-trace signal and concrete NGX image for one RR feature extent. */
public final class DlssRrTargets implements RawWavefrontFrame, Destroyable {
    static final int COLOR_FORMAT = VK12.VK_FORMAT_R16G16B16A16_SFLOAT;
    static final int ALBEDO_FORMAT = VK12.VK_FORMAT_R16G16B16A16_SFLOAT;
    static final int NORMAL_ROUGHNESS_FORMAT = VK12.VK_FORMAT_R32G32B32A32_SFLOAT;
    static final int LINEAR_DEPTH_FORMAT = VK12.VK_FORMAT_R32_SFLOAT;
    static final int MOTION_FORMAT = VK12.VK_FORMAT_R32G32_SFLOAT;
    static final int SPECULAR_MOTION_FORMAT = VK12.VK_FORMAT_R32G32_SFLOAT;
    static final int SPECULAR_HIT_DISTANCE_FORMAT = VK12.VK_FORMAT_R16_SFLOAT;
    static final int RESPONSIVITY_FORMAT = VK12.VK_FORMAT_R16_SFLOAT;
    private static final int USAGE =
            VK12.VK_IMAGE_USAGE_STORAGE_BIT
                    | VK12.VK_IMAGE_USAGE_SAMPLED_BIT
                    | VK12.VK_IMAGE_USAGE_TRANSFER_SRC_BIT;

    private final VulkanImage noisyDiffuse;
    private final VulkanImage noisySpecular;
    private final VulkanImage sourceNormalRoughness;
    private final VulkanImage linearViewZ;
    private final VulkanImage transportScratch;
    private final VulkanImage motion;
    private final VulkanImage material;
    private final VulkanImage specularMaterial;
    private final VulkanImage primaryPosition;
    private final VulkanImage sunLighting;
    private final VulkanImage sunPenumbra;
    private final VulkanImage inputColor;
    private final VulkanImage normalRoughness;
    private final VulkanImage specularMotion;
    private final VulkanImage specularHitDistance;
    private final VulkanImage reflectionPosition;
    private final VulkanImage rrOutput;
    private final VulkanImage responsivity;
    private final VulkanImage reconstructionControl;
    private final VulkanImage[] owned;
    private boolean destroyed;

    private DlssRrTargets(ArrayList<VulkanImage> images) {
        this.noisyDiffuse = images.get(0);
        this.noisySpecular = images.get(1);
        this.sourceNormalRoughness = images.get(2);
        this.linearViewZ = images.get(3);
        this.transportScratch = images.get(4);
        this.motion = images.get(5);
        this.material = images.get(6);
        this.specularMaterial = images.get(7);
        this.primaryPosition = images.get(8);
        this.sunLighting = images.get(9);
        this.sunPenumbra = images.get(10);
        this.inputColor = images.get(11);
        this.normalRoughness = images.get(12);
        this.specularMotion = images.get(13);
        this.specularHitDistance = images.get(14);
        this.reflectionPosition = images.get(15);
        this.rrOutput = images.get(16);
        this.responsivity = images.get(17);
        this.reconstructionControl = images.get(18);
        this.owned = images.toArray(VulkanImage[]::new);
    }

    public static DlssRrTargets create(
            VulkanContext context,
            int renderWidth,
            int renderHeight,
            int displayWidth,
            int displayHeight) {
        ArrayList<VulkanImage> images = new ArrayList<>();
        try {
            add(context, images, renderWidth, renderHeight,
                    VK12.VK_FORMAT_R16G16B16A16_SFLOAT, "Prime RR noisy diffuse");
            add(context, images, renderWidth, renderHeight,
                    VK12.VK_FORMAT_R16G16B16A16_SFLOAT, "Prime RR noisy specular");
            add(context, images, renderWidth, renderHeight,
                    VK12.VK_FORMAT_R32G32B32A32_SFLOAT,
                    "Prime RR source world normal and roughness");
            add(context, images, renderWidth, renderHeight,
                    LINEAR_DEPTH_FORMAT, "Prime RR linear view Z");
            add(context, images, renderWidth, renderHeight,
                    VK12.VK_FORMAT_R16G16B16A16_SFLOAT, "Prime RR transport scratch");
            add(context, images, renderWidth, renderHeight,
                    MOTION_FORMAT, "Prime RR canonical visible motion");
            add(context, images, renderWidth, renderHeight,
                    ALBEDO_FORMAT, "Prime RR diffuse albedo and distance");
            add(context, images, renderWidth, renderHeight,
                    ALBEDO_FORMAT, "Prime RR specular albedo and material flags");
            add(context, images, renderWidth, renderHeight,
                    VK12.VK_FORMAT_R32G32B32A32_SFLOAT, "Prime RR primary position");
            add(context, images, renderWidth, renderHeight,
                    VK12.VK_FORMAT_R16G16B16A16_SFLOAT, "Prime RR sun lighting");
            add(context, images, renderWidth, renderHeight,
                    VK12.VK_FORMAT_R16_SFLOAT, "Prime RR sun penumbra");
            add(context, images, renderWidth, renderHeight,
                    COLOR_FORMAT, "Prime RR input color");
            add(context, images, renderWidth, renderHeight,
                    NORMAL_ROUGHNESS_FORMAT, "Prime RR world normal and roughness");
            add(context, images, renderWidth, renderHeight,
                    SPECULAR_MOTION_FORMAT, "Prime RR reflection motion");
            add(context, images, renderWidth, renderHeight,
                    SPECULAR_HIT_DISTANCE_FORMAT, "Prime RR specular hit distance");
            add(context, images, renderWidth, renderHeight,
                    VK12.VK_FORMAT_R32G32B32A32_SFLOAT,
                    "Prime RR reflection previous virtual position");
            add(context, images, displayWidth, displayHeight,
                    COLOR_FORMAT, "Prime RR linear HDR output");
            add(context, images, renderWidth, renderHeight,
                    RESPONSIVITY_FORMAT, "Prime RR responsivity");
            add(context, images, renderWidth, renderHeight,
                    VK12.VK_FORMAT_R8_UINT, "Prime RR reconstruction control");
            return new DlssRrTargets(images);
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
        images.add(context.createImage2D(width, height, format, USAGE, label));
    }

    /** Transitions the exact RR resource set to its lifetime-stable GENERAL layout. */
    public void prepareForRayTrace(
            org.lwjgl.vulkan.VkCommandBuffer commandBuffer,
            VulkanImageInitializationBatch initialization) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkImageMemoryBarrier2.Buffer barriers = VkImageMemoryBarrier2.calloc(this.owned.length, stack);
            for (int index = 0; index < this.owned.length; index++) {
                VulkanImage image = this.owned[index];
                boolean initialized = initialization.prepare(image);
                barriers.get(index)
                        .sType$Default()
                        .srcStageMask(initialized
                                ? VK12.VK_PIPELINE_STAGE_ALL_COMMANDS_BIT
                                : VK12.VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT)
                        .srcAccessMask(initialized
                                ? VK12.VK_ACCESS_MEMORY_READ_BIT | VK12.VK_ACCESS_MEMORY_WRITE_BIT
                                : 0L)
                        .dstStageMask(
                                KHRRayTracingPipeline.VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR
                                        | VK12.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT)
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
                        .baseMipLevel(0)
                        .levelCount(1)
                        .baseArrayLayer(0)
                        .layerCount(1);
            }
            VkDependencyInfo dependency = VkDependencyInfo.calloc(stack)
                    .sType$Default()
                    .pImageMemoryBarriers(barriers);
            KHRSynchronization2.vkCmdPipelineBarrier2KHR(commandBuffer, dependency);
        }
    }

    @Override public VulkanImage noisyDiffuse() { return this.noisyDiffuse; }
    @Override public VulkanImage noisySpecular() { return this.noisySpecular; }
    @Override public VulkanImage normalRoughness() { return this.sourceNormalRoughness; }
    @Override public VulkanImage viewZ() { return this.linearViewZ; }
    @Override public VulkanImage transportScratch() { return this.transportScratch; }
    @Override public VulkanImage reconstructionMotion() { return this.motion; }
    @Override public VulkanImage material() { return this.material; }
    @Override public VulkanImage specularMaterial() { return this.specularMaterial; }
    @Override public VulkanImage reconstructionControl() { return this.reconstructionControl; }
    @Override public VulkanImage primaryPosition() { return this.primaryPosition; }
    @Override public VulkanImage reflectionPosition() { return this.reflectionPosition; }
    @Override public VulkanImage sunLighting() { return this.sunLighting; }
    @Override public VulkanImage sunPenumbra() { return this.sunPenumbra; }

    public VulkanImage inputColor() { return this.inputColor; }
    public VulkanImage motion() { return this.motion; }
    public VulkanImage rrNormalRoughness() { return this.normalRoughness; }
    public VulkanImage specularMotion() { return this.specularMotion; }
    public VulkanImage specularHitDistance() { return this.specularHitDistance; }
    public VulkanImage rrOutput() { return this.rrOutput; }
    public VulkanImage responsivity() { return this.responsivity; }

    @Override
    public void destroy() {
        if (this.destroyed) {
            return;
        }
        this.destroyed = true;
        for (int index = this.owned.length - 1; index >= 0; index--) {
            this.owned[index].destroy();
        }
    }
}
