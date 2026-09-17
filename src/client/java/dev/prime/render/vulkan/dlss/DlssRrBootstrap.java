// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

package dev.prime.render.vulkan.dlss;

import com.mojang.renderpearl.backend.vulkan.VulkanPhysicalDevice;
import dev.prime.infrastructure.PrimeInfo;
import dev.prime.render.vulkan.natives.NativeLibraries;
import dev.prime.render.vulkan.VulkanContext;
import java.nio.IntBuffer;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkExtensionProperties;

/** Session-scoped, fail-safe NGX capability negotiation. */
public final class DlssRrBootstrap {
    // Vulkan instance/device hooks and the later render-runtime initialization share this
    // pre-device lifecycle. The existing class monitor serializes that boundary; one immutable
    // state prevents partially updated capability observations inside it.
    private static State state = State.initial();

    private DlssRrBootstrap() {}

    public static synchronized void enableRequiredInstanceExtensions(
            Collection<String> enabledExtensions) {
        if (state.instanceReady()) {
            enabledExtensions.addAll(state.instanceExtensions());
            return;
        }
        if (!NativeLibraries.isWindowsX64()) {
            disable("DLSS RR currently supports Windows x86-64 only", null);
            return;
        }
        try {
            List<String> required = DlssRrNative.instanceExtensions();
            Set<String> supported = supportedInstanceExtensions();
            List<String> missing = required.stream()
                    .filter(extension -> !supported.contains(extension))
                    .toList();
            if (!missing.isEmpty()) {
                disable("Missing NGX Vulkan instance extensions: " + String.join(", ", missing), null);
                return;
            }
            enabledExtensions.addAll(required);
            state = new State(
                    required,
                    "DLSS RR Vulkan device has not been probed",
                    true,
                    false,
                    state.initialization());
            PrimeInfo.LOGGER.info(
                    "Enabled {} NVIDIA NGX Vulkan instance extension(s)", required.size());
        } catch (RuntimeException | LinkageError exception) {
            disable("Unable to query NVIDIA NGX Vulkan instance requirements", exception);
        }
    }

    public static synchronized void enableRequiredDeviceExtensions(
            VulkanPhysicalDevice physicalDevice, Collection<String> enabledExtensions) {
        if (!state.instanceReady()) {
            return;
        }
        try {
            List<String> required = DlssRrNative.deviceExtensions(
                    physicalDevice.vkPhysicalDevice().getInstance().address(),
                    physicalDevice.vkPhysicalDevice().address());
            List<String> missing = required.stream()
                    // LWJGL already cached the physical device's advertised extension set.
                    // Re-enumerating hundreds of extension properties on MemoryStack can
                    // exhaust its fixed per-thread arena while device negotiation is active.
                    .filter(extension -> !physicalDevice.hasDeviceExtension(extension))
                    .toList();
            if (!missing.isEmpty()) {
                disable("Missing NGX Vulkan device extensions: " + String.join(", ", missing), null);
                return;
            }
            enabledExtensions.addAll(required);
            state = new State(
                    state.instanceExtensions(),
                    "NVIDIA NGX has not been initialized",
                    true,
                    true,
                    state.initialization());
            PrimeInfo.LOGGER.info(
                    "Enabled {} NVIDIA NGX Vulkan device extension(s)", required.size());
        } catch (RuntimeException | LinkageError exception) {
            disable("Unable to query NVIDIA NGX Vulkan device requirements", exception);
        }
    }

    public static synchronized Optional<DlssRrNative.Context> initialize(VulkanContext context) {
        if (!state.initialization().canInitialize(state.deviceReady())) {
            return Optional.empty();
        }
        try {
            DlssRrNative.Context ngx = DlssRrNative.initialize(context);
            state = new State(
                    state.instanceExtensions(),
                    "",
                    state.instanceReady(),
                    state.deviceReady(),
                    state.initialization().initialized());
            PrimeInfo.LOGGER.info("NVIDIA NGX DLSS Ray Reconstruction is available");
            return Optional.of(ngx);
        } catch (RuntimeException | LinkageError exception) {
            disable("NVIDIA NGX DLSS RR initialization failed", exception);
            return Optional.empty();
        }
    }

