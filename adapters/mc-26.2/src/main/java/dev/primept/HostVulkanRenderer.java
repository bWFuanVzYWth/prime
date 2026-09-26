package dev.primept;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vulkan.VulkanCommandEncoder;
import com.mojang.blaze3d.vulkan.VulkanDevice;
import com.mojang.blaze3d.vulkan.VulkanGpuTexture;
import com.mojang.blaze3d.vulkan.VulkanGpuTextureView;
import dev.primept.mixin.GpuDeviceAccessor;
import dev.primept.mixin.VulkanCommandEncoderAccessor;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Objects;
import org.lwjgl.vulkan.VK10;

/** Borrows Minecraft's device and command stream. Rust owns only its renderer resources. */
public final class HostVulkanRenderer implements AutoCloseable {
    // The host defines texture usage bits 1, 2, 4, 8 and 16; this private extension is mapped by our texture mixin.
    public static final int USAGE_STORAGE = 32;
    private static final long RETIRE_TIMEOUT_NANOS = 5_000_000_000L;
    // A failed retirement is terminal. Keep one owner alive; never accumulate sessions after failures.
    private static NativeBridge retainedBridge;
    private static Throwable retirementFailure;
    private final NativeBridge bridge;
    private final VulkanCommandEncoder encoder;
    private boolean closed;
    private boolean retired;
    private long readinessGeneration;
    private boolean completedWorldFrame;

    public HostVulkanRenderer() throws IOException {
        var device = availableDevice();
        encoder = device.createCommandEncoder();
        var access = (VulkanCommandEncoderAccessor)(Object)encoder;
        bridge = new NativeBridge(NativeBridge.resolveLibrary());
        try {
            bridge.attachVulkan(
                    device.instance().vkInstance().address(),
                    device.vkDevice().getPhysicalDevice().address(), device.vkDevice().address(),
                    device.graphicsQueue().vkQueue().address(), access.primept$submitSemaphore(),
                    device.graphicsQueue().queueFamilyIndex());
        } catch (RuntimeException | Error failure) {
            try {
                bridge.close();
            } catch (RuntimeException | Error cleanup) {
                blockRetirement(bridge, cleanup);
                if (failure != cleanup)
                    failure.addSuppressed(cleanup);
            }
            throw failure;
        }
    }

    public static boolean isVulkanHost() {
        return ((GpuDeviceAccessor)(Object)RenderSystem.getDevice()).primept$backend() instanceof
                VulkanDevice;
    }

    /** Every main color allocation needs this, including resize while vanilla is selected. */
    public static int mainColorUsage(int usage) {
        if (!Boolean.getBoolean("primept.enabled"))
            return usage;
        var backend = ((GpuDeviceAccessor)(Object)RenderSystem.getDevice()).primept$backend();
        return backend instanceof VulkanDevice device && VulkanBootstrap.isEnabled(device)
                ? usage | USAGE_STORAGE
                : usage;
    }

    /** Capability check creates no backend resources and may run before retiring vanilla. */
    public static void requireAvailable() {
        availableDevice();
    }

    private static VulkanDevice availableDevice() {
        requireRetirementResolved();
        var backend = ((GpuDeviceAccessor)(Object)RenderSystem.getDevice()).primept$backend();
        if (!(backend instanceof VulkanDevice device)) {
            throw new IllegalStateException(
                    "Prime PT requires Minecraft's Vulkan backend; launch with --graphicsBackend VULKAN. CPU readback presentation is not supported.");
        }
        VulkanBootstrap.requireEnabled(device);
        return device;
    }

    /** A completion failure also forbids allocating the next vanilla backend's exclusive resources. */
    public static void requireRetirementResolved() {
        RenderSystem.assertOnRenderThread();
        if (retirementFailure != null) {
            throw new IllegalStateException(
                    "Renderer retirement is unresolved; no new renderer resources may be created",
                    retirementFailure);
        }
    }

