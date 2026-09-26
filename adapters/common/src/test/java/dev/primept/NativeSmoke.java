package dev.primept;

import dev.primept.capture.Packets;
import dev.primept.capture.DynamicFrame;
import dev.primept.capture.InstanceCapture;
import java.util.Arrays;
import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;
import javax.imageio.ImageIO;

/** Executes the actual Java FFM -> Rust translation -> Vulkan/Slang path, with no Minecraft process. */
public final class NativeSmoke {
    public static void main(String[] args) throws Exception {
        int width = 1920, height = 1080;
        Path library = args[0].equals("bundled") ? NativeBridge.resolveLibrary() : Path.of(args[0]);
        try (var bridge = new NativeBridge(library)) {
            bridge.submit(Packets.reset(1));
            bridge.submit(Packets.texture(1, 1, 1, new byte[] { -1, -1, -1, -1 }));
            ByteBuffer vertices = ByteBuffer.allocate(112).order(ByteOrder.LITTLE_ENDIAN);
            for (float[] p : new float[][] { {-3, 0, 3}, {3, 0, 3}, {3, 0, -3}, {-3, 0, -3} }) {
                vertices.putFloat(p[0]).putFloat(p[1]).putFloat(p[2]);
                vertices.put((byte) 190).put((byte) 90).put((byte) 55).put((byte) 255);
                vertices.putFloat(0.5f).putFloat(0.5f).putInt(0);
            }
            vertices.flip();
            bridge.submit(Packets.mesh(1, 1, 1, 0, 0, 0, 4, 28, 0, 12, 16, 4, 0, 0, vertices));
            byte[] frame = Packets.frame(1, 0, 2, 4,
                    new float[] {0, -.4472136f, -.8944272f}, new float[] {1, 0, 0},
                    new float[] {0, .8944272f, -.4472136f}, 1.05f, width, height, 0);
            ByteBuffer rgba = ByteBuffer.allocateDirect(width * height * 4);
            bridge.renderDiagnostic(frame, rgba);
            var image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
            int firstColor = 0;
            boolean varied = false;
            for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
                int offset = (y * width + x) * 4;
                int r = Byte.toUnsignedInt(rgba.get(offset)), g = Byte.toUnsignedInt(rgba.get(offset + 1));
                int b = Byte.toUnsignedInt(rgba.get(offset + 2)), a = Byte.toUnsignedInt(rgba.get(offset + 3));
                if (a != 255) throw new AssertionError("Native output alpha must be opaque");
                int argb = a << 24 | r << 16 | g << 8 | b;
                if (x == 0 && y == 0) firstColor = argb;
                else varied |= argb != firstColor;
                image.setRGB(x, y, argb);
            }
            if (!varied) throw new AssertionError("Native renderer returned a constant image");
            ImageIO.write(image, "png", Path.of(args[1]).toFile());
            byte[] baseline = new byte[width * height * 4];
            rgba.get(0, baseline);
            bridge.submit(Packets.texture(1, 7, 1, 1, new byte[] { 0, 50, -1, -1 }));
            bridge.submit(Packets.texture(1, 8, 1, 1, new byte[] { -1, 0, 0, 0 }));
            try (var dynamic = new DynamicFrame(64)) {
                dynamic.begin(1, 1, 0, 0, 0);
                wall(dynamic, 7, 0);
                bridge.submit(dynamic.seal());
                bridge.renderDiagnostic(frame, rgba);
                int center = ((height / 2) * width + width / 2) * 4;
                if (Byte.toUnsignedInt(rgba.get(center + 2)) <= Byte.toUnsignedInt(rgba.get(center)))
                    throw new AssertionError("Batched dynamic blue wall is not visible at the center");
                byte[] dynamicPixels = new byte[baseline.length];
                rgba.get(0, dynamicPixels);
                if (Arrays.equals(baseline, dynamicPixels)) throw new AssertionError("Dynamic frame did not affect output");
                dynamic.begin(1, 2, 0, 0, 0);
                bridge.submit(dynamic.seal());
                bridge.renderDiagnostic(frame, rgba);
                rgba.get(0, dynamicPixels);
                if (!Arrays.equals(baseline, dynamicPixels))
                    throw new AssertionError("Empty dynamic frame did not restore the static scene");
                dynamic.begin(1, 3, 0, 0, 0);
                wall(dynamic, 8, 2);
                bridge.submit(dynamic.seal());
                bridge.renderDiagnostic(frame, rgba);
                rgba.get(0, dynamicPixels);
                if (!Arrays.equals(baseline, dynamicPixels))
                    throw new AssertionError("Alpha-zero dynamic surface changed visibility or shadowing");
            }
            try (var instances = new InstanceCapture(1)) {
                ByteBuffer local = ByteBuffer.allocate(96).order(ByteOrder.LITTLE_ENDIAN);
                for (float[] point : new float[][] { {-1, 0}, {1, 0}, {1, 2}, {-1, 2} })
                    local.putFloat(point[0]).putFloat(point[1]).putFloat(0).putInt(-1).putFloat(.5f).putFloat(.5f);
                local.flip();
                var prototype = instances.prototype(4, 4, 24, 0, 12, 16, local);
                var wall = instances.instance();
                float[] transform = {2, 0, 0, 0, 0, 2, 0, 0, 0, 0, 1, 0};
                float[] uv = {1, 1, 0, 0};
                instances.beginFrame();
                instances.observe(wall, prototype, 0, 0, 2, transform, 7, 0, -1, uv);
                instances.endFrame();
                bridge.submit(instances.sealDelta());
                instances.acknowledge();
                bridge.renderDiagnostic(frame, rgba);
                int center = ((height / 2) * width + width / 2) * 4;
                if (Byte.toUnsignedInt(rgba.get(center + 2)) <= Byte.toUnsignedInt(rgba.get(center)))
                    throw new AssertionError("Persistent instance texture or affine transform is not visible");
                instances.beginFrame();
                instances.observe(wall, prototype, 0, 0, 2, transform, 7, 0, -1, uv);
                instances.endFrame();
                if (instances.sealDelta() != null) throw new AssertionError("Stable instances must not cross FFM again");
                instances.release(prototype);
                instances.beginFrame(); instances.endFrame();
                bridge.submit(instances.sealDelta());
                instances.acknowledge();
                bridge.renderDiagnostic(frame, rgba);
                byte[] removed = new byte[baseline.length];
                rgba.get(0, removed);
                if (!Arrays.equals(baseline, removed))
                    throw new AssertionError("Atomic prototype/instance removal did not restore the scene");
            }
            var crossThreadError = new AtomicReference<Throwable>();
            Thread thread = Thread.ofPlatform().start(() -> {
                try { bridge.submit(Packets.reset(2)); }
                catch (Throwable error) { crossThreadError.set(error); }
            });
            thread.join();
            if (!(crossThreadError.get() instanceof IllegalStateException))
                throw new AssertionError("FFM renderer thread ownership was not enforced");
            boolean rejected = false;
            try { bridge.submit(new byte[8]); }
            catch (IllegalStateException expected) { rejected = true; }
            if (!rejected) throw new AssertionError("Malformed packet must fail at the native boundary");
            System.out.println("FFM Vulkan smoke passed: raw and persistent-instance packets -> Rust -> Slang -> RGBA; dynamic visibility, affine/texture instance, unchanged-frame zero-submit, atomic removal, alpha-zero visibility/shadows, owner-thread and malformed-input checks; " + args[1]);
        }
    }

    private static void wall(DynamicFrame dynamic, int texture, int flags) {
        dynamic.beginSpan(texture, flags, 4);
        dynamic.vertex(-2, 0, 2, -1, 0, 0);
        dynamic.vertex(2, 0, 2, -1, 1, 0);
        dynamic.vertex(2, 4, 2, -1, 1, 1);
        dynamic.vertex(-2, 4, 2, -1, 0, 1);
        dynamic.endSpan();
    }
}
