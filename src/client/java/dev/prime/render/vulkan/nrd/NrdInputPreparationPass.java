package dev.prime.render.vulkan.nrd;

import com.mojang.blaze3d.vulkan.Destroyable;
import dev.prime.render.FrameCamera;
import dev.prime.render.post.nrd.NrdCameraTransform;
import dev.prime.render.shader.ShaderAbi;
import dev.prime.render.vulkan.VulkanContext;
import dev.prime.render.vulkan.VulkanDescriptors;
import dev.prime.render.vulkan.VulkanDescriptors.BoundSet;
import dev.prime.render.vulkan.VulkanImage;
import dev.prime.render.vulkan.VulkanSharedPrograms.SharedComputeProgram;
import dev.prime.render.vulkan.VulkanSharedPrograms.Program;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import org.joml.Matrix4f;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;

final class NrdInputPreparationPass implements Destroyable {
    private static final int PUSH_SIZE = ShaderAbi.NRD_MOTION_PUSH_CONSTANT_SIZE;
    private final SharedComputeProgram program;
    private final BoundSet descriptors;
    private final Matrix4f currentClipToWorld = new Matrix4f();
    private final Matrix4f previousWorldToClip = new Matrix4f();
    private final Matrix4f worldToViewScratch = new Matrix4f();
    private boolean destroyed;

    private NrdInputPreparationPass(
            SharedComputeProgram program,
            BoundSet descriptors) {
        this.program = program;
        this.descriptors = descriptors;
    }

    static NrdInputPreparationPass create(
            VulkanContext context,
            NrdImages images,
            String debugPrefix) {
        SharedComputeProgram program = context.acquireSharedProgram(
                Program.NRD_MOTION);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VulkanImage[] descriptorImages = new VulkanImage[] {
                images.motion(),
                images.viewZ(),
                images.primaryPosition(),
                images.fsrDepth(),
                images.noisyDiffuse(),
                images.noisySpecular(),
                images.normalRoughness(),
                images.material(),
                images.specularMaterial(),
                images.noisyDiffuseSh1(),
                images.noisySpecularSh1(),
                images.reflectionMotion(),
                images.reflectionViewZ(),
                images.reflectionPosition(),
                images.reflectionNoisyDiffuse(),
                images.reflectionNoisySpecular(),
                images.reflectionNormalRoughness(),
                images.reflectionMaterial(),
                images.reflectionSpecularMaterial(),
                images.reflectionNoisyDiffuseSh1(),
                images.reflectionNoisySpecularSh1(),
                images.displayPosition(),
                images.fsrMotion(),
                images.reconstructionControl()
            };
            NrdDenoiser.validateMotionBindings(
                    descriptorImages,
                    images.motion(),
                    images.fsrMotion(),
                    images.reconstructionControl());
            BoundSet descriptors = VulkanDescriptors.bindStorageImages(
                    context,
                    stack,
                    program.descriptorSetLayout(),
                    List.of(descriptorImages),
                    debugPrefix + " motion");
            return new NrdInputPreparationPass(
                    program,
                    descriptors);
        } catch (RuntimeException exception) {
            program.release();
            throw exception;
        }
    }

    void record(
            VkCommandBuffer commandBuffer,
            FrameCamera camera,
            FrameCamera previous,
            int width,
            int height,
            float cameraJitterX,
            float cameraJitterY) {
        NrdCameraTransform.currentClipToWorld(camera, this.currentClipToWorld);
        NrdCameraTransform.previousWorldToClip(
                camera, previous, this.previousWorldToClip, this.worldToViewScratch);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            ByteBuffer push = stack.malloc(PUSH_SIZE).order(ByteOrder.nativeOrder());
            NrdMotionConstants.write(
                    push,
                    this.currentClipToWorld,
                    this.previousWorldToClip,
                    cameraJitterX,
                    cameraJitterY);
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