    /** Switch-only barrier. Does not destroy the shared host device or wait for device idle. */
    public static void retireHostResources(Runnable release) {
        requireRetirementResolved();
        Objects.requireNonNull(release);
        var backend = ((GpuDeviceAccessor)(Object)RenderSystem.getDevice()).primept$backend();
        if (!(backend instanceof VulkanDevice device)) {
            throw new IllegalStateException(
                    "Host resource retirement requires Minecraft's Vulkan backend");
        }
        var encoder = device.createCommandEncoder();
        try {
            submitAndAwait(encoder);
            release.run();
            // These adapters' texture/view/buffer close methods enqueue their destruction synchronously.
            // FIFO marker execution proves every preceding release callback ran, independently of ring size.
            boolean[] released = {false};
            encoder.queueForDestroy(() -> released[0] = true);
            long deadline = System.nanoTime() + RETIRE_TIMEOUT_NANOS;
            while (!released[0]) {
                if (System.nanoTime() - deadline >= 0) {
                    throw new IllegalStateException(
                            "Host resource destruction did not reach its completion marker");
                }
                submitAndAwait(encoder);
            }
        } catch (RuntimeException | Error failure) {
            blockRetirement(null, failure);
            throw failure;
        }
    }

    private static void submitAndAwait(VulkanCommandEncoder encoder) {
        // createFence captures the CURRENT submit index. submit() itself only waits for an older submit.
        try (var fence = encoder.createFence()) {
            encoder.submit();
            if (!fence.awaitCompletion(RETIRE_TIMEOUT_NANOS)) {
                throw new IllegalStateException(
                        "Host GPU submission did not complete before renderer retirement");
            }
        }
    }

    private static void blockRetirement(NativeBridge bridge, Throwable failure) {
        if (retirementFailure == null)
            retirementFailure = failure;
        if (retainedBridge == null)
            retainedBridge = bridge;
    }

    public void submit(byte[] packet) {
        bridge.submit(packet);
    }
    public void submitDynamic(long epoch) {
        dev.primept.capture.DynamicCapture.submit(epoch, bridge);
    }
    public ByteBuffer frameBuffer() {
        return bridge.frameBuffer();
    }
    public long lastGpuTimeNanos() {
        return bridge.lastGpuTimeNanos();
    }
    public boolean hasCompletedWorldFrame() {
        return !closed && completedWorldFrame;
    }

    public void resetReadiness() {
        ++readinessGeneration;
        completedWorldFrame = false;
    }

    public void enableWorldReplacementAfterCompletion() {
        long generation = readinessGeneration;
        // The host rotates this queue only after the corresponding submission's timeline wait succeeds.
        encoder.queueForDestroy(() -> {
            if (!closed && generation == readinessGeneration) {
                completedWorldFrame = true;
                PrimeClient.LOGGER.info("Prime PT world frame completed on GPU");
            }
        });
    }

    public void record(RenderTarget destination) {
        if (closed)
            throw new IllegalStateException("Host renderer is closed");
        var color = destination.getColorTexture();
        var colorView = destination.getColorTextureView();
        if (!(color instanceof VulkanGpuTexture texture) ||
            !(colorView instanceof VulkanGpuTextureView view) ||
            texture.getFormat() != GpuFormat.RGBA8_UNORM ||
            (texture.usage() & USAGE_STORAGE) == 0) {
            throw new IllegalStateException(
                    "Prime PT requires an RGBA8 Vulkan main target with storage-image usage; size=" +
                    destination.width + "x" + destination.height + ", color=" +
                    (color == null ? "missing"
                                   : color.getClass().getSimpleName() + "/" + color.getFormat() +
                                             "/usage=0x" + Integer.toHexString(color.usage())) +
                    ", view=" +
                    (colorView == null ? "missing" : colorView.getClass().getSimpleName()));
        }
        var command = encoder.allocateAndBeginTransientCommandBuffer();
        long submitValue =
                ((VulkanCommandEncoderAccessor)(Object)encoder).primept$currentSubmitIndex();
        bridge.record(command.address(), texture.vkImage(), view.vkImageView(), submitValue);
        int status = VK10.vkEndCommandBuffer(command);
        if (status != VK10.VK_SUCCESS)
            throw new IllegalStateException("Cannot finish native render command buffer: " +
                                            status);
        // execute preserves the vanilla terrain -> PT -> hand/HUD ordering; the host submits once normally.
        encoder.execute(command);
    }

    @Override
    public void close() {
        requireRetirementResolved();
        if (retired)
            return;
        if (closed)
            throw new IllegalStateException("Native renderer retirement has not completed");
        closed = true;
        try {
            submitAndAwait(encoder);
            bridge.close();
            retired = true;
        } catch (RuntimeException | Error failure) {
            blockRetirement(bridge, failure);
            throw failure;
        }
    }
}
