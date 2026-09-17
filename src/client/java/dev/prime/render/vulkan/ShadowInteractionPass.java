// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

package dev.prime.render.vulkan;

import com.mojang.blaze3d.vulkan.Destroyable;
import dev.prime.infrastructure.ResourceCleanup;
import dev.prime.render.shader.ShaderAbi;
import dev.prime.render.vulkan.terrain.TerrainScene;
import dev.prime.render.vulkan.terrain.TerrainScene.ShadowSurface;
import java.nio.ByteBuffer;
import java.nio.LongBuffer;
import java.util.List;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.*;

/** TraceBackend owns the sidecar and compiler on the render thread; all users borrow bindings.
 * Queue barriers serialize animation/reuse writes after previous readers, and deferred retirement
 * preserves replaced buffers and immutable descriptor sets until their last frame completes. */
public final class ShadowInteractionPass implements Destroyable {
    private static final int PUSH_SIZE = 24;
    private static final int COMPUTE = VK12.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT;
    private static final int RAY_TRACE = KHRRayTracingPipeline.VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR;
    private final VulkanContext context;
    private final ShadowInteractionUpdates updates = new ShadowInteractionUpdates();
    private final long descriptorLayout;
    private final long pipelineLayout;
    private final long pipeline;
    private VulkanBuffer output;
    private VulkanDescriptors.BoundSet descriptors;
    private MaterialTexturePages.Binding textures;
    private TerrainScene.SurfaceBinding surfaces;
    private TerrainScene.TintSampleBinding tints;
    private long sampler;
    private List<ShadowSurface> pending;
    private boolean destroyed;

