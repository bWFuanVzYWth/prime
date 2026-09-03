package dev.prime.render.vulkan;

import com.mojang.blaze3d.vulkan.Destroyable;
import dev.prime.infrastructure.ResourceCleanup;
import java.nio.LongBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.KHRAccelerationStructure;
import org.lwjgl.vulkan.VkAccelerationStructureCreateInfoKHR;
import org.lwjgl.vulkan.VkAccelerationStructureDeviceAddressInfoKHR;
import org.lwjgl.vulkan.VkDevice;

public final class AccelerationStructure implements Destroyable {
    private final VkDevice device;
    private final long handle;
    private final long deviceAddress;
    private final VulkanBuffer backingBuffer;
    private boolean destroyed;

    AccelerationStructure(VkDevice device, long handle, long deviceAddress, VulkanBuffer backingBuffer) {
        this.device = device;
        this.handle = handle;
        this.deviceAddress = deviceAddress;
        this.backingBuffer = backingBuffer;
    }

    static AccelerationStructure create(
            VulkanContext context, long size, int type, String label) {
        VulkanBuffer backing = context.createBuffer(
                size,
                KHRAccelerationStructure.VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_STORAGE_BIT_KHR,
                false,
                label + " backing");
        long handle = 0L;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkAccelerationStructureCreateInfoKHR createInfo =
                    VkAccelerationStructureCreateInfoKHR.calloc(stack)
                            .sType$Default()
                            .buffer(backing.handle())
                            .offset(0L)
                            .size(size)
                            .type(type);
            LongBuffer handlePointer = stack.mallocLong(1);
            VulkanContext.check(
                    KHRAccelerationStructure.vkCreateAccelerationStructureKHR(
                            context.vkDevice(), createInfo, null, handlePointer),
                    "create " + label);
            handle = handlePointer.get(0);
            context.device().instance().debug().setObjectName(
                    context.vkDevice(),
                    KHRAccelerationStructure.VK_OBJECT_TYPE_ACCELERATION_STRUCTURE_KHR,
                    handle,
                    label);
            VkAccelerationStructureDeviceAddressInfoKHR addressInfo =
                    VkAccelerationStructureDeviceAddressInfoKHR.calloc(stack)
                            .sType$Default()
                            .accelerationStructure(handle);
            long address = KHRAccelerationStructure.vkGetAccelerationStructureDeviceAddressKHR(
                    context.vkDevice(), addressInfo);
            return new AccelerationStructure(context.vkDevice(), handle, address, backing);
        } catch (RuntimeException exception) {
            if (handle != 0L) {
                KHRAccelerationStructure.vkDestroyAccelerationStructureKHR(
                        context.vkDevice(), handle, null);
            }
            throw ResourceCleanup.destroy(backing, exception);
        }
    }

    public long handle() {
        return this.handle;
    }

    public long deviceAddress() {
        return this.deviceAddress;
    }

    public long backingSize() {
        return this.backingBuffer.size();
    }

    @Override
    public void destroy() {
        if (!this.destroyed) {
            this.destroyed = true;
            KHRAccelerationStructure.vkDestroyAccelerationStructureKHR(this.device, this.handle, null);
            this.backingBuffer.destroy();
        }
    }
}
