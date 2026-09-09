// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

package dev.prime.render.runtime;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.prime.infrastructure.PrimeInfo;
import dev.prime.render.vulkan.VulkanBootstrap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.network.chat.Component;

/** Client boundary for runtime logs and availability toasts. */
public final class RuntimeDiagnostics {
    private static final int TOAST_TEXT_WIDTH = 200;
    private static final int MAX_MESSAGE_LINES = 4;
    private static final SystemToast.SystemToastId UNAVAILABLE_TOAST =
            new SystemToast.SystemToastId(Long.MAX_VALUE);

    private boolean notificationShown;
    private boolean unavailabilityLogged;

    public void resetAvailabilityNotifications() {
        this.notificationShown = false;
        this.unavailabilityLogged = false;
    }

    public String finalizeUnavailableReason(String failureReason, RuntimeState state) {
        if (this.unavailabilityLogged || state != RuntimeState.UNAVAILABLE) {
            return failureReason;
        }
        String resolved = failureReason;
        if (VulkanBootstrap.snapshot().device() == null) {
            String backend = RenderSystem.getDevice().getDeviceInfo().backendName();
            resolved = "Minecraft is using " + backend
                    + "; select the Vulkan graphics backend";
        }
        this.unavailabilityLogged = true;
        PrimeInfo.LOGGER.warn("Prime will use vanilla rendering: {}", resolved);
        return resolved;
    }

    public void showFailureOnce(
            Minecraft minecraft, RuntimeState state, String failureReason) {
        if (this.notificationShown
                || state != RuntimeState.UNAVAILABLE && state != RuntimeState.FAILED
                || minecraft.gui == null) {
            return;
        }
        this.notificationShown = true;
        SystemToast.addOrUpdate(
                minecraft.gui.toastManager(),
                UNAVAILABLE_TOAST,
                fitText(minecraft.font,
                        RuntimeFailureSummary.title(PrimeInfo.version(), state), 1),
                fitText(minecraft.font, failureReason, MAX_MESSAGE_LINES));
    }

    private static Component fitText(Font font, String value, int maximumLines) {
        String text = RuntimeFailureSummary.clean(value);
        Component full = Component.literal(text);
        if (font.split(full, TOAST_TEXT_WIDTH).size() <= maximumLines) {
            return full;
        }

        int low = 0;
        int high = text.codePointCount(0, text.length());
        while (low < high) {
            int candidateLength = low + (high - low + 1) / 2;
            String candidate = abbreviatedPrefix(text, candidateLength);
            if (font.split(Component.literal(candidate), TOAST_TEXT_WIDTH).size()
                    <= maximumLines) {
                low = candidateLength;
            } else {
                high = candidateLength - 1;
            }
        }
        return Component.literal(abbreviatedPrefix(text, low));
    }

    private static String abbreviatedPrefix(String text, int codePoints) {
        int end = text.offsetByCodePoints(0, codePoints);
        return text.substring(0, end).stripTrailing() + '…';
    }
}
