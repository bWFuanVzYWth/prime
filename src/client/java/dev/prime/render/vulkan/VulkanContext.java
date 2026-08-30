package dev.prime.render.vulkan;

import com.mojang.blaze3d.vulkan.Destroyable;
import com.mojang.blaze3d.vulkan.VulkanCommandEncoder;
import com.mojang.blaze3d.vulkan.VulkanDevice;
import com.mojang.blaze3d.vulkan.VulkanGpuTexture;
import dev.prime.infrastructure.PrimeInfo;
import dev.prime.render.vulkan.VulkanSharedPrograms.SharedComputeProgram;
import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Objects;
import java.util.Set;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.vma.Vma;
import org.lwjgl.util.vma.VmaAllocationCreateInfo;
import org.lwjgl.util.vma.VmaAllocationInfo;
import org.lwjgl.util.vma.VmaAllocatorCreateInfo;
import org.lwjgl.util.vma.VmaBudget;
import org.lwjgl.util.vma.VmaStatistics;
import org.lwjgl.util.vma.VmaTotalStatistics;
import org.lwjgl.util.vma.VmaVulkanFunctions;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkBufferCreateInfo;
import org.lwjgl.vulkan.VkBufferDeviceAddressInfo;
import org.lwjgl.vulkan.VkComputePipelineCreateInfo;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkImageCreateInfo;
import org.lwjgl.vulkan.VkImageViewCreateInfo;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties;

public final class VulkanContext implements AutoCloseable {
    private final VulkanDevice device;
    private final VulkanCapabilities capabilities;
    private final long allocator;
    private final long uniformBufferOffsetAlignment;
    private final long maxStorageBufferRange;
    private final VulkanPipelineCache pipelineCache;
    private final VulkanSharedPrograms sharedPrograms;
    private HdrPresentPass hdrPresentPass;
    private UiAlphaCapturePass uiAlphaCapturePass;
    private final Set<Destroyable> deferred = Collections.newSetFromMap(new IdentityHashMap<>());
    private boolean closed;

