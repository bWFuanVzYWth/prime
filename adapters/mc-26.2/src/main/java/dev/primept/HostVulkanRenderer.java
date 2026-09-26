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
import java.util.ArrayList;
import java.util.List;
import org.lwjgl.vulkan.VK10;

/** Borrows Minecraft's device and command stream. Rust owns only its renderer resources. */
public final class HostVulkanRenderer implements AutoCloseable {
    // 26.2 defines texture usage bits 1, 2, 4, 8 and 16; this private extension is mapped by our texture mixin.
    public static final int USAGE_STORAGE = 32;
    private static final List<NativeBridge> QUARANTINED = new ArrayList<>();
    private final NativeBridge bridge;
    private final VulkanCommandEncoder encoder;
    private boolean closed;
    private long readinessGeneration;
    private boolean completedTerrainFrame;

    public HostVulkanRenderer() throws IOException {
        var backend = ((GpuDeviceAccessor) (Object) RenderSystem.getDevice()).primept$backend();
        if (!(backend instanceof VulkanDevice device)) {
            throw new IllegalStateException("Prime PT requires Minecraft's Vulkan backend; launch with --graphicsBackend VULKAN. CPU readback presentation is not supported.");
        }
        VulkanBootstrap.requireEnabled(device);
        encoder = device.createCommandEncoder();
        var access = (VulkanCommandEncoderAccessor) (Object) encoder;
        bridge = new NativeBridge(NativeBridge.resolveLibrary());
        try {
            bridge.attachVulkan(device.instance().vkInstance().address(), device.vkDevice().getPhysicalDevice().address(),
                    device.vkDevice().address(), device.graphicsQueue().vkQueue().address(),
                    access.primept$submitSemaphore(), device.graphicsQueue().queueFamilyIndex());
        } catch (RuntimeException | Error failure) {
            try { bridge.close(); } catch (RuntimeException cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }

    public void submit(byte[] packet) { bridge.submit(packet); }
    public void submitDynamic(long epoch) { dev.primept.capture.DynamicCapture.submit(epoch, bridge); }
    public ByteBuffer frameBuffer() { return bridge.frameBuffer(); }
    public long lastGpuTimeNanos() { return bridge.lastGpuTimeNanos(); }
    public boolean hasCompletedTerrainFrame() { return !closed && completedTerrainFrame; }

    public void resetReadiness() {
        ++readinessGeneration;
        completedTerrainFrame = false;
    }

    public void enableWorldReplacementAfterCompletion() {
        long generation = readinessGeneration;
        // The host rotates this queue only after the corresponding submission's timeline wait succeeds.
        encoder.queueForDestroy(() -> {
            if (!closed && generation == readinessGeneration) {
                completedTerrainFrame = true;
                PrimeClient.LOGGER.info("Prime PT terrain frame completed on GPU; vanilla world raster bypass is ready");
            }
        });
    }

    public void record(RenderTarget destination) {
        if (closed) throw new IllegalStateException("Host renderer is closed");
        if (!(destination.getColorTexture() instanceof VulkanGpuTexture texture)
                || !(destination.getColorTextureView() instanceof VulkanGpuTextureView view)
                || texture.getFormat() != GpuFormat.RGBA8_UNORM
                || (texture.usage() & USAGE_STORAGE) == 0) {
            throw new IllegalStateException("Prime PT requires an RGBA8 Vulkan main target with storage-image usage");
        }
        var command = encoder.allocateAndBeginTransientCommandBuffer();
        long submitValue = ((VulkanCommandEncoderAccessor) (Object) encoder).primept$currentSubmitIndex();
        bridge.record(command.address(), texture.vkImage(), view.vkImageView(), submitValue);
        int status = VK10.vkEndCommandBuffer(command);
        if (status != VK10.VK_SUCCESS) throw new IllegalStateException("Cannot finish native render command buffer: " + status);
        // execute preserves the vanilla terrain -> PT -> hand/HUD ordering; the host submits once normally.
        encoder.execute(command);
    }

    @Override public void close() {
        if (closed) return;
        closed = true;
        try {
            // Flush references recorded into the host stream before native waits on its borrowed timeline.
            encoder.submit();
        } catch (RuntimeException | Error failure) {
            QUARANTINED.add(bridge);
            throw new IllegalStateException("Host submission failed; retaining native GPU resources until process exit", failure);
        }
        try { bridge.close(); }
        catch (RuntimeException | Error failure) {
            QUARANTINED.add(bridge);
            throw failure;
        }
    }
}
