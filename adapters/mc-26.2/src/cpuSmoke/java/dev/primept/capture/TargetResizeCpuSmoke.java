package dev.primept.capture;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.pipeline.MainTarget;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.DeviceInfo;
import com.mojang.blaze3d.systems.DeviceLimits;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.GpuDeviceBackend;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vulkan.VulkanDevice;
import dev.primept.HostVulkanRenderer;
import dev.primept.PrimeClient;
import dev.primept.VulkanBootstrap;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.Set;
import java.util.function.Supplier;
import org.lwjgl.system.Pointer;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkPhysicalDevice;
import sun.misc.Unsafe;

/** Executes real transformed target allocation and resize; records textures without a GPU. */
final class TargetResizeCpuSmoke {
    static void run() throws Exception {
        Field device = field(RenderSystem.class, "DEVICE");
        Field thread = field(RenderSystem.class, "renderThread");
        Field status = field(VulkanBootstrap.class, "status");
        Object previousDevice = device.get(null), previousThread = thread.get(null);
        Object previousStatus = status.get(null);
        String previousEnabled = System.getProperty("primept.enabled");
        Object client = field(PrimeClient.class, "INSTANCE").get(null);
        Field requested = field(PrimeClient.class, "requested");
        Object previousRequested = requested.get(client);
        check(previousDevice == null, "No real GPU may be initialized by this test");
        Unsafe unsafe = (Unsafe)field(Unsafe.class, "theUnsafe").get(null);
        // Synthetic identities exercise the production negotiation guard. No Vulkan call may use them.
        var physical = (VkPhysicalDevice)unsafe.allocateInstance(VkPhysicalDevice.class);
        field(Pointer.Default.class, "address").setLong(physical, 101);
        var logical = (VkDevice)unsafe.allocateInstance(VkDevice.class);
        field(Pointer.Default.class, "address").setLong(logical, 202);
        field(VkDevice.class, "physicalDevice").set(logical, physical);
        var backend = (VulkanDevice)unsafe.allocateInstance(VulkanDevice.class);
        field(VulkanDevice.class, "vkDevice").set(backend, logical);
        var recorder = (RecordingDevice)unsafe.allocateInstance(RecordingDevice.class);
        field(GpuDevice.class, "backend").set(recorder, backend);
        var statusConstructor = status.getType().getDeclaredConstructor(
                long.class, long.class, boolean.class, String.class);
        statusConstructor.setAccessible(true);
        try {
            device.set(null, recorder);
            thread.set(null, Thread.currentThread());
            System.setProperty("primept.enabled", "true");
            requested.set(client, "path_trace");
            status.set(null, statusConstructor.newInstance(101L, 202L, true, ""));
            check(VulkanBootstrap.isEnabled(backend), "Synthetic negotiated device is recognized");
            lifecycle(true);
            // Vanilla must retain the shared main target capability for a later switch to PT.
            requested.set(client, "vanilla");
            check(!PrimeClient.captureResourcesEnabled(), "Vanilla selection disables PT capture");
            lifecycle(true);
            System.setProperty("primept.enabled", "false");
            lifecycle(false);
            System.setProperty("primept.enabled", "true");
            status.set(null, statusConstructor.newInstance(101L, 303L, true, "other device"));
            lifecycle(false);
            status.set(null, previousStatus);
            lifecycle(false);
            status.set(null, statusConstructor.newInstance(101L, 202L, true, ""));
            var otherBackend = Proxy.newProxyInstance(
                    TargetResizeCpuSmoke.class.getClassLoader(),
                    new Class<?>[] {GpuDeviceBackend.class}, (proxy, method, args) -> {
                        throw new AssertionError("Unexpected backend call: " + method);
                    });
            field(GpuDevice.class, "backend").set(recorder, otherBackend);
            lifecycle(false);
        } finally {
            status.set(null, previousStatus);
            device.set(null, previousDevice);
            thread.set(null, previousThread);
            restoreProperty("primept.enabled", previousEnabled);
            requested.set(client, previousRequested);
        }
        System.out.println(
                "PRIME_PT_TARGET_RESIZE_CPU_OK: actual MainTarget constructor/resize, color-only storage, depth/offscreen unchanged, vanilla/disabled/unnegotiated/other backend; no window or GPU");
    }

