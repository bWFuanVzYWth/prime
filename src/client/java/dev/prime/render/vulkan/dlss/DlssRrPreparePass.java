package dev.prime.render.vulkan.dlss;

import dev.prime.render.vulkan.GeneratedShaderPrograms;
import com.mojang.blaze3d.vulkan.Destroyable;
import dev.prime.render.FrameCamera;
import dev.prime.render.SunDirection;
import dev.prime.render.post.SubpixelJitter;
import dev.prime.render.vulkan.AtmospherePipeline;
import dev.prime.render.vulkan.VulkanContext;
import dev.prime.render.vulkan.VulkanDescriptors;
import dev.prime.render.vulkan.VulkanImage;
import dev.prime.render.vulkan.DispatchMath;
import dev.prime.render.vulkan.VulkanShaderModules;
import dev.prime.render.vulkan.VulkanSync;
import dev.prime.render.post.nrd.NrdCameraTransform;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.LongBuffer;
import java.util.List;
import org.joml.Matrix4f;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.KHRRayTracingPipeline;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkComputePipelineCreateInfo;
import org.lwjgl.vulkan.VkDescriptorImageInfo;
import org.lwjgl.vulkan.VkDescriptorPoolSize;
import org.lwjgl.vulkan.VkDescriptorSetLayoutBinding;
import org.lwjgl.vulkan.VkPipelineShaderStageCreateInfo;
import org.lwjgl.vulkan.VkPushConstantRange;
import org.lwjgl.vulkan.VkWriteDescriptorSet;

/** Converts raw path-tracing signals into the exact low-resolution image set submitted to NGX. */
final class DlssRrPreparePass implements Destroyable {
    static final int IMAGE_COUNT = 17;
    private static final int COMPUTE_STAGE = VK12.VK_SHADER_STAGE_COMPUTE_BIT;
    private static final int LOCAL_SIZE = 8;
    private static final String SHADER = GeneratedShaderPrograms.resource("rr_prepare");

    private final VulkanContext context;
    private final long descriptorSetLayout;
    private final long descriptorPool;
    private final long descriptorSet;
    private final long pipelineLayout;
    private final long pipeline;
    private final AtmospherePipeline atmosphere;
    private final int dispatchX;
    private final int dispatchY;
    private final Matrix4f currentClipToWorld = new Matrix4f();
    private final Matrix4f previousWorldToClip = new Matrix4f();
    private boolean destroyed;

