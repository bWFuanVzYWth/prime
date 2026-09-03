package dev.prime.render.vulkan.nrd;

import dev.prime.render.vulkan.GeneratedShaderPrograms;
import com.mojang.blaze3d.vulkan.Destroyable;
import dev.prime.render.vulkan.AtmospherePipeline;
import dev.prime.render.vulkan.VulkanContext;
import dev.prime.render.vulkan.VulkanDescriptors;
import dev.prime.render.vulkan.VulkanDescriptors.StorageImageSet;
import dev.prime.render.vulkan.VulkanImage;
import dev.prime.render.vulkan.VulkanSharedPrograms.SharedComputeProgram;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkCommandBuffer;

final class NrdCompositePass implements Destroyable {
    private static final int COMPUTE_STAGE = VK12.VK_SHADER_STAGE_COMPUTE_BIT;
    private static final int BINDING_COUNT = 28;
    private static final int PUSH_SIZE = NrdCompositeConstants.SIZE;
    private final SharedComputeProgram program;
    private final StorageImageSet descriptors;
    private boolean destroyed;

    private NrdCompositePass(
            SharedComputeProgram program,
            StorageImageSet descriptors) {
        this.program = program;
        this.descriptors = descriptors;
    }

    static NrdCompositePass create(
            VulkanContext context,
            VulkanImage output,
            VulkanImage stableAccumulation,
            NrdImages images,
            AtmospherePipeline atmosphere) {
        SharedComputeProgram program = null;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            program = SharedComputeProgram.createStorageImages(
                    context,
                    "Prime NRD composite",
                    PUSH_SIZE,
                    BINDING_COUNT,
                    GeneratedShaderPrograms.resource("nrd_composite"));

            List<VulkanImage> descriptorImages = List.of(
                output,
                images.denoisedDiffuse(),
                images.denoisedSpecular(),
                images.material(),
                images.specularMaterial(),
                stableAccumulation,
                atmosphere.aerialRadiance(),
                atmosphere.aerialTransmittance(),
                images.fsrReactiveMask(),
                images.fsrTransparencyCompositionMask(),
                images.sunLighting(),
                images.sunShadow(),
                images.denoisedDiffuseSh1(),
                images.denoisedSpecularSh1(),
                images.normalRoughness(),
                images.viewZ(),
                images.primaryPosition(),
                images.noisySpecular(),
                images.reflectionDenoisedDiffuse(),
                images.reflectionDenoisedSpecular(),
                images.reflectionMaterial(),
                images.reflectionSpecularMaterial(),
                images.reflectionDenoisedDiffuseSh1(),
                images.reflectionDenoisedSpecularSh1(),
                images.reflectionNormalRoughness(),
                images.reflectionViewZ(),
                images.reflectionPosition(),
                images.displayPosition());
            StorageImageSet descriptors = VulkanDescriptors.bindStorageImages(
                    context,
                    stack,
                    program.descriptorSetLayout(),
                    descriptorImages,
                    "Prime NRD composite");
            return new NrdCompositePass(
                    program,
                    descriptors);
        } catch (RuntimeException exception) {
            if (program != null) {
                program.release();
            }
            throw exception;
        }
    }

    void record(
            VkCommandBuffer commandBuffer,
            int width,
            int height,
            float sunRadianceMultiplier,
            float cameraJitterX,
            float cameraJitterY,
            float epipoleX,
            float epipoleY) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
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
            ByteBuffer push = stack.calloc(PUSH_SIZE).order(ByteOrder.nativeOrder());
            NrdCompositeConstants.write(
                    push,
                    width,
                    height,
                    sunRadianceMultiplier,
                    cameraJitterX,
                    cameraJitterY,
                    epipoleX,
                    epipoleY);
            VK12.vkCmdPushConstants(
                    commandBuffer,
                    this.program.pipelineLayout(),
                    COMPUTE_STAGE,
                    0,
                    push);
            VK12.vkCmdDispatch(commandBuffer, (width + 7) / 8, (height + 7) / 8, 1);
        }
    }

    @Override
    public void destroy() {
        if (!this.destroyed) {
            this.destroyed = true;
            this.descriptors.destroy();
            this.program.release();
        }
    }
}
