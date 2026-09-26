package dev.primept;

import dev.primept.capture.Packets;
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
            System.out.println("FFM Vulkan smoke passed: raw quad -> Rust triangles -> Slang -> RGBA, owner-thread check, malformed-packet rejection; " + args[1]);
        }
    }
}