    private DlssRrPreparePass(
            VulkanContext context,
            long descriptorSetLayout,
            long descriptorPool,
            long descriptorSet,
            long pipelineLayout,
            long pipeline,
            AtmospherePipeline atmosphere,
            int width,
            int height) {
        this.context = context;
        this.descriptorSetLayout = descriptorSetLayout;
        this.descriptorPool = descriptorPool;
        this.descriptorSet = descriptorSet;
        this.pipelineLayout = pipelineLayout;
        this.pipeline = pipeline;
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
        long setLayout = 0L;
        long descriptorPool = 0L;
        long pipelineLayout = 0L;
        long pipeline = 0L;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDescriptorSetLayoutBinding.Buffer bindings =
                    VkDescriptorSetLayoutBinding.calloc(IMAGE_COUNT, stack);
            for (int binding = 0; binding < IMAGE_COUNT; binding++) {
                bindings.get(binding).binding(binding)
                        .descriptorType(VK12.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                        .descriptorCount(1)
                        .stageFlags(COMPUTE_STAGE);
            }
            setLayout = VulkanDescriptors.createSetLayout(
                    context,
                    stack,
                    bindings,
                    "create RR prepare descriptor layout");
            VkPushConstantRange.Buffer pushRange = VkPushConstantRange.calloc(1, stack)
                    .stageFlags(COMPUTE_STAGE).offset(0).size(DlssRrPrepareConstants.SIZE);
            pipelineLayout = VulkanDescriptors.createPipelineLayout(
                    context,
                    stack,
                    setLayout,
                    pushRange,
                    "create RR prepare pipeline layout");
            LongBuffer pointer = stack.mallocLong(1);
            long shader = VulkanShaderModules.create(context, stack, SHADER);
            try {
                VkPipelineShaderStageCreateInfo stage = VkPipelineShaderStageCreateInfo.calloc(stack)
                        .sType$Default().stage(COMPUTE_STAGE).module(shader).pName(stack.UTF8("main"));
                VkComputePipelineCreateInfo.Buffer createInfo = VkComputePipelineCreateInfo.calloc(1, stack);
                createInfo.get(0).sType$Default().stage(stage).layout(pipelineLayout);
                pointer.clear();
                context.createComputePipeline(createInfo, pointer, "RR prepare");
                pipeline = pointer.get(0);
            } finally {
                VK12.vkDestroyShaderModule(context.vkDevice(), shader, null);
            }
            VkDescriptorPoolSize.Buffer poolSize = VkDescriptorPoolSize.calloc(1, stack);
            poolSize.get(0).type(VK12.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(IMAGE_COUNT);
            descriptorPool = VulkanDescriptors.createPool(
                    context,
                    stack,
                    1,
                    poolSize,
                    "create RR prepare descriptor pool");
            long descriptorSet = VulkanDescriptors.allocateSet(
                    context,
                    stack,
                    descriptorPool,
                    setLayout,
                    "allocate RR prepare descriptor set");
            VkDescriptorImageInfo.Buffer imageInfos = VkDescriptorImageInfo.calloc(IMAGE_COUNT, stack);
            VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(IMAGE_COUNT, stack);
            for (int binding = 0; binding < IMAGE_COUNT; binding++) {
                imageInfos.get(binding)
                        .imageView(images.get(binding).view())
                        .imageLayout(VK12.VK_IMAGE_LAYOUT_GENERAL);
                writes.get(binding).sType$Default()
                        .dstSet(descriptorSet)
                        .dstBinding(binding)
                        .descriptorCount(1)
                        .descriptorType(VK12.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                        .pImageInfo(VkDescriptorImageInfo.create(imageInfos.get(binding).address(), 1));
            }
            VK12.vkUpdateDescriptorSets(context.vkDevice(), writes, null);
            return new DlssRrPreparePass(
                    context, setLayout, descriptorPool, descriptorSet, pipelineLayout, pipeline,
                    atmosphere, targets.inputColor().width(), targets.inputColor().height());
        } catch (RuntimeException exception) {
            if (descriptorPool != 0L) VK12.vkDestroyDescriptorPool(context.vkDevice(), descriptorPool, null);
            if (pipeline != 0L) VK12.vkDestroyPipeline(context.vkDevice(), pipeline, null);
            if (pipelineLayout != 0L) VK12.vkDestroyPipelineLayout(context.vkDevice(), pipelineLayout, null);
            if (setLayout != 0L) VK12.vkDestroyDescriptorSetLayout(context.vkDevice(), setLayout, null);
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
            VK12.vkCmdBindPipeline(commandBuffer, VK12.VK_PIPELINE_BIND_POINT_COMPUTE, this.pipeline);
            VK12.vkCmdBindDescriptorSets(
                    commandBuffer,
                    VK12.VK_PIPELINE_BIND_POINT_COMPUTE,
                    this.pipelineLayout,
                    0,
                    stack.longs(this.descriptorSet),
                    null);
            VK12.vkCmdPushConstants(
                    commandBuffer, this.pipelineLayout, COMPUTE_STAGE, 0, push);
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
        VK12.vkDestroyDescriptorPool(this.context.vkDevice(), this.descriptorPool, null);
        VK12.vkDestroyPipeline(this.context.vkDevice(), this.pipeline, null);
        VK12.vkDestroyPipelineLayout(this.context.vkDevice(), this.pipelineLayout, null);
        VK12.vkDestroyDescriptorSetLayout(this.context.vkDevice(), this.descriptorSetLayout, null);
    }
}
