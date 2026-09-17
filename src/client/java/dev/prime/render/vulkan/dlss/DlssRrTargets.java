// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

package dev.prime.render.vulkan.dlss;

import com.mojang.renderpearl.backend.vulkan.Destroyable;
import dev.prime.render.vulkan.VulkanContext;
import dev.prime.render.vulkan.VulkanImage;
import dev.prime.render.vulkan.RawWavefrontFrame;
import dev.prime.render.vulkan.VulkanImageInitializationBatch;
import dev.prime.render.vulkan.VulkanSync;
import org.lwjgl.vulkan.KHRRayTracingPipeline;
import org.lwjgl.vulkan.VK12;

/** Owns every raw path-trace signal and concrete NGX image for one RR feature extent. */
public final class DlssRrTargets implements RawWavefrontFrame, Destroyable {
    static final int COLOR_FORMAT = VK12.VK_FORMAT_R16G16B16A16_SFLOAT;
    static final int ALBEDO_FORMAT = VK12.VK_FORMAT_R16G16B16A16_SFLOAT;
    static final int NORMAL_ROUGHNESS_FORMAT = VK12.VK_FORMAT_R32G32B32A32_SFLOAT;
    static final int LINEAR_DEPTH_FORMAT = VK12.VK_FORMAT_R32_SFLOAT;
    static final int MOTION_FORMAT = VK12.VK_FORMAT_R32G32_SFLOAT;
    static final int SPECULAR_HIT_DISTANCE_FORMAT = VK12.VK_FORMAT_R16_SFLOAT;
    static final int RESPONSIVITY_FORMAT = VK12.VK_FORMAT_R16_SFLOAT;
    private static final int USAGE =
            VK12.VK_IMAGE_USAGE_STORAGE_BIT
                    | VK12.VK_IMAGE_USAGE_SAMPLED_BIT
                    | VK12.VK_IMAGE_USAGE_TRANSFER_SRC_BIT;

    private final VulkanImage[] owned;
    private boolean destroyed;

    private DlssRrTargets(VulkanImage[] owned) {
        this.owned = owned;
    }

    public static DlssRrTargets create(
            VulkanContext context,
            int renderWidth,
            int renderHeight,
            int displayWidth,
            int displayHeight) {
        Role[] roles = Role.values();
        VulkanImage[] images = new VulkanImage[roles.length];
        int created = 0;
        try {
            for (Role role : roles) {
                int width = role.displayExtent ? displayWidth : renderWidth;
                int height = role.displayExtent ? displayHeight : renderHeight;
                images[created++] = context.createImage2D(
                        width, height, role.format, USAGE, role.label);
            }
            return new DlssRrTargets(images);
        } catch (RuntimeException exception) {
            for (int index = created - 1; index >= 0; index--) {
                images[index].destroy();
            }
            throw exception;
        }
    }

    private VulkanImage image(Role role) {
        return this.owned[role.ordinal()];
    }

    /** Transitions the exact RR resource set to its lifetime-stable GENERAL layout. */
    public void prepareForRayTrace(
            org.lwjgl.vulkan.VkCommandBuffer commandBuffer,
            VulkanImageInitializationBatch initialization) {
        VulkanSync.prepareImages(commandBuffer, initialization, this.owned,
                VK12.VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
                VK12.VK_ACCESS_MEMORY_READ_BIT | VK12.VK_ACCESS_MEMORY_WRITE_BIT,
                KHRRayTracingPipeline.VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR
                        | VK12.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                VK12.VK_ACCESS_SHADER_READ_BIT | VK12.VK_ACCESS_SHADER_WRITE_BIT);
    }

    @Override public VulkanImage noisyDiffuse() { return image(Role.NOISY_DIFFUSE); }
    @Override public VulkanImage noisySpecular() { return image(Role.NOISY_SPECULAR); }
    @Override public VulkanImage normalRoughness() { return image(Role.NORMAL_ROUGHNESS); }
    @Override public VulkanImage viewZ() { return image(Role.VIEW_Z); }
    @Override public VulkanImage transportScratch() { return image(Role.TRANSPORT_SCRATCH); }
    @Override public VulkanImage reconstructionMotion() { return image(Role.MOTION); }
    @Override public VulkanImage material() { return image(Role.MATERIAL); }
    @Override public VulkanImage specularMaterial() { return image(Role.SPECULAR_MATERIAL); }
    @Override public VulkanImage reflectionNormalRoughness() {
        return image(Role.REFLECTION_NORMAL_ROUGHNESS);
    }
    @Override public VulkanImage reconstructionControl() { return image(Role.RECONSTRUCTION_CONTROL); }
    @Override public VulkanImage primaryPosition() { return image(Role.PRIMARY_POSITION); }
    @Override public VulkanImage reflectionPosition() { return image(Role.REFLECTION_POSITION); }
    @Override public VulkanImage sunLighting() { return image(Role.SUN_LIGHTING); }
    @Override public VulkanImage sunPenumbra() { return image(Role.SUN_PENUMBRA); }

    public VulkanImage inputColor() { return image(Role.TRANSPORT_SCRATCH); }
    public VulkanImage motion() { return image(Role.MOTION); }
    public VulkanImage rrNormalRoughness() { return image(Role.NORMAL_ROUGHNESS); }
    public VulkanImage specularHitDistance() { return image(Role.SUN_PENUMBRA); }
    public VulkanImage rrOutput() { return image(Role.OUTPUT); }
    public VulkanImage responsivity() { return image(Role.RESPONSIVITY); }

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

    private enum Role {
        NOISY_DIFFUSE(COLOR_FORMAT, "Prime RR noisy diffuse"),
        NOISY_SPECULAR(COLOR_FORMAT, "Prime RR noisy specular"),
        NORMAL_ROUGHNESS(NORMAL_ROUGHNESS_FORMAT, "Prime RR world normal and roughness"),
        VIEW_Z(LINEAR_DEPTH_FORMAT, "Prime RR linear view Z"),
        TRANSPORT_SCRATCH(COLOR_FORMAT, "Prime RR transport scratch / input color"),
        MOTION(MOTION_FORMAT, "Prime RR canonical visible motion"),
        MATERIAL(ALBEDO_FORMAT, "Prime RR diffuse albedo and distance"),
        SPECULAR_MATERIAL(ALBEDO_FORMAT, "Prime RR specular albedo and material flags"),
        PRIMARY_POSITION(VK12.VK_FORMAT_R32G32B32A32_SFLOAT, "Prime RR primary position"),
        SUN_LIGHTING(COLOR_FORMAT, "Prime RR sun lighting"),
        SUN_PENUMBRA(SPECULAR_HIT_DISTANCE_FORMAT,
                "Prime RR sun penumbra / specular hit distance"),
        REFLECTION_NORMAL_ROUGHNESS(NORMAL_ROUGHNESS_FORMAT,
                "Prime RR reflection guide surface"),
        REFLECTION_POSITION(VK12.VK_FORMAT_R32G32B32A32_SFLOAT,
                "Prime RR reflection previous virtual position"),
        OUTPUT(COLOR_FORMAT, "Prime RR linear HDR output", true),
        RESPONSIVITY(RESPONSIVITY_FORMAT, "Prime RR responsivity"),
        RECONSTRUCTION_CONTROL(VK12.VK_FORMAT_R8_UINT, "Prime RR reconstruction control");

        final int format;
        final String label;
        final boolean displayExtent;

        Role(int format, String label) {
            this(format, label, false);
        }

        Role(int format, String label, boolean displayExtent) {
            this.format = format;
            this.label = label;
            this.displayExtent = displayExtent;
        }
    }
}