    private static void lifecycle(boolean storage) {
        var main = new MainTarget(1920, 1080);
        try {
            attachments(main, 1920, 1080, storage);
            for (int[] size : new int[][] {{1137, 641},
                                           {641, 1137},
                                           {1, 1},
                                           {3840, 2160},
                                           {1920, 1080},
                                           {1920, 1080}}) {
                resize(main, size[0], size[1], storage);
            }
        } finally {
            main.destroyBuffers();
        }
        // Same RGBA format and allocation method, but this is not the presentation target.
        var offscreen = new TextureTarget("offscreen", 64, 32, true, GpuFormat.RGBA8_UNORM);
        try {
            attachments(offscreen, 64, 32, false);
            resize(offscreen, 101, 53, false);
        } finally {
            offscreen.destroyBuffers();
        }
    }

    private static void resize(RenderTarget target, int width, int height, boolean storage) {
        var color = (RecordingTexture)target.getColorTexture();
        var depth = (RecordingTexture)target.getDepthTexture();
        var colorView = (RecordingView)target.getColorTextureView();
        var depthView = (RecordingView)target.getDepthTextureView();
        target.resize(width, height);
        check(color.closes == 1 && depth.closes == 1 && colorView.closes == 1 &&
                      depthView.closes == 1,
              "Resize closes each old host attachment once");
        check(target.getColorTexture() != color && target.getColorTextureView() != colorView,
              "Resize allocates a new image and view");
        attachments(target, width, height, storage);
    }

    private static void attachments(RenderTarget target, int width, int height, boolean storage) {
        var color = target.getColorTexture();
        var depth = target.getDepthTexture();
        int baseUsage = GpuTexture.USAGE_COPY_SRC | GpuTexture.USAGE_COPY_DST |
                        GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_RENDER_ATTACHMENT;
        check(color.usage() == (baseUsage | (storage ? HostVulkanRenderer.USAGE_STORAGE : 0)),
              "Main color storage=" + storage + " at " + width + "x" + height +
                      ": actual usage=" + color.usage());
        check(depth.usage() == baseUsage, "Depth must not acquire storage usage");
        check(color.getFormat() == GpuFormat.RGBA8_UNORM &&
                      depth.getFormat() == GpuFormat.D32_FLOAT,
              "Host formats preserved");
        check(target.width == width && target.height == height && color.getWidth(0) == width &&
                      color.getHeight(0) == height && depth.getWidth(0) == width &&
                      depth.getHeight(0) == height,
              "Actual target and attachment sizes agree");
        check(target.getColorTextureView().texture() == color &&
                      target.getDepthTextureView().texture() == depth,
              "Views refer to the current attachments");
    }

    private static final class RecordingDevice extends GpuDevice {
        // Never invoked: the front end is CPU-allocated to avoid profiler/command encoder setup.
        private RecordingDevice() {
            super(null, () -> {});
        }

        @Override
        public DeviceInfo getDeviceInfo() {
            return new DeviceInfo("CPU fixture", "", "", true, "", 1,
                                  new DeviceLimits(1, 1, 16384, 1L << 30, 1, 8), null, Set.of(),
                                  null, null);
        }

        @Override
        public GpuTexture createTexture(Supplier<String> label, int usage, GpuFormat format,
                                        int width, int height, int depth, int mips) {
            return new RecordingTexture(usage, label.get(), format, width, height, depth, mips);
        }

        @Override
        public GpuTextureView createTextureView(GpuTexture texture) {
            return new RecordingView(texture);
        }
    }

    private static final class RecordingTexture extends GpuTexture {
        int closes;
        RecordingTexture(int usage, String label, GpuFormat format, int width, int height,
                         int depth, int mips) {
            super(usage, label, format, width, height, depth, mips);
        }
        public boolean isClosed() {
            return closes != 0;
        }
        public void close() {
            ++closes;
        }
    }

    private static final class RecordingView extends GpuTextureView {
        int closes;
        RecordingView(GpuTexture texture) {
            super(texture, 0, 1);
        }
        public boolean isClosed() {
            return closes != 0;
        }
        public void close() {
            ++closes;
        }
    }

    private static Field field(Class<?> type, String name) throws Exception {
        Field result = type.getDeclaredField(name);
        result.setAccessible(true);
        return result;
    }

    private static void restoreProperty(String key, String value) {
        if (value == null)
            System.clearProperty(key);
        else
            System.setProperty(key, value);
    }

    private static void check(boolean pass, String message) {
        if (!pass)
            throw new AssertionError(message);
    }
}
