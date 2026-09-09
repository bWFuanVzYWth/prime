// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

package dev.prime.render.vulkan;

import com.mojang.blaze3d.vulkan.VulkanGpuTextureView;
import dev.prime.render.HdrOutput;
import java.util.Objects;
import org.lwjgl.vulkan.VkCommandBuffer;

/**
 * Render-boundary bridge from Prime's HDR frame to Minecraft's final surface submission.
 *
 * <p>{@link dev.prime.render.runtime.RendererLifecycle} exclusively owns the context lifetime.
 * Frame publication contains immutable image identities and is cleared before the next frame can
 * retire renderer resources. Volatile publication covers a backend that splits world recording
 * and surface submission across threads without adding another lock domain.
 */
public final class HdrPresentation {
    private static volatile VulkanContext context;
    private static volatile Frame frame;

    private HdrPresentation() {
    }

    public static void attach(VulkanContext value) {
        context = Objects.requireNonNull(value, "value");
        frame = null;
    }

    public static void detach(VulkanContext value) {
        if (context == value) {
            frame = null;
            context = null;
        }
    }

    public static void beginFrame() {
        frame = null;
    }

    public static boolean available() {
        return context != null;
    }

    public static void publish(
            VulkanContext owner,
            VulkanImage hdr,
            VulkanImage sdrBaseline) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(hdr, "hdr");
        Objects.requireNonNull(sdrBaseline, "sdrBaseline");
        if (hdr.width() != sdrBaseline.width() || hdr.height() != sdrBaseline.height()) {
            throw new IllegalArgumentException("HDR and SDR presentation extents differ");
        }
        HdrOutput.Calibration calibration = HdrOutput.activeCalibration();
        if (context == owner && calibration.active()) {
            frame = new Frame(owner, hdr, sdrBaseline, calibration.scRgbScale());
        }
    }

    public static VulkanImage record(
            VkCommandBuffer commandBuffer,
            VulkanGpuTextureView uiComposite,
            int width,
            int height) {
        VulkanContext activeContext = context;
        if (activeContext == null) {
            return null;
        }
        Frame current = frame;
        boolean compositePrimeHdr = current != null
                && current.context == activeContext
                && current.hdr.width() == width
                && current.hdr.height() == height
                && current.sdrBaseline.width() == width
                && current.sdrBaseline.height() == height;
        long uiView = uiComposite.vkImageView();
        long hdrView = compositePrimeHdr ? current.hdr.view() : uiView;
        long baselineView = compositePrimeHdr ? current.sdrBaseline.view() : uiView;
        float scRgbScale = compositePrimeHdr
                ? current.scRgbScale
                : HdrOutput.activeCalibration().scRgbScale();
        return activeContext.recordHdrPresentation(
                commandBuffer,
                hdrView,
                baselineView,
                uiView,
                width,
                height,
                compositePrimeHdr,
                scRgbScale);
    }

    private record Frame(
            VulkanContext context,
            VulkanImage hdr,
            VulkanImage sdrBaseline,
            float scRgbScale) {
    }
}