    public VulkanContext(VulkanDevice device, VulkanCapabilities capabilities) {
        this.device = device;
        this.capabilities = capabilities;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VmaVulkanFunctions functions = VmaVulkanFunctions.calloc(stack)
                    .set(device.vkDevice().getPhysicalDevice().getInstance(), device.vkDevice());
            VmaAllocatorCreateInfo createInfo = VmaAllocatorCreateInfo.calloc(stack)
                    .flags(Vma.VMA_ALLOCATOR_CREATE_BUFFER_DEVICE_ADDRESS_BIT)
                    .instance(device.vkDevice().getPhysicalDevice().getInstance())
                    .physicalDevice(device.vkDevice().getPhysicalDevice())
                    .device(device.vkDevice())
                    .vulkanApiVersion(VK12.VK_API_VERSION_1_2)
                    .pVulkanFunctions(functions);
            PointerBuffer pointer = stack.mallocPointer(1);
            check(Vma.vmaCreateAllocator(createInfo, pointer), "create ray tracing VMA allocator");
            this.allocator = pointer.get(0);
        }
        try {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                VkPhysicalDeviceProperties properties = VkPhysicalDeviceProperties.calloc(stack);
                VK12.vkGetPhysicalDeviceProperties(device.vkDevice().getPhysicalDevice(), properties);
                this.uniformBufferOffsetAlignment = properties.limits().minUniformBufferOffsetAlignment();
                this.maxStorageBufferRange =
                        Integer.toUnsignedLong(properties.limits().maxStorageBufferRange());
                this.pipelineCache = VulkanPipelineCache.create(device.vkDevice(), properties);
            }
            this.sharedPrograms = new VulkanSharedPrograms(this);
        } catch (RuntimeException exception) {
            Vma.vmaDestroyAllocator(this.allocator);
            throw exception;
        }
    }

    public VulkanDevice device() {
        return this.device;
    }

    public VkDevice vkDevice() {
        return this.device.vkDevice();
    }

    public VulkanCapabilities capabilities() {
        return this.capabilities;
    }

    public VulkanCommandEncoder commandEncoder() {
        requireOpen();
        return this.device.createCommandEncoder();
    }

    public long uniformBufferOffsetAlignment() {
        return this.uniformBufferOffsetAlignment;
    }

    public long maxStorageBufferRange() {
        return this.maxStorageBufferRange;
    }

    /**
     * Captures allocator totals for before/after resize, reload and backend-switch measurements.
     *
     * <p>The heap budget is VMA's estimate unless the host device enabled
     * {@code VK_EXT_memory_budget}; Prime does not turn an unavailable extension into a guessed
     * hardware budget. Allocations made entirely inside an external SDK must be reported by that
     * SDK and are not included here.
     */
    public VulkanMemorySnapshot memorySnapshot() {
        requireOpen();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VmaTotalStatistics totals = VmaTotalStatistics.calloc(stack);
            Vma.vmaCalculateStatistics(this.allocator, totals);
            VmaStatistics total = totals.total().statistics();
            VmaBudget.Buffer budgets = VmaBudget.calloc(VK12.VK_MAX_MEMORY_HEAPS, stack);
            Vma.vmaGetHeapBudgets(this.allocator, budgets);
            ArrayList<VulkanMemorySnapshot.Heap> heaps = new ArrayList<>();
            for (int index = 0; index < budgets.capacity(); index++) {
                VmaStatistics statistics = totals.memoryHeap(index).statistics();
                VmaBudget budget = budgets.get(index);
                if (statistics.blockCount() == 0
                        && statistics.allocationCount() == 0
                        && budget.budget() == 0L) {
                    continue;
                }
                heaps.add(new VulkanMemorySnapshot.Heap(
                        index,
                        statistics.blockBytes(),
                        statistics.allocationBytes(),
                        budget.usage(),
                        budget.budget()));
            }
            return new VulkanMemorySnapshot(
                    total.blockCount(),
                    total.allocationCount(),
                    total.blockBytes(),
                    total.allocationBytes(),
                    heaps);
        }
    }

    VulkanPipelineCache.Session pipelineCacheSession() {
        requireOpen();
        return this.pipelineCache.openSession();
    }

    SharedComputeProgram acquireDisplayTransformProgram() {
        requireOpen();
        return this.sharedPrograms.acquireDisplayTransform();
    }

    SharedComputeProgram acquireAutoExposureProgram() {
        requireOpen();
        return this.sharedPrograms.acquireAutoExposure();
    }

    SharedComputeProgram acquireHdrPresentProgram() {
        requireOpen();
        return this.sharedPrograms.acquireHdrPresent();
    }

    SharedComputeProgram acquireUiAlphaExtractProgram() {
        requireOpen();
        return this.sharedPrograms.acquireUiAlphaExtract();
    }

    SharedComputeProgram acquireUiAlphaClearProgram() {
        requireOpen();
        return this.sharedPrograms.acquireUiAlphaClear();
    }

    SharedComputeProgram acquireStreamlineInputProgram() {
        requireOpen();
        return this.sharedPrograms.acquireStreamlineInput();
    }

    SharedComputeProgram acquireRendererDataRangeProgram() {
        requireOpen();
        return this.sharedPrograms.acquireRendererDataRange();
    }

    public VulkanImage recordHdrPresentation(
            VkCommandBuffer commandBuffer,
            long hdrView,
            long baselineView,
            long uiView,
            int width,
            int height,
            boolean compositePrimeHdr,
            float scRgbScale) {
        requireOpen();
        if (this.hdrPresentPass == null || !this.hdrPresentPass.matches(width, height)) {
            if (this.hdrPresentPass != null) {
                this.awaitIdle();
                this.hdrPresentPass.destroy();
            }
            this.hdrPresentPass = HdrPresentPass.create(this, width, height);
        }
        this.hdrPresentPass.record(
                commandBuffer,
                hdrView,
                baselineView,
                uiView,
                compositePrimeHdr,
                scRgbScale);
        return this.hdrPresentPass.output();
    }

    public void clearMainColorAlpha(
            VkCommandBuffer commandBuffer,
            long mainColorImage,
            long mainColorView,
            int width,
            int height) {
        requireOpen();
        UiAlphaCapturePass previous = this.uiAlphaCapturePass;
        this.uiAlphaCapturePass = UiAlphaCapturePass.create(
                this, width, height, mainColorImage, mainColorView);
        if (previous != null) {
            this.defer(previous);
        }
        this.uiAlphaCapturePass.recordClear(commandBuffer);
    }

    public VulkanImage captureMainColorAlpha(
            VkCommandBuffer commandBuffer,
            VulkanGpuTexture mainColor,
            long mainColorImage,
            long mainColorView,
            int width,
            int height) {
        requireOpen();
        UiAlphaCapturePass current = this.uiAlphaCapturePass;
        if (current == null
                || !current.matches(width, height, mainColorImage, mainColorView)) {
            return null;
        }
        current.recordExtract(commandBuffer, mainColor);
        return current.alpha();
    }

    public void invalidateSharedPrograms() {
        requireOpen();
        this.sharedPrograms.invalidate();
    }

    /** Creates every size-independent presentation program before extent/frame resources use it. */
    public void prewarmSharedPrograms() {
        requireOpen();
        this.sharedPrograms.prewarm();
    }

    public void createComputePipeline(
            VkComputePipelineCreateInfo.Buffer createInfo,
            LongBuffer pipelinePointer,
            String label) {
        requireOpen();
        long started = System.nanoTime();
        try (VulkanPipelineCache.Session cache = this.pipelineCache.openSession()) {
            check(
                    VK12.vkCreateComputePipelines(
                            this.device.vkDevice(),
                            cache.handle(),
                            createInfo,
                            null,
                            pipelinePointer),
                    "create " + label);
        }
        long elapsed = System.nanoTime() - started;
        PrimeInfo.LOGGER.info("Created {} pipeline in {} us", label, elapsed / 1_000L);
    }

    public VulkanBuffer createBuffer(long size, int usage, boolean hostVisible, String label) {
        int hostFlags = hostVisible
                ? Vma.VMA_ALLOCATION_CREATE_HOST_ACCESS_SEQUENTIAL_WRITE_BIT
                        | Vma.VMA_ALLOCATION_CREATE_MAPPED_BIT
                : 0;
        return createBuffer(size, usage, hostFlags, label);
    }

    /**
     * Creates a host-readable GPU output buffer.
     *
     * <p>This is reserved for explicit diagnostics; production frame resources remain device
     * local.
     */
    public VulkanBuffer createReadbackBuffer(long size, int usage, String label) {
        return createBuffer(
                size,
                usage,
                Vma.VMA_ALLOCATION_CREATE_HOST_ACCESS_RANDOM_BIT
                        | Vma.VMA_ALLOCATION_CREATE_MAPPED_BIT,
                label);
    }

    private VulkanBuffer createBuffer(
            long size, int usage, int hostFlags, String label) {
        requireOpen();
        if (size <= 0L) {
            throw new IllegalArgumentException("Vulkan buffer size must be positive");
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkBufferCreateInfo bufferCreateInfo = VkBufferCreateInfo.calloc(stack)
                    .sType$Default()
                    .size(size)
                    .usage(usage | VK12.VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT)
                    .sharingMode(VK12.VK_SHARING_MODE_EXCLUSIVE);
            VmaAllocationCreateInfo allocationCreateInfo = VmaAllocationCreateInfo.calloc(stack)
                    .usage(hostFlags != 0
                            ? Vma.VMA_MEMORY_USAGE_AUTO_PREFER_HOST
                            : Vma.VMA_MEMORY_USAGE_AUTO_PREFER_DEVICE);
            if (hostFlags != 0) {
                allocationCreateInfo.flags(hostFlags);
            }
            LongBuffer bufferPointer = stack.mallocLong(1);
            PointerBuffer allocationPointer = stack.mallocPointer(1);
            VmaAllocationInfo allocationInfo = VmaAllocationInfo.calloc(stack);
            check(Vma.vmaCreateBuffer(
                    this.allocator,
                    bufferCreateInfo,
                    allocationCreateInfo,
                    bufferPointer,
                    allocationPointer,
                    allocationInfo), "create " + label);
            long handle = bufferPointer.get(0);
            long allocation = allocationPointer.get(0);
            try {
                this.device.instance().debug().setObjectName(
                        this.device.vkDevice(), VK12.VK_OBJECT_TYPE_BUFFER, handle, label);
                VkBufferDeviceAddressInfo addressInfo = VkBufferDeviceAddressInfo.calloc(stack)
                        .sType$Default()
                        .buffer(handle);
                long address = VK12.vkGetBufferDeviceAddress(this.device.vkDevice(), addressInfo);
                if (address == 0L) {
                    throw new IllegalStateException("Vulkan returned a null device address for " + label);
                }
                return new VulkanBuffer(
                        this.allocator,
                        allocation,
                        handle,
                        address,
                        hostFlags != 0 ? allocationInfo.pMappedData() : 0L,
                        size);
            } catch (RuntimeException exception) {
                Vma.vmaDestroyBuffer(this.allocator, handle, allocation);
                throw exception;
            }
        }
    }

    public VulkanImage createOutputImage(int width, int height) {
        return this.createImage(
                width,
                height,
                1,
                1,
                VK12.VK_FORMAT_R8G8B8A8_UNORM,
                VK12.VK_IMAGE_USAGE_STORAGE_BIT
                        | VK12.VK_IMAGE_USAGE_SAMPLED_BIT
                        | VK12.VK_IMAGE_USAGE_TRANSFER_SRC_BIT,
                "Prime output");
    }

    public VulkanImage createAccumulationImage(int width, int height) {
        return this.createImage(
                width,
                height,
                1,
                1,
                VK12.VK_FORMAT_R32G32B32A32_SFLOAT,
                VK12.VK_IMAGE_USAGE_STORAGE_BIT | VK12.VK_IMAGE_USAGE_SAMPLED_BIT,
                "Prime accumulation");
    }

    public VulkanImage createAtmosphereImage2D(int width, int height, String label) {
        return this.createImage(
                width,
                height,
                1,
                1,
                VK12.VK_FORMAT_R16G16B16A16_SFLOAT,
                VK12.VK_IMAGE_USAGE_STORAGE_BIT,
                label);
    }

    public VulkanImage createAtmosphereImage3D(int width, int height, int depth, String label) {
        return this.createImage(
                width,
                height,
                depth,
                1,
                VK12.VK_FORMAT_R16G16B16A16_SFLOAT,
                VK12.VK_IMAGE_USAGE_STORAGE_BIT,
                label);
    }

    public VulkanImage createSampledImage3D(
            int width,
            int height,
            int depth,
            int format,
            String label) {
        return this.createImage(
                width,
                height,
                depth,
                1,
                format,
                VK12.VK_IMAGE_USAGE_SAMPLED_BIT | VK12.VK_IMAGE_USAGE_TRANSFER_DST_BIT,
                label);
    }

    public VulkanImage createImage2D(int width, int height, int format, int usage, String label) {
        return this.createImage(width, height, 1, 1, format, usage, label);
    }

    /** Creates one sampled full-chain view plus one storage view per mip level. */
    public VulkanImage createMipmappedImage2D(
            int width,
            int height,
            int mipLevels,
            int format,
            int usage,
            String label) {
        if (mipLevels <= 0) {
            throw new IllegalArgumentException("Vulkan mip count must be positive");
        }
        int maximumMipLevels = 32 - Integer.numberOfLeadingZeros(Math.max(width, height));
        if (mipLevels > maximumMipLevels) {
            throw new IllegalArgumentException("Vulkan mip count exceeds the image extent");
        }
        return this.createImage(width, height, 1, mipLevels, format, usage, label);
    }

    private VulkanImage createImage(
            int width,
            int height,
            int depth,
            int mipLevels,
            int format,
            int usage,
            String label) {
        requireOpen();
        if (width <= 0 || height <= 0 || depth <= 0) {
            throw new IllegalArgumentException("Vulkan image dimensions must be positive");
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkImageCreateInfo imageCreateInfo = VkImageCreateInfo.calloc(stack)
                    .sType$Default()
                    .imageType(depth == 1 ? VK12.VK_IMAGE_TYPE_2D : VK12.VK_IMAGE_TYPE_3D)
                    .format(format)
                    .mipLevels(mipLevels)
                    .arrayLayers(1)
                    .samples(VK12.VK_SAMPLE_COUNT_1_BIT)
                    .tiling(VK12.VK_IMAGE_TILING_OPTIMAL)
                    .usage(usage)
                    .sharingMode(VK12.VK_SHARING_MODE_EXCLUSIVE)
                    .initialLayout(VK12.VK_IMAGE_LAYOUT_UNDEFINED);
            imageCreateInfo.extent().set(width, height, depth);
            VmaAllocationCreateInfo allocationCreateInfo = VmaAllocationCreateInfo.calloc(stack)
                    .usage(Vma.VMA_MEMORY_USAGE_AUTO_PREFER_DEVICE);
            LongBuffer imagePointer = stack.mallocLong(1);
            PointerBuffer allocationPointer = stack.mallocPointer(1);
            check(Vma.vmaCreateImage(
                    this.allocator,
                    imageCreateInfo,
                    allocationCreateInfo,
                    imagePointer,
                    allocationPointer,
                    null), "create " + label + " image");
            long image = imagePointer.get(0);
            long view = 0L;
            long[] mipViews = new long[mipLevels];
            int createdMipViews = 0;
            try {
                VkImageViewCreateInfo viewCreateInfo = VkImageViewCreateInfo.calloc(stack)
                        .sType$Default()
                        .image(image)
                        .viewType(depth == 1 ? VK12.VK_IMAGE_VIEW_TYPE_2D : VK12.VK_IMAGE_VIEW_TYPE_3D)
                        .format(format);
                viewCreateInfo.subresourceRange()
                        .aspectMask(VK12.VK_IMAGE_ASPECT_COLOR_BIT)
                        .baseMipLevel(0)
                        .levelCount(mipLevels)
                        .baseArrayLayer(0)
                        .layerCount(1);
                LongBuffer viewPointer = stack.mallocLong(1);
                check(
                        VK12.vkCreateImageView(this.device.vkDevice(), viewCreateInfo, null, viewPointer),
                        "create " + label + " view");
                view = viewPointer.get(0);
                if (mipLevels == 1) {
                    mipViews[0] = view;
                }
                for (int level = 0; level < (mipLevels == 1 ? 0 : mipLevels); level++) {
                    VkImageViewCreateInfo mipViewCreateInfo = VkImageViewCreateInfo.calloc(stack)
                            .sType$Default()
                            .image(image)
                            .viewType(VK12.VK_IMAGE_VIEW_TYPE_2D)
                            .format(format);
                    mipViewCreateInfo.subresourceRange()
                            .aspectMask(VK12.VK_IMAGE_ASPECT_COLOR_BIT)
                            .baseMipLevel(level)
                            .levelCount(1)
                            .baseArrayLayer(0)
                            .layerCount(1);
                    viewPointer.clear();
                    check(
                            VK12.vkCreateImageView(
                                    this.device.vkDevice(), mipViewCreateInfo, null, viewPointer),
                            "create " + label + " mip " + level + " view");
                    mipViews[level] = viewPointer.get(0);
                    createdMipViews++;
                    this.device.instance().debug().setObjectName(
                            this.device.vkDevice(),
                            VK12.VK_OBJECT_TYPE_IMAGE_VIEW,
                            mipViews[level],
                            label + " mip " + level + " view");
                }
                this.device.instance().debug().setObjectName(
                        this.device.vkDevice(), VK12.VK_OBJECT_TYPE_IMAGE, image, label);
                this.device.instance().debug().setObjectName(
                        this.device.vkDevice(), VK12.VK_OBJECT_TYPE_IMAGE_VIEW, view, label + " view");
                return new VulkanImage(
                        this.allocator,
                        this.device.vkDevice(),
                        image,
                        allocationPointer.get(0),
                        view,
                        mipViews,
                        format,
                        usage,
                        width,
                        height,
                        depth);
            } catch (RuntimeException exception) {
                for (int level = createdMipViews - 1; level >= 0; level--) {
                    VK12.vkDestroyImageView(this.device.vkDevice(), mipViews[level], null);
                }
                if (view != 0L) {
                    VK12.vkDestroyImageView(this.device.vkDevice(), view, null);
                }
                Vma.vmaDestroyImage(this.allocator, image, allocationPointer.get(0));
                throw exception;
            }
        }
    }

    public void defer(Destroyable destroyable) {
        Objects.requireNonNull(destroyable, "destroyable");
        if (this.closed) {
            throw new IllegalStateException("Cannot defer a resource after the Vulkan context has closed");
        }
        synchronized (this.deferred) {
            this.deferred.add(destroyable);
        }
        try {
            this.commandEncoder().queueForDestroy(() -> {
                boolean shouldDestroy;
                synchronized (VulkanContext.this.deferred) {
                    shouldDestroy = VulkanContext.this.deferred.remove(destroyable);
                }
                if (shouldDestroy) {
                    destroyable.destroy();
                }
            });
        } catch (RuntimeException exception) {
            // Keep ownership in deferred: the callback was not registered, so close() must
            // retire the resource after vkDeviceWaitIdle instead of leaking it or freeing it early.
            throw exception;
        }
    }

    /** Runs only after all commands submitted before this call have completed on the real queue timeline. */
    public void afterSubmission(Runnable callback) {
        Objects.requireNonNull(callback, "callback");
        if (this.closed) {
            throw new IllegalStateException("Cannot register a completion callback after the Vulkan context has closed");
        }
        this.commandEncoder().queueForDestroy(callback::run);
    }

    public void awaitIdle() {
        requireOpen();
        check(VK12.vkDeviceWaitIdle(this.device.vkDevice()), "wait for Vulkan device");
    }

    public void drainDeferredAfterIdle() {
        ArrayList<Destroyable> pending;
        synchronized (this.deferred) {
            pending = new ArrayList<>(this.deferred);
            this.deferred.clear();
        }
        RuntimeException failure = null;
        for (Destroyable destroyable : pending) {
            try {
                destroyable.destroy();
            } catch (RuntimeException exception) {
                if (failure == null) {
                    failure = exception;
                } else {
                    failure.addSuppressed(exception);
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    public static long alignUp(long value, long alignment) {
        if (alignment <= 0L || (alignment & alignment - 1L) != 0L) {
            throw new IllegalArgumentException("Alignment must be a positive power of two");
        }
        if (value < 0L || value > Long.MAX_VALUE - (alignment - 1L)) {
            throw new IllegalArgumentException("Value cannot be aligned without overflow");
        }
        return value + alignment - 1L & -alignment;
    }

    public static void check(int result, String operation) {
        if (result != VK12.VK_SUCCESS) {
            throw new IllegalStateException(operation + " failed with Vulkan result " + result);
        }
    }

    @Override
    public void close() {
        if (this.closed) {
            return;
        }
        // Do not publish a terminal state until GPU ownership can be released safely.
        this.awaitIdle();
        RuntimeException failure = null;
        try {
            this.drainDeferredAfterIdle();
        } catch (RuntimeException exception) {
            failure = exception;
        }
        try {
            if (this.hdrPresentPass != null) {
                this.hdrPresentPass.destroy();
                this.hdrPresentPass = null;
            }
            if (this.uiAlphaCapturePass != null) {
                this.uiAlphaCapturePass.destroy();
                this.uiAlphaCapturePass = null;
            }
            this.sharedPrograms.close();
        } catch (RuntimeException exception) {
            if (failure == null) {
                failure = exception;
            } else {
                failure.addSuppressed(exception);
            }
        }
        try {
            this.pipelineCache.close();
        } catch (RuntimeException exception) {
            if (failure == null) {
                failure = exception;
            } else {
                failure.addSuppressed(exception);
            }
        }
        Vma.vmaDestroyAllocator(this.allocator);
        this.closed = true;
        if (failure != null) {
            throw failure;
        }
    }

    private void requireOpen() {
        if (this.closed) {
            throw new IllegalStateException("Vulkan context is closed");
        }
    }
}
