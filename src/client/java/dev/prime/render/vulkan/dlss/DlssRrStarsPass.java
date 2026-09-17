// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.
package dev.prime.render.vulkan.dlss;

import com.mojang.renderpearl.backend.vulkan.Destroyable;
import dev.prime.render.AtmosphereCoordinates;
import dev.prime.render.post.ReconstructionFrameParameters;
import dev.prime.render.shader.ShaderAbi;
import dev.prime.render.vulkan.AtmospherePipeline;
import dev.prime.render.vulkan.DispatchMath;
import dev.prime.render.vulkan.VulkanContext;
import dev.prime.render.vulkan.VulkanDescriptors;
import dev.prime.render.vulkan.VulkanImage;
import dev.prime.render.vulkan.VulkanSharedPrograms;
import dev.prime.render.vulkan.VulkanSync;
import java.nio.ByteOrder;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkCommandBuffer;

/** Native-resolution starlight behind the foreground coverage reconstructed by NGX. */
final class DlssRrStarsPass implements Destroyable {
    private final VulkanSharedPrograms.SharedComputeProgram program;
    private final VulkanDescriptors.BoundSet descriptors;
    private final int dispatchX;
    private final int dispatchY;
    private boolean destroyed;

    private DlssRrStarsPass(VulkanSharedPrograms.SharedComputeProgram program,
            VulkanDescriptors.BoundSet descriptors, VulkanImage output) {
        this.program = program;
        this.descriptors = descriptors;
        this.dispatchX = DispatchMath.divideRoundUp(output.width(), 8);
        this.dispatchY = DispatchMath.divideRoundUp(output.height(), 8);
    }

    static DlssRrStarsPass create(VulkanContext context, VulkanImage output,
            AtmospherePipeline atmosphere, VulkanImage starmap, long sampler, VulkanImage visibility) {
        var program = context.acquireSharedProgram(VulkanSharedPrograms.Program.RR_STARS);
        try (var stack = MemoryStack.stackPush()) {
            var descriptors = VulkanDescriptors.bind(context, stack, program.descriptorSetLayout(), "RR stars",
                    VulkanDescriptors.image(0, VK12.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE,
                            output.view(), VK12.VK_IMAGE_LAYOUT_GENERAL),
                    VulkanDescriptors.sampledImage(1, starmap.view(),
                            VK12.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, sampler),
                    VulkanDescriptors.image(2, VK12.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE,
                            atmosphere.cameraTransmittance().view(), VK12.VK_IMAGE_LAYOUT_GENERAL),
                    VulkanDescriptors.image(3, VK12.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE,
                            visibility.view(), VK12.VK_IMAGE_LAYOUT_GENERAL));
            return new DlssRrStarsPass(program, descriptors, output);
        } catch (RuntimeException exception) {
            program.release();
            throw exception;
        }
    }

    void record(VkCommandBuffer commandBuffer, ReconstructionFrameParameters parameters) {
        var stars = parameters.stars();
        if (stars.cameraInWater() || stars.multiplier() == 0.0F) return;
        VulkanSync.memoryBarrier(commandBuffer, VK12.VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
                VK12.VK_ACCESS_MEMORY_WRITE_BIT, VK12.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                VK12.VK_ACCESS_SHADER_READ_BIT | VK12.VK_ACCESS_SHADER_WRITE_BIT);
        try (var stack = MemoryStack.stackPush()) {
            var push = stack.calloc(104).order(ByteOrder.nativeOrder());
            parameters.camera().inverseViewProjection().get(0, push);
            push.putFloat(64, parameters.sunDirection().x());
            push.putFloat(68, parameters.sunDirection().y());
            push.putFloat(72, parameters.sunDirection().z());
            push.putFloat(76, AtmosphereCoordinates.eyeRadiusKm(parameters.camera().y()));
            push.putFloat(80, (float) Math.toRadians(stars.astronomy().latitudeDegrees()));
            push.putFloat(84, (float) Math.toRadians(stars.astronomy().solarLongitudeDegrees()));
            push.putFloat(88, stars.multiplier() * ShaderAbi.STARMAP_BASE_RADIANCE_SCALE);
            push.putFloat(96, parameters.jitter().x());
            push.putFloat(100, parameters.jitter().y());
            this.program.dispatch(commandBuffer, stack, this.descriptors.handle(), push,
                    this.dispatchX, this.dispatchY);
        }
        VulkanSync.memoryBarrier(commandBuffer, VK12.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                VK12.VK_ACCESS_SHADER_WRITE_BIT, VK12.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                VK12.VK_ACCESS_SHADER_READ_BIT);
    }

    @Override public void destroy() {
        if (this.destroyed) return;
        this.destroyed = true;
        this.descriptors.destroy();
        this.program.release();
    }
}