    /**
     * Releases the sole active NGX context and re-arms initialization for an in-game renderer
     * restart. A previously failed RR session remains disabled after its context is retired.
     */
    public static synchronized void release(DlssRrNative.Context context) {
        java.util.Objects.requireNonNull(context, "context");
        if (state.initialization() != Initialization.ACTIVE) {
            throw new IllegalStateException("No NVIDIA NGX context is active");
        }
        RuntimeException failure = null;
        try {
            context.close();
        } catch (RuntimeException exception) {
            failure = exception;
            disable("NVIDIA NGX shutdown failed", exception);
        }
        state = new State(
                state.instanceExtensions(),
                state.deviceReady()
                        ? "NVIDIA NGX has not been initialized"
                        : state.unavailableReason(),
                state.instanceReady(),
                state.deviceReady(),
                state.initialization().released());
        if (failure != null) {
            throw failure;
        }
    }

    public static synchronized boolean deviceReady() {
        return state.deviceReady();
    }

    public static synchronized String unavailableReason() {
        return state.unavailableReason();
    }

    public static synchronized void failSession(String reason, Throwable cause) {
        disable(reason, cause);
    }

    private static Set<String> supportedInstanceExtensions() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer count = stack.callocInt(1);
            int result = VK12.vkEnumerateInstanceExtensionProperties((String) null, count, null);
            if (result != VK12.VK_SUCCESS) {
                throw new IllegalStateException(
                        "vkEnumerateInstanceExtensionProperties failed with " + result);
            }
            VkExtensionProperties.Buffer properties = VkExtensionProperties.calloc(count.get(0), stack);
            result = VK12.vkEnumerateInstanceExtensionProperties((String) null, count, properties);
            if (result != VK12.VK_SUCCESS) {
                throw new IllegalStateException(
                        "vkEnumerateInstanceExtensionProperties failed with " + result);
            }
            HashSet<String> supported = new HashSet<>();
            for (int index = 0; index < count.get(0); index++) {
                supported.add(properties.get(index).extensionNameString());
            }
            return Set.copyOf(supported);
        }
    }

    private static void disable(String reason, Throwable cause) {
        state = new State(
                state.instanceExtensions(),
                reason,
                false,
                false,
                state.initialization());
        if (cause == null) {
            PrimeInfo.LOGGER.warn("DLSS RR unavailable for this session: {}", reason);
        } else {
            PrimeInfo.LOGGER.warn("DLSS RR unavailable for this session: {}", reason, cause);
        }
    }

    private record State(
            List<String> instanceExtensions,
            String unavailableReason,
            boolean instanceReady,
            boolean deviceReady,
            Initialization initialization) {
        private State {
            instanceExtensions = List.copyOf(instanceExtensions);
            java.util.Objects.requireNonNull(unavailableReason, "unavailableReason");
            java.util.Objects.requireNonNull(initialization, "initialization");
        }

        private static State initial() {
            return new State(
                    List.of(),
                    "DLSS RR has not been probed",
                    false,
                    false,
                    Initialization.IDLE);
        }
    }

    enum Initialization {
        IDLE,
        ACTIVE;

        boolean canInitialize(boolean deviceReady) {
            return deviceReady && this == IDLE;
        }

        Initialization initialized() {
            if (this != IDLE) {
                throw new IllegalStateException("NVIDIA NGX is already initialized");
            }
            return ACTIVE;
        }

        Initialization released() {
            if (this != ACTIVE) {
                throw new IllegalStateException("NVIDIA NGX is not initialized");
            }
            return IDLE;
        }
    }
}
