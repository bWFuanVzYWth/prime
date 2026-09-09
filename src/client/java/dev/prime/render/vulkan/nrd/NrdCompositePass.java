// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

package dev.prime.render.vulkan.nrd;

import com.mojang.blaze3d.vulkan.Destroyable;
import dev.prime.render.vulkan.AtmospherePipeline;
import dev.prime.render.vulkan.VulkanContext;
import dev.prime.render.vulkan.VulkanDescriptors;
import dev.prime.render.vulkan.VulkanDescriptors.BoundSet;
import dev.prime.render.vulkan.VulkanImage;
import dev.prime.render.vulkan.VulkanSharedPrograms.SharedComputeProgram;
import dev.prime.render.vulkan.VulkanSharedPrograms.Program;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;

final class NrdCompositePass implements Destroyable {
    private static final int PUSH_SIZE = NrdCompositeConstants.SIZE;
    private final SharedComputeProgram program;
    private final BoundSet descriptors;
    private boolean destroyed;

    private NrdCompositePass(
            SharedComputeProgram program,
            BoundSet descriptors) {
        this.program = program;
        this.descriptors = descriptors;
    }

    static NrdCompositePass create(
            VulkanContext context,
            VulkanImage output,
            VulkanImage stableAccumulation,
            NrdImages images,
            AtmospherePipeline atmosphere) {
        SharedComputeProgram program = context.acquireSharedProgram(
                Program.NRD_COMPOSITE);
        try (MemoryStack stack = MemoryStack.stackPush()) {
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
            BoundSet descriptors = VulkanDescriptors.bindStorageImages(
                    context,
                    stack,
                    program.descriptorSetLayout(),
                    descriptorImages,
                    "Prime NRD composite");
            return new NrdCompositePass(
                    program,
                    descriptors);
        } catch (RuntimeException exception) {
            program.release();
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
            this.program.dispatch(
                    commandBuffer,
                    stack,
                    this.descriptors.handle(),
                    push,
                    (width + 7) / 8,
                    (height + 7) / 8);
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