    ShadowInteractionPass(VulkanContext context) {
        this.context = context;
        long layout = 0L;
        long programLayout = 0L;
        long program = 0L;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDescriptorSetLayoutBinding.Buffer bindings = VkDescriptorSetLayoutBinding.calloc(4, stack);
            int[] buffers = {ShaderAbi.DESCRIPTOR_TEXTURE_RECORDS,
                    ShaderAbi.DESCRIPTOR_TINT_SAMPLES, ShaderAbi.DESCRIPTOR_SURFACE_RECORDS};
            for (int i = 0; i < buffers.length; i++) {
                VulkanDescriptors.layoutBinding(bindings.get(i), buffers[i],
                        VK12.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, 1, VK12.VK_SHADER_STAGE_COMPUTE_BIT);
            }
            VulkanDescriptors.layoutBinding(bindings.get(3), ShaderAbi.DESCRIPTOR_BASE_COLOR_PAGES,
                    VK12.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, ShaderAbi.BASE_COLOR_PAGE_COUNT,
                    VK12.VK_SHADER_STAGE_COMPUTE_BIT);
            layout = VulkanDescriptors.createSetLayout(context, stack, bindings, "shadow interaction layout");
            programLayout = VulkanDescriptors.createPipelineLayout(context, stack, layout,
                    VkPushConstantRange.calloc(1, stack).stageFlags(VK12.VK_SHADER_STAGE_COMPUTE_BIT)
                            .offset(0).size(PUSH_SIZE), "shadow interaction pipeline layout");
            long shader = VulkanShaderModules.create(context, stack,
                    GeneratedShaderPrograms.resource("shadow_interaction"));
            try {
                VkPipelineShaderStageCreateInfo stage = VkPipelineShaderStageCreateInfo.calloc(stack)
                        .sType$Default().stage(VK12.VK_SHADER_STAGE_COMPUTE_BIT)
                        .module(shader).pName(stack.UTF8("main"));
                VkComputePipelineCreateInfo.Buffer info = VkComputePipelineCreateInfo.calloc(1, stack);
                info.get(0).sType$Default().stage(stage).layout(programLayout);
                LongBuffer pointer = stack.mallocLong(1);
                context.createComputePipeline(info, pointer, "Prime shadow interaction compiler");
                program = pointer.get(0);
            } finally {
                VK12.vkDestroyShaderModule(context.vkDevice(), shader, null);
            }
            this.descriptorLayout = layout;
            this.pipelineLayout = programLayout;
            this.pipeline = program;
        } catch (RuntimeException failure) {
            if (program != 0L) VK12.vkDestroyPipeline(context.vkDevice(), program, null);
            if (programLayout != 0L) VK12.vkDestroyPipelineLayout(context.vkDevice(), programLayout, null);
            if (layout != 0L) VK12.vkDestroyDescriptorSetLayout(context.vkDevice(), layout, null);
            throw failure;
        }
    }

    VulkanBuffer output() { return this.output; }

    void ensure(MaterialTexturePages.Binding textures, TerrainScene.SurfaceBinding surfaces,
            TerrainScene.TintSampleBinding tints, long sampler) {
        if (this.pending != null) throw new IllegalStateException("Shadow compilation is pending");
        this.updates.ensure(surfaces.shadows());
        // SurfaceKeys are sparse, but scenes without glass need only the empty descriptor slot.
        List<ShadowSurface> entries = surfaces.shadows().entries();
        long required = entries.isEmpty() ? ShaderAbi.SHADOW_INTERACTION_RECORD_SIZE
                : ((long) entries.getLast().key() + 1L) * ShaderAbi.SHADOW_INTERACTION_RECORD_SIZE;
        if (required > this.context.maxStorageBufferRange()) {
            throw new IllegalArgumentException("Shadow interactions exceed the device storage-buffer range");
        }
        long capacity = this.output == null ? ShaderAbi.SHADOW_INTERACTION_RECORD_SIZE : this.output.size();
        while (capacity < required) capacity = Math.min(this.context.maxStorageBufferRange(), capacity * 2L);
        if (this.output == null || this.output.size() < capacity) {
            VulkanBuffer replacement = this.context.createBuffer(capacity,
                    VK12.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK12.VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT,
                    false, "Prime shadow interactions");
            VulkanBuffer previous = this.output;
            this.output = replacement;
            this.updates.invalidate();
            if (previous != null) this.context.defer(previous);
        }
        if (this.descriptors != null && this.textures == textures && this.sampler == sampler
                && this.surfaces.buffer() == surfaces.buffer() && this.surfaces.bytes() == surfaces.bytes()
                && this.tints.equals(tints)) return;
        VulkanDescriptors.BoundSet replacement = bind(textures, surfaces, tints, sampler);
        VulkanDescriptors.BoundSet previous = this.descriptors;
        if (this.textures != textures || this.sampler != sampler || !tints.equals(this.tints)) {
            this.updates.invalidate();
        }
        this.descriptors = replacement;
        this.textures = textures;
        this.surfaces = surfaces;
        this.tints = tints;
        this.sampler = sampler;
        if (previous != null) this.context.defer(previous);
    }

    private VulkanDescriptors.BoundSet bind(MaterialTexturePages.Binding textures,
            TerrainScene.SurfaceBinding surfaces, TerrainScene.TintSampleBinding tints, long sampler) {
        long pool = 0L;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDescriptorPoolSize.Buffer sizes = VkDescriptorPoolSize.calloc(2, stack);
            sizes.get(0).type(VK12.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(3);
            sizes.get(1).type(VK12.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                    .descriptorCount(ShaderAbi.BASE_COLOR_PAGE_COUNT);
            pool = VulkanDescriptors.createPool(this.context, stack, 1, sizes, "shadow interaction pool");
            long set = VulkanDescriptors.allocateSet(this.context, stack, pool,
                    this.descriptorLayout, "shadow interaction set");
            VkDescriptorBufferInfo.Buffer buffers = VkDescriptorBufferInfo.calloc(3, stack);
            buffers.get(0).buffer(textures.textureRecords().handle()).offset(0)
                    .range(textures.textureRecords().size());
            buffers.get(1).buffer(tints.buffer()).offset(0).range(tints.bytes());
            buffers.get(2).buffer(surfaces.buffer()).offset(0).range(surfaces.bytes());
            VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(4, stack);
            int[] bindings = {ShaderAbi.DESCRIPTOR_TEXTURE_RECORDS,
                    ShaderAbi.DESCRIPTOR_TINT_SAMPLES, ShaderAbi.DESCRIPTOR_SURFACE_RECORDS};
            for (int i = 0; i < bindings.length; i++) {
                VulkanDescriptors.writeBuffer(writes.get(i), set, bindings[i],
                        VK12.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, buffers.get(i));
            }
            VkDescriptorImageInfo.Buffer images = VkDescriptorImageInfo.calloc(ShaderAbi.BASE_COLOR_PAGE_COUNT, stack);
            for (int i = 0; i < images.capacity(); i++) {
                VulkanImage image = textures.baseColorPages().get(Math.min(i, textures.baseColorPages().size() - 1));
                images.get(i).sampler(sampler).imageView(image.view())
                        .imageLayout(VK12.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
            }
            VulkanDescriptors.writeImages(writes.get(3), set, ShaderAbi.DESCRIPTOR_BASE_COLOR_PAGES,
                    VK12.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, images.get(0), images.capacity());
            VK12.vkUpdateDescriptorSets(this.context.vkDevice(), writes, null);
            return new VulkanDescriptors.BoundSet(this.context, pool, set);
        } catch (RuntimeException failure) {
            if (pool != 0L) VK12.vkDestroyDescriptorPool(this.context.vkDevice(), pool, null);
            throw failure;
        }
    }

    boolean prepare(VkCommandBuffer command, int[] changedTextures) {
        if (this.pending != null) throw new IllegalStateException("Shadow compilation is pending");
        List<ShadowSurface> requests = this.updates.prepare(changedTextures);
        if (requests.isEmpty()) return false;
        VulkanBuffer input = this.context.createBuffer((long) requests.size() * 8L,
                VK12.VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT, true, "Prime shadow compilation requests");
        try {
            long address = input.mappedAddress();
            for (int i = 0; i < requests.size(); i++) {
                ShadowSurface request = requests.get(i);
                MemoryUtil.memPutInt(address + (long) i * 8L, request.key());
                MemoryUtil.memPutInt(address + (long) i * 8L + 4L, request.textureId());
            }
            input.flush(0L, input.size());
            // Scene uploads feed this compute read. Previous any-hit reads must finish before
            // overwriting an animation result in the shared sidecar.
            VulkanSync.memoryBarrier(command, VK12.VK_PIPELINE_STAGE_TRANSFER_BIT | RAY_TRACE | COMPUTE,
                    VK12.VK_ACCESS_TRANSFER_WRITE_BIT | VK12.VK_ACCESS_SHADER_READ_BIT | VK12.VK_ACCESS_SHADER_WRITE_BIT,
                    COMPUTE, VK12.VK_ACCESS_SHADER_READ_BIT | VK12.VK_ACCESS_SHADER_WRITE_BIT);
            try (MemoryStack stack = MemoryStack.stackPush()) {
                VK12.vkCmdBindPipeline(command, VK12.VK_PIPELINE_BIND_POINT_COMPUTE, this.pipeline);
                VK12.vkCmdBindDescriptorSets(command, VK12.VK_PIPELINE_BIND_POINT_COMPUTE,
                        this.pipelineLayout, 0, stack.longs(this.descriptors.handle()), null);
                // Split before the guaranteed Vulkan X workgroup-count limit (65535).
                int maximum = 65535 * 64;
                for (int first = 0; first < requests.size(); first += maximum) {
                    int count = Math.min(maximum, requests.size() - first);
                    ByteBuffer push = stack.calloc(PUSH_SIZE);
                    push.putLong(0, input.deviceAddress() + (long) first * 8L);
                    push.putLong(8, this.output.deviceAddress());
                    push.putInt(16, count);
                    VK12.vkCmdPushConstants(command, this.pipelineLayout,
                            VK12.VK_SHADER_STAGE_COMPUTE_BIT, 0, push);
                    VK12.vkCmdDispatch(command, (count + 63) / 64, 1, 1);
                }
            }
            VulkanSync.memoryBarrier(command, COMPUTE, VK12.VK_ACCESS_SHADER_WRITE_BIT,
                    RAY_TRACE, VK12.VK_ACCESS_SHADER_READ_BIT);
            this.pending = requests;
        } finally {
            // Covers accepted and abandoned command buffers using the existing host retirement.
            this.context.defer(input);
        }
        return true;
    }

    void submitted() {
        if (this.pending == null) return;
        this.updates.submitted(this.pending);
        this.pending = null;
    }

    void abandon() { this.pending = null; }

    @Override public void destroy() {
        if (this.destroyed) return;
        this.destroyed = true;
        RuntimeException failure = ResourceCleanup.destroy(this.descriptors, null);
        failure = ResourceCleanup.destroy(this.output, failure);
        VK12.vkDestroyPipeline(this.context.vkDevice(), this.pipeline, null);
        VK12.vkDestroyPipelineLayout(this.context.vkDevice(), this.pipelineLayout, null);
        VK12.vkDestroyDescriptorSetLayout(this.context.vkDevice(), this.descriptorLayout, null);
        if (failure != null) throw failure;
    }
}
