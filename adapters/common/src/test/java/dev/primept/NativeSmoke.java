package dev.primept;

import dev.primept.capture.Packets;
import dev.primept.capture.DynamicFrame;
import dev.primept.capture.InstanceCapture;
import dev.primept.settings.RenderSettings;
import static dev.primept.abi.PrimeAbi.*;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import java.util.Arrays;
import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;
import javax.imageio.ImageIO;

/** Actual typed C ABI -> Rust -> Vulkan/Slang, without Minecraft or a visible window. */
public final class NativeSmoke {
    private static final float[] IDENTITY = {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0};
    private static final float[] UV = {1, 1, 0, 0};
    public static void main(String[] args) throws Exception {
        int width = 1920, height = 1080;
        Path library = args[0].equals("bundled") ? NativeBridge.resolveLibrary() : Path.of(args[0]);
        try (var bridge = new NativeBridge(library); var dynamic = new DynamicFrame(64);
             var instances = new InstanceCapture(1); var arena = Arena.ofConfined()) {
            bridge.configure(RenderSettings.defaults(), false, RenderSettings.View.OUTPUT);
            bridge.reset(1);
            bridge.submitTexture(1, 1, 1, 1, new byte[] {-1, -1, -1, -1});
            bridge.submitTexture(1, 7, 1, 1, new byte[] {0, 50, -1, -1});
            bridge.submitTexture(1, 8, 1, 1, new byte[] {-1, 0, 0, 0});
            bridge.submitTexture(1, 9, 1, 1, new byte[] {0, -1, 0, -1});
            ByteBuffer floor = ByteBuffer.allocate(112).order(ByteOrder.LITTLE_ENDIAN);
            for (float[] p : new float[][] {{-3, 0, 3}, {3, 0, 3}, {3, 0, -3}, {-3, 0, -3}}) {
                floor.putFloat(p[0]).putFloat(p[1]).putFloat(p[2]);
                floor.put((byte)190).put((byte)90).put((byte)55).put((byte)255);
                floor.putFloat(.5f).putFloat(.5f).putInt(0);
            }
            floor.flip();
            var floorPrototype = instances.prototype(4, 4, 28, 0, 12, 16, floor);
            var floorInstance = instances.instance();
            instances.beginFrame();
            floor(instances, floorInstance, floorPrototype);
            instances.endFrame();
            bridge.submitInstances(instances.sealDelta());
            instances.acknowledge();
            byte[] frame = Packets.frame(
                    1, 0, 2, 4, new float[] {0, -.4472136f, -.8944272f}, new float[] {1, 0, 0},
                    new float[] {0, .8944272f, -.4472136f}, 1.05f, width, height, 0);
            ByteBuffer rgba = ByteBuffer.allocateDirect(width * height * 4);
            byte[] baseline = pixels(bridge, frame, rgba);
            save(baseline, width, height, Path.of(args[1]));
            var nightFrame = arena.allocate(PrimeFrame.LAYOUT);
            nightFrame.copyFrom(MemorySegment.ofArray(frame));
            PrimeFrame.solar_hour_angle(nightFrame, (float)Math.PI);
            byte[] night = nightFrame.toArray(JAVA_BYTE);
            requireDifferent(baseline, pixels(bridge, night, rgba),
                             "Solar hour angle did not reach lighting");
            bridge.configure(RenderSettings.defaults()
                                     .with(RenderSettings.Control.LATITUDE, -47)
                                     .with(RenderSettings.Control.SOLAR_LONGITUDE, 90),
                             false, RenderSettings.View.OUTPUT);
            requireDifferent(baseline, pixels(bridge, frame, rgba),
                             "Observer/season did not reach lighting");
            bridge.configure(RenderSettings.defaults(), false, RenderSettings.View.OUTPUT);
            requireSame(baseline, pixels(bridge, frame, rgba), "Restored astronomy changed image");
            bridge.configure(RenderSettings.defaults(), true, RenderSettings.View.OUTPUT);
            byte[] moved = Packets.frame(999, 80000, 90000, 70000, new float[] {1, 0, 0},
                                         new float[] {0, 0, 1}, new float[] {0, 1, 0}, .5f, width,
                                         height, 0);
            bridge.configure(RenderSettings.defaults()
                                     .with(RenderSettings.Control.BOUNCES, 1)
                                     .with(RenderSettings.Control.SUN_EV, 4)
                                     .with(RenderSettings.Control.LATITUDE, -47),
                             true, RenderSettings.View.OUTPUT);
            requireSame(baseline, pixels(bridge, moved, rgba),
                        "Frozen pose/epoch changed displayed snapshot");
            reject(() -> bridge.reset(2));
            bridge.configure(RenderSettings.defaults(), false, RenderSettings.View.OUTPUT);
            requireSame(baseline, pixels(bridge, frame, rgba), "Realtime did not resume snapshot");
            int center = ((height / 2) * width + width / 2) * 4;
            dynamic.begin(1, 1, 0, 0, 0);
            wall(dynamic, 7, 0);
            bridge.submitDynamic(dynamic.seal());
            byte[] blue = pixels(bridge, frame, rgba);
            if (Byte.toUnsignedInt(blue[center + 2]) <= Byte.toUnsignedInt(blue[center]))
                throw new AssertionError("Typed blue dynamic wall is not visible");
            requireDifferent(baseline, blue, "Dynamic input had no effect");
            dynamic.begin(1, 2, 0, 0, 0);
            bridge.submitDynamic(dynamic.seal());
            requireSame(baseline, pixels(bridge, frame, rgba),
                        "Empty dynamic snapshot did not clear raw geometry");
            dynamic.begin(1, 3, 0, 0, 0);
            wall(dynamic, 8, 2);
            bridge.submitDynamic(dynamic.seal());
            requireSame(baseline, pixels(bridge, frame, rgba),
                        "Alpha-zero geometry changed visibility/shadows");
            dynamic.begin(1, 4, 0, 0, 0);
            wall(dynamic, 9, 0);
            wall(dynamic, 999, 0);
            var invalid = dynamic.seal();
            reject(() -> bridge.submitDynamic(invalid));
            requireSame(baseline, pixels(bridge, frame, rgba),
                        "Late invalid span partially published");
            dynamic.begin(1, 4, 0, 0, 0);
            wall(dynamic, 9, 0);
            bridge.submitDynamic(dynamic.seal());
            byte[] green = pixels(bridge, frame, rgba);
            requireDifferent(baseline, green, "Valid replacement did not publish after rejection");
            bridge.retireTextures(1, new int[] {9});
            requireSame(green, pixels(bridge, frame, rgba),
                        "Owner retirement invalidated a live texture reference");
            dynamic.begin(1, 5, 0, 0, 0);
            bridge.submitDynamic(dynamic.seal());
            requireSame(baseline, pixels(bridge, frame, rgba),
                        "Removing texture consumer changed baseline");
            dynamic.begin(1, 6, 0, 0, 0);
            wall(dynamic, 9, 0);
            var retired = dynamic.seal();
            reject(() -> bridge.submitDynamic(retired));
            requireSame(baseline, pixels(bridge, frame, rgba), "Retired texture was accepted");
            ByteBuffer local = ByteBuffer.allocate(96).order(ByteOrder.LITTLE_ENDIAN);
            for (float[] p : new float[][] {{-1, 0}, {1, 0}, {1, 2}, {-1, 2}})
                local.putFloat(p[0]).putFloat(p[1]).putFloat(0).putInt(-1).putFloat(.5f).putFloat(
                        .5f);
            local.flip();
            var prototype = instances.prototype(4, 4, 24, 0, 12, 16, local);
            var wallInstance = instances.instance();
            float[] transform = {2, 0, 0, 0, 0, 2, 0, 0, 0, 0, 1, 0};
            for (int iteration = 0; iteration < 2; iteration++) {
                instances.beginFrame();
                floor(instances, floorInstance, floorPrototype);
                instances.observe(wallInstance, prototype, 0, 0, 2, transform, 7, 0, -1, UV);
                instances.endFrame();
                var delta = instances.sealDelta();
                if (iteration == 0) {
                    bridge.submitInstances(delta);
                    instances.acknowledge();
                    byte[] image = pixels(bridge, frame, rgba);
                    if (Byte.toUnsignedInt(image[center + 2]) <= Byte.toUnsignedInt(image[center]))
                        throw new AssertionError(
                                "Instance affine transform/texture is not visible");
                } else if (delta != null)
                    throw new AssertionError("Stable instances crossed FFM again");
            }
            instances.release(prototype);
            instances.beginFrame();
            floor(instances, floorInstance, floorPrototype);
            instances.endFrame();
            bridge.submitInstances(instances.sealDelta());
            instances.acknowledge();
            requireSame(baseline, pixels(bridge, frame, rgba),
                        "Atomic prototype/instance removal changed baseline");
            var wrongThread = new AtomicReference<Throwable>();
            Thread thread = Thread.ofPlatform().start(() -> {
                try {
                    bridge.reset(2);
                } catch (Throwable e) {
                    wrongThread.set(e);
                }
            });
            thread.join();
            if (!(wrongThread.get() instanceof IllegalStateException))
                throw new AssertionError("Owner thread was not enforced");
            var malformed = arena.allocate(PrimeDynamicBatch.LAYOUT);
            NativeBridge.header(PrimeDynamicBatch.header(malformed), PrimeDynamicBatch.SIZE - 8);
            reject(() -> bridge.submitDynamic(malformed));
            requireSame(baseline, pixels(bridge, frame, rgba), "Invalid C header mutated scene");
            System.out.println(
                    "FFM typed C ABI Vulkan smoke passed: frame/settings, astronomy/frozen snapshot, raw and instance spans, late-failure atomicity, texture retirement, unchanged-frame zero-submit, owner thread and invalid header; " +
                    args[1]);
        }
    }
    private static void floor(InstanceCapture capture, InstanceCapture.Instance instance,
                              InstanceCapture.Prototype prototype) {
        capture.observe(instance, prototype, 0, 0, 0, IDENTITY, 1, 0, -1, UV);
    }
    private static byte[] pixels(NativeBridge bridge, byte[] frame, ByteBuffer output) {
        bridge.renderDiagnostic(frame, output);
        byte[] result = new byte[output.capacity()];
        output.get(0, result);
        return result;
    }
    private static void reject(Runnable action) {
        try {
            action.run();
        } catch (IllegalStateException expected) {
            return;
        }
        throw new AssertionError("Invalid transaction was accepted");
    }
    private static void requireSame(byte[] a, byte[] b, String message) {
        if (!Arrays.equals(a, b))
            throw new AssertionError(message);
    }
    private static void requireDifferent(byte[] a, byte[] b, String message) {
        if (Arrays.equals(a, b))
            throw new AssertionError(message);
    }
    private static void save(byte[] pixels, int width, int height, Path path) throws Exception {
        var image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        int first = 0;
        boolean varied = false;
        for (int y = 0; y < height; y++)
            for (int x = 0; x < width; x++) {
                int at = (y * width + x) * 4, r = Byte.toUnsignedInt(pixels[at]),
                    g = Byte.toUnsignedInt(pixels[at + 1]), b = Byte.toUnsignedInt(pixels[at + 2]),
                    a = Byte.toUnsignedInt(pixels[at + 3]);
                if (a != 255)
                    throw new AssertionError("Output alpha is not opaque");
                int color = a << 24 | r << 16 | g << 8 | b;
                if (x == 0 && y == 0)
                    first = color;
                else
                    varied |= color != first;
                image.setRGB(x, y, color);
            }
        if (!varied)
            throw new AssertionError("Renderer returned a constant image");
        ImageIO.write(image, "png", path.toFile());
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
