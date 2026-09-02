package dev.prime.render.vulkan.dlss;

import dev.prime.render.vulkan.GeneratedShaderPrograms;
import com.mojang.blaze3d.vulkan.Destroyable;
import dev.prime.render.FrameCamera;
import dev.prime.render.SunDirection;
import dev.prime.render.post.SubpixelJitter;
import dev.prime.render.vulkan.AtmospherePipeline;
import dev.prime.render.vulkan.VulkanContext;
import dev.prime.render.vulkan.VulkanDescriptors;
import dev.prime.render.vulkan.VulkanDescriptors.StorageImageSet;
import dev.prime.render.vulkan.VulkanImage;
import dev.prime.render.vulkan.DispatchMath;
import dev.prime.render.vulkan.VulkanSharedPrograms.SharedComputeProgram;
import dev.prime.render.vulkan.VulkanSync;
import dev.prime.render.post.nrd.NrdCameraTransform;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import org.joml.Matrix4f;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.KHRRayTracingPipeline;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkCommandBuffer;

/** Converts raw path-tracing signals into the exact low-resolution image set submitted to NGX. */
final class DlssRrPreparePass implements Destroyable {
    static final int IMAGE_COUNT = 17;
    private static final int COMPUTE_STAGE = VK12.VK_SHADER_STAGE_COMPUTE_BIT;
    private static final int LOCAL_SIZE = 8;
    private static final String SHADER = GeneratedShaderPrograms.resource("rr_prepare");

    private final SharedComputeProgram program;
    private final StorageImageSet descriptors;
    private final AtmospherePipeline atmosphere;
    private final int dispatchX;
    private final int dispatchY;
    private final Matrix4f currentClipToWorld = new Matrix4f();
    private final Matrix4f previousWorldToClip = new Matrix4f();
    private boolean destroyed;

    private DlssRrPreparePass(
            SharedComputeProgram program,
            StorageImageSet descriptors,
            AtmospherePipeline atmosphere,
            int width,
            int height) {
        this.program = program;
        this.descriptors = descriptors;
        this.atmosphere = atmosphere;
        this.dispatchX = DispatchMath.divideRoundUp(width, LOCAL_SIZE);
        this.dispatchY = DispatchMath.divideRoundUp(height, LOCAL_SIZE);
    }

    static DlssRrPreparePass create(
            VulkanContext context,
            DlssRrTargets targets,
            VulkanImage stableRadiance,
            AtmospherePipeline atmosphere) {
        List<VulkanImage> images = List.of(
                targets.noisyDiffuse(),
                targets.noisySpecular(),
                targets.material(),
                targets.specularMaterial(),
                targets.viewZ(),
                targets.primaryPosition(),
                targets.sunLighting(),
                stableRadiance,
                atmosphere.aerialRadiance(),
                atmosphere.aerialTransmittance(),
                targets.inputColor(),
                targets.motion(),
                targets.specularMotion(),
                targets.reflectionPosition(),
                targets.specularHitDistance(),
                targets.responsivity(),
                targets.reconstructionControl());
        SharedComputeProgram program = null;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            program = SharedComputeProgram.createStorageImages(
                    context,
                    "RR prepare",
                    DlssRrPrepareConstants.SIZE,
                    IMAGE_COUNT,
                    SHADER);
            StorageImageSet descriptors = VulkanDescriptors.bindStorageImages(
                    context,
                    stack,
                    program.descriptorSetLayout(),
                    images,
                    "RR prepare");
            return new DlssRrPreparePass(
                    program, descriptors,
                    atmosphere, targets.inputColor().width(), targets.inputColor().height());
        } catch (RuntimeException exception) {
            if (program != null) program.release();
            throw exception;
        }
    }

    void record(
            VkCommandBuffer commandBuffer,
            FrameCamera camera,
            FrameCamera previousCamera,
            SubpixelJitter currentJitterPixels,
            SunDirection sunDirection,
            float sunRadianceMultiplier,
            float responsivity) {
        VulkanSync.memoryBarrier(
                commandBuffer,
                KHRRayTracingPipeline.VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR,
                VK12.VK_ACCESS_SHADER_WRITE_BIT,
                VK12.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                VK12.VK_ACCESS_SHADER_READ_BIT | VK12.VK_ACCESS_SHADER_WRITE_BIT);
        NrdCameraTransform.currentClipToWorld(camera, this.currentClipToWorld);
        NrdCameraTransform.previousWorldToClip(
                camera, previousCamera, this.previousWorldToClip, new Matrix4f());
        try (MemoryStack stack = MemoryStack.stackPush()) {
            ByteBuffer push = stack.malloc(DlssRrPrepareConstants.SIZE)
                    .order(ByteOrder.nativeOrder());
            DlssRrPrepareConstants.write(
                    push,
                    this.currentClipToWorld,
                    this.previousWorldToClip,
                    camera.viewRotation(),
                    sunRadianceMultiplier,
                    responsivity,
                    this.atmosphere.aerialEpipole(camera, sunDirection),
                    currentJitterPixels);
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
            VK12.vkCmdDispatch(commandBuffer, this.dispatchX, this.dispatchY, 1);
        }
        VulkanSync.memoryBarrier(
                commandBuffer,
                VK12.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                VK12.VK_ACCESS_SHADER_WRITE_BIT,
                VK12.VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
                VK12.VK_ACCESS_MEMORY_READ_BIT | VK12.VK_ACCESS_MEMORY_WRITE_BIT);
    }

    @Override
    public void destroy() {
        if (this.destroyed) return;
        this.destroyed = true;
        this.descriptors.destroy();
        this.program.release();
    }
}
