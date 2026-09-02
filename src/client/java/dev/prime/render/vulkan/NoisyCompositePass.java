package dev.prime.render.vulkan;

import com.mojang.blaze3d.vulkan.Destroyable;
import dev.prime.render.AerialEpipolarMapping;
import dev.prime.render.FrameCamera;
import dev.prime.render.SunDirection;
import dev.prime.render.vulkan.VulkanDescriptors.StorageImageSet;
import dev.prime.render.vulkan.VulkanSharedPrograms.SharedComputeProgram;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkCommandBuffer;

/** Sums the raw estimator partitions into native-resolution linear HDR without filtering. */
final class NoisyCompositePass implements Destroyable {
    private static final int IMAGE_COUNT = 8;
    private static final int PUSH_SIZE = 24;
    private static final int COMPUTE_STAGE = VK12.VK_SHADER_STAGE_COMPUTE_BIT;
    private static final String SHADER =
            GeneratedShaderPrograms.resource("noisy_composite");

    private final SharedComputeProgram program;
    private final StorageImageSet descriptors;
    private final AtmospherePipeline atmosphere;
    private final int width;
    private final int height;
    private boolean destroyed;

    private NoisyCompositePass(
            SharedComputeProgram program,
            StorageImageSet descriptors,
            AtmospherePipeline atmosphere,
            int width,
            int height) {
        this.program = program;
        this.descriptors = descriptors;
        this.atmosphere = atmosphere;
        this.width = width;
        this.height = height;
    }

    static NoisyCompositePass create(
            VulkanContext context,
            BasicRawWavefrontFrame signals,
            VulkanImage stableRadiance,
            AtmospherePipeline atmosphere) {
        List<VulkanImage> images = List.of(
                signals.noisyDiffuse(),
                signals.noisySpecular(),
                signals.material(),
                stableRadiance,
                signals.sunLighting(),
                atmosphere.aerialRadiance(),
                atmosphere.aerialTransmittance(),
                signals.linearOutput());
        SharedComputeProgram program = null;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            program = SharedComputeProgram.createStorageImages(
                    context,
                    "noisy-composite",
                    PUSH_SIZE,
                    IMAGE_COUNT,
                    SHADER);
            StorageImageSet descriptors = VulkanDescriptors.bindStorageImages(
                    context,
                    stack,
                    program.descriptorSetLayout(),
                    images,
                    "noisy-composite");
            return new NoisyCompositePass(
                    program,
                    descriptors,
                    atmosphere,
                    signals.linearOutput().width(),
                    signals.linearOutput().height());
        } catch (RuntimeException exception) {
            if (program != null) {
                program.release();
            }
            throw exception;
        }
    }

    void record(
            VkCommandBuffer commandBuffer,
            FrameCamera camera,
            SunDirection sunDirection,
            float sunRadianceMultiplier) {
        VulkanSync.memoryBarrier(
                commandBuffer,
                VK12.VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
                VK12.VK_ACCESS_MEMORY_WRITE_BIT,
                VK12.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                VK12.VK_ACCESS_SHADER_READ_BIT | VK12.VK_ACCESS_SHADER_WRITE_BIT);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            ByteBuffer push = stack.calloc(PUSH_SIZE).order(ByteOrder.nativeOrder());
            push.putInt(0, this.width);
            push.putInt(4, this.height);
            push.putFloat(8, sunRadianceMultiplier);
            AerialEpipolarMapping.Epipole epipole =
                    this.atmosphere.aerialEpipole(camera, sunDirection);
            push.putFloat(16, epipole.x());
            push.putFloat(20, epipole.y());
            VK12.vkCmdBindPipeline(
                    commandBuffer,
                    VK12.VK_PIPELINE_BIND_POINT_COMPUTE,
                    this.program.pipeline(0));
            VK12.vkCmdBindDescriptorSets(
                    commandBuffer,
                    VK12.VK_PIPELINE_BIND_POINT_COMPUTE,
                    this.program.pipelineLayout(),
                    0,
                    stack.longs(this.descriptors.handle()),
                    null);
            VK12.vkCmdPushConstants(
                    commandBuffer, this.program.pipelineLayout(), COMPUTE_STAGE, 0, push);
            VK12.vkCmdDispatch(
                    commandBuffer, (this.width + 7) / 8, (this.height + 7) / 8, 1);
        }
        VulkanSync.memoryBarrier(
                commandBuffer,
                VK12.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                VK12.VK_ACCESS_SHADER_WRITE_BIT,
                VK12.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                VK12.VK_ACCESS_SHADER_READ_BIT);
    }

    @Override
    public void destroy() {
        if (this.destroyed) return;
        this.destroyed = true;
        this.descriptors.destroy();
        this.program.release();
    }
}
