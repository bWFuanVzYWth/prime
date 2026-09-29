package dev.primept;

import dev.primept.capture.Packets;
import dev.primept.settings.RenderSettings;
import dev.primept.capture.DynamicFrame;
import dev.primept.capture.InstanceCapture;
import dev.primept.capture.RouteBuffer;
import java.util.Arrays;
import java.util.List;
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
            bridge.configure(RenderSettings.defaults(), false, RenderSettings.View.OUTPUT);
            bridge.submit(Packets.reset(1));
            bridge.submit(Packets.texture(1, 1, 1, new byte[] {-1, -1, -1, -1}));
            ByteBuffer vertices = ByteBuffer.allocate(112).order(ByteOrder.LITTLE_ENDIAN);
            for (float[] p : new float[][] {{-3, 0, 3}, {3, 0, 3}, {3, 0, -3}, {-3, 0, -3}}) {
                vertices.putFloat(p[0]).putFloat(p[1]).putFloat(p[2]);
                vertices.put((byte)190).put((byte)90).put((byte)55).put((byte)255);
                vertices.putFloat(0.5f).putFloat(0.5f).putInt(0);
            }
            vertices.flip();
            var route = new RouteBuffer().header(13, 1).i(1).i(0).l(77).i(1).i(0).i(0).i(6).i(-1);
            for (int i = 0; i < 4; ++i) {
                int offset = i * 28;
                route.f(vertices.getFloat(offset))
                        .f(vertices.getFloat(offset + 4))
                        .f(vertices.getFloat(offset + 8))
                        .rgba(0xffbe5a37)
                        .f(.5f)
                        .f(.5f);
            }
            bridge.submit(route.seal());
            bridge.submit(route.header(12, 1)
                                  .l(1)
                                  .l(1)
                                  .d(0)
                                  .d(0)
                                  .d(0)
                                  .i(1)
                                  .i(0)
                                  .l(77)
                                  .f(0)
                                  .f(0)
                                  .f(0)
                                  .i(64)
                                  .i(0)
                                  .seal());
            // Source definitions can retire before GPU recording; compiled geometry owns its data.
            bridge.submit(route.header(13, 1).i(0).i(1).l(77).seal());
            byte[] frame = Packets.frame(
                    1, 0, 2, 4, new float[] {0, -.4472136f, -.8944272f}, new float[] {1, 0, 0},
                    new float[] {0, .8944272f, -.4472136f}, 1.05f, width, height, 0);
            ByteBuffer rgba = ByteBuffer.allocateDirect(width * height * 4);
            byte[] waiting = pixels(bridge, frame, rgba);
            for (int slot = 1; slot < 63; ++slot)
                bridge.submit(Packets.sectionReplace(1, 1000 + slot, 1, (slot / 16) * 16,
                                                     (slot / 4 % 4) * 16, (slot % 4) * 16,
                                                     List.of()));
            if (!Arrays.equals(waiting, pixels(bridge, frame, rgba)))
                throw new AssertionError("Incomplete 63-section cell published terrain");
            bridge.submit(Packets.sectionReplace(1, 1063, 1, 48, 48, 48, List.of()));
            byte[] complete = pixels(bridge, frame, rgba);
            if (Arrays.equals(waiting, complete))
                throw new AssertionError(
                        "64th complete empty section did not publish cached terrain");
            bridge.submit(Packets.remove(1, 1063, 2));
            if (!Arrays.equals(waiting, pixels(bridge, frame, rgba)))
                throw new AssertionError(
                        "Withdrawing a section left a partial or stale cell visible");
            bridge.submit(Packets.sectionReplace(1, 1063, 3, 48, 48, 48, List.of()));
            if (!Arrays.equals(complete, pixels(bridge, frame, rgba)))
                throw new AssertionError(
                        "Recompleted cell did not restore its CPU-cached geometry");
            var image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
            int firstColor = 0;
            boolean varied = false;
            for (int y = 0; y < height; y++)
                for (int x = 0; x < width; x++) {
                    int offset = (y * width + x) * 4;
                    int r = Byte.toUnsignedInt(rgba.get(offset)),
                        g = Byte.toUnsignedInt(rgba.get(offset + 1));
                    int b = Byte.toUnsignedInt(rgba.get(offset + 2)),
                        a = Byte.toUnsignedInt(rgba.get(offset + 3));
                    if (a != 255)
                        throw new AssertionError("Native output alpha must be opaque");
                    int argb = a << 24 | r << 16 | g << 8 | b;
                    if (x == 0 && y == 0)
                        firstColor = argb;
                    else
                        varied |= argb != firstColor;
                    image.setRGB(x, y, argb);
                }
            if (!varied)
                throw new AssertionError("Native renderer returned a constant image");
            ImageIO.write(image, "png", Path.of(args[1]).toFile());
            byte[] baseline = new byte[width * height * 4];
            rgba.get(0, baseline);
            byte[] nightFrame = frame.clone();
            ByteBuffer.wrap(nightFrame)
                    .order(ByteOrder.LITTLE_ENDIAN)
                    .putFloat(100, (float)Math.PI);
            if (Arrays.equals(baseline, pixels(bridge, nightFrame, rgba)))
                throw new AssertionError("Solar hour angle did not reach native lighting");
            bridge.configure(RenderSettings.defaults()
                                     .with(RenderSettings.Control.LATITUDE, -47)
                                     .with(RenderSettings.Control.SOLAR_LONGITUDE, 90),
                             false, RenderSettings.View.OUTPUT);
            if (Arrays.equals(baseline, pixels(bridge, frame, rgba)))
                throw new AssertionError("Observer and season did not reach native lighting");
            bridge.configure(RenderSettings.defaults(), false, RenderSettings.View.OUTPUT);
            if (!Arrays.equals(baseline, pixels(bridge, frame, rgba)))
                throw new AssertionError("Restored astronomy did not reproduce the noon image");
            bridge.configure(RenderSettings.defaults(), true, RenderSettings.View.OUTPUT);
            byte[] moved = Packets.frame(999, 80000, 90000, 70000, new float[] {1, 0, 0},
                                         new float[] {0, 0, 1}, new float[] {0, 1, 0}, .5f, width,
                                         height, 0);
            ByteBuffer.wrap(moved).order(ByteOrder.LITTLE_ENDIAN).putFloat(100, (float)Math.PI);
            bridge.configure(RenderSettings.defaults()
                                     .with(RenderSettings.Control.BOUNCES, 1)
                                     .with(RenderSettings.Control.SUN_EV, 4)
                                     .with(RenderSettings.Control.LATITUDE, -47)
                                     .with(RenderSettings.Control.SOLAR_LONGITUDE, 90),
                             true, RenderSettings.View.OUTPUT);
            bridge.renderDiagnostic(moved, rgba);
            byte[] frozen = new byte[baseline.length];
            rgba.get(0, frozen);
            if (!Arrays.equals(baseline, frozen))
                throw new AssertionError("Frozen pose/epoch differs from the displayed snapshot");
            try {
                bridge.submit(Packets.reset(2));
                throw new AssertionError("Frozen scene accepted a mutation");
            } catch (IllegalStateException expected) {
                if (!expected.getMessage().contains("frozen"))
                    throw expected;
            }
            bridge.configure(RenderSettings.defaults(), false, RenderSettings.View.OUTPUT);
            bridge.renderDiagnostic(frame, rgba);
            rgba.get(0, frozen);
            if (!Arrays.equals(baseline, frozen))
                throw new AssertionError("Realtime did not resume after the snapshot");

            bridge.submit(Packets.texture(1, 7, 1, 1, new byte[] {0, 50, -1, -1}));
            bridge.submit(Packets.texture(1, 8, 1, 1, new byte[] {-1, 0, 0, 0}));
            bridge.submit(Packets.texture(1, 9, 1, 1, new byte[] {0, -1, 0, -1}));
            completeSections(bridge, vertices, frame, rgba, baseline, width, height);
            try (var dynamic = new DynamicFrame(64)) {
                dynamic.begin(1, 1, 0, 0, 0);
                wall(dynamic, 7, 0);
                bridge.submit(dynamic.seal());
                bridge.renderDiagnostic(frame, rgba);
                int center = ((height / 2) * width + width / 2) * 4;
                if (Byte.toUnsignedInt(rgba.get(center + 2)) <=
                    Byte.toUnsignedInt(rgba.get(center)))
                    throw new AssertionError(
                            "Batched dynamic blue wall is not visible at the center");
                byte[] dynamicPixels = new byte[baseline.length];
                rgba.get(0, dynamicPixels);
                if (Arrays.equals(baseline, dynamicPixels))
                    throw new AssertionError("Dynamic frame did not affect output");
                dynamic.begin(1, 2, 0, 0, 0);
                bridge.submit(dynamic.seal());
                bridge.renderDiagnostic(frame, rgba);
                rgba.get(0, dynamicPixels);
                if (!Arrays.equals(baseline, dynamicPixels))
                    throw new AssertionError(
                            "Empty dynamic frame did not restore the static scene");
                dynamic.begin(1, 3, 0, 0, 0);
                wall(dynamic, 8, 2);
                bridge.submit(dynamic.seal());
                bridge.renderDiagnostic(frame, rgba);
                rgba.get(0, dynamicPixels);
                if (!Arrays.equals(baseline, dynamicPixels))
                    throw new AssertionError(
                            "Alpha-zero dynamic surface changed visibility or shadowing");
            }
            try (var instances = new InstanceCapture(1)) {
                ByteBuffer local = ByteBuffer.allocate(96).order(ByteOrder.LITTLE_ENDIAN);
                for (float[] point : new float[][] {{-1, 0}, {1, 0}, {1, 2}, {-1, 2}})
                    local.putFloat(point[0])
                            .putFloat(point[1])
                            .putFloat(0)
                            .putInt(-1)
                            .putFloat(.5f)
                            .putFloat(.5f);
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
                if (Byte.toUnsignedInt(rgba.get(center + 2)) <=
                    Byte.toUnsignedInt(rgba.get(center)))
                    throw new AssertionError(
                            "Persistent instance texture or affine transform is not visible");
                instances.beginFrame();
                instances.observe(wall, prototype, 0, 0, 2, transform, 7, 0, -1, uv);
                instances.endFrame();
                if (instances.sealDelta() != null)
                    throw new AssertionError("Stable instances must not cross FFM again");
                instances.release(prototype);
                instances.beginFrame();
                instances.endFrame();
                bridge.submit(instances.sealDelta());
                instances.acknowledge();
                bridge.renderDiagnostic(frame, rgba);
                byte[] removed = new byte[baseline.length];
                rgba.get(0, removed);
                if (!Arrays.equals(baseline, removed))
                    throw new AssertionError(
                            "Atomic prototype/instance removal did not restore the scene");
            }
            var crossThreadError = new AtomicReference<Throwable>();
            Thread thread = Thread.ofPlatform().start(() -> {
                try {
                    bridge.submit(Packets.reset(2));
                } catch (Throwable error) {
                    crossThreadError.set(error);
                }
            });
            thread.join();
            if (!(crossThreadError.get() instanceof IllegalStateException))
                throw new AssertionError("FFM renderer thread ownership was not enforced");
            boolean rejected = false;
            try {
                bridge.submit(new byte[8]);
            } catch (IllegalStateException expected) {
                rejected = true;
            }
            if (!rejected)
                throw new AssertionError("Malformed packet must fail at the native boundary");
            System.out.println(
                    "FFM Vulkan smoke passed: solar time/observer/season and frozen astronomy; 63/64-section visibility gate, withdrawal/reload, complete-section, raw and persistent-instance packets -> Rust -> Slang -> RGBA; multi-layer op8/unchanged/material change/late rejection/removal, texture owner/reference retirement, bulk section removal and completion watermark, dynamic visibility, affine/texture instance, unchanged-frame zero-submit, atomic removal, alpha-zero visibility/shadows, owner-thread and malformed-input checks; " +
                    args[1]);
        }
    }

    private static void completeSections(NativeBridge bridge, ByteBuffer floor, byte[] frame,
                                         ByteBuffer output, byte[] baseline, int width,
                                         int height) {
        var ground = new Packets.SectionLayer(0, 1, 0, 4, 4, 28, 0, 12, 16, floor);
        ByteBuffer wall = ByteBuffer.allocate(96).order(ByteOrder.LITTLE_ENDIAN);
        for (float[] p : new float[][] {{-2, 0, 2}, {2, 0, 2}, {2, 4, 2}, {-2, 4, 2}})
            wall.putFloat(p[0]).putFloat(p[1]).putFloat(p[2]).putInt(-1).putFloat(.5f).putFloat(
                    .5f);
        wall.flip();
        var blueWall = new Packets.SectionLayer(1, 7, 1, 4, 4, 24, 0, 12, 16, wall);
        var greenWall = new Packets.SectionLayer(1, 9, 1, 4, 4, 24, 0, 12, 16, wall);
        int center = ((height / 2) * width + width / 2) * 4;

        bridge.submit(Packets.sectionReplace(1, 1, 2, 0, 0, 0, List.of(ground, blueWall)));
        byte[] blue = pixels(bridge, frame, output);
        if (Byte.toUnsignedInt(blue[center + 2]) <= Byte.toUnsignedInt(blue[center]) ||
            Arrays.equals(blue, baseline))
            throw new AssertionError(
                    "Complete two-layer section did not display its blue cutout wall");
        bridge.submit(Packets.sectionReplace(1, 1, 3, 0, 0, 0, List.of(blueWall, ground)));
        if (!Arrays.equals(blue, pixels(bridge, frame, output)))
            throw new AssertionError(
                    "Identical section content with reordered layers changed rendered output");

        bridge.submit(Packets.sectionReplace(1, 1, 4, 0, 0, 0, List.of(ground, greenWall)));
        byte[] green = pixels(bridge, frame, output);
        if (Byte.toUnsignedInt(green[center + 1]) <= Byte.toUnsignedInt(green[center + 2]) ||
            Arrays.equals(blue, green))
            throw new AssertionError("Section material change was not visible");
        bridge.submit(Packets.retireTextures(1, new int[] {9}));
        if (!Arrays.equals(green, pixels(bridge, frame, output)))
            throw new AssertionError("Texture owner retirement invalidated a live scene reference");
        // The first candidate layer differs. A failure in the final layer must publish neither change.
        ByteBuffer changedFloor =
                ByteBuffer.allocate(floor.remaining()).order(ByteOrder.LITTLE_ENDIAN);
        changedFloor.put(floor.duplicate()).flip();
        changedFloor.putFloat(4, 1.0f);
        var changedGround = new Packets.SectionLayer(0, 1, 0, 4, 4, 28, 0, 12, 16, changedFloor);
        var missingTexture = new Packets.SectionLayer(1, 999, 1, 4, 4, 24, 0, 12, 16, wall);
        reject(bridge,
               Packets.sectionReplace(1, 1, 5, 0, 0, 0, List.of(changedGround, missingTexture)));
        if (!Arrays.equals(green, pixels(bridge, frame, output)))
            throw new AssertionError("Rejected late section layer partially changed GPU output");

        bridge.submit(Packets.sectionReplace(1, 1, 5, 0, 0, 0, List.of(ground)));
        if (!Arrays.equals(baseline, pixels(bridge, frame, output)))
            throw new AssertionError(
                    "Complete section replacement did not remove its absent wall layer");
        bridge.submit(Packets.sectionReplace(1, 1, 6, 0, 0, 0, List.of()));
        byte[] empty = pixels(bridge, frame, output);
        if (Arrays.equals(baseline, empty))
            throw new AssertionError("Empty complete section did not clear geometry");
        reject(bridge, Packets.sectionReplace(1, 1, 5, 0, 0, 0,
                                              java.util.List.of(new Packets.SectionLayer(
                                                      2, 1, 0, 4, 4, 28, 0, 12, 16, floor))));
        if (!Arrays.equals(empty, pixels(bridge, frame, output)))
            throw new AssertionError(
                    "Stale packet resurrected a layer after a newer complete section removal");
        bridge.submit(Packets.sectionReplace(1, 1, 7, 0, 0, 0, List.of(ground)));
        if (!Arrays.equals(baseline, pixels(bridge, frame, output)))
            throw new AssertionError(
                    "Newer complete section did not restore the original geometry");
        reject(bridge, Packets.sectionReplace(1, 1, 8, 0, 0, 0, List.of(ground, greenWall)));
        if (!Arrays.equals(baseline, pixels(bridge, frame, output)))
            throw new AssertionError("Collected texture was still accepted or changed the scene");

        // The last removed section has a layer absent from the unchanged surviving floor.
        // Its reusable removal scratch must not contaminate the following op8 transaction.
        bridge.submit(Packets.sectionReplace(
                1, 1002, 10, 0, 0, 32,
                List.of(new Packets.SectionLayer(1, 1, 0, 4, 4, 28, 0, 12, 16, floor))));
        bridge.submit(Packets.removeSections(1, new long[] {1001, 1002}, new long[] {20, 21}));
        bridge.submit(Packets.sectionWatermark(1, 21));
        bridge.submit(Packets.sectionReplace(1, 1, 22, 0, 0, 0, List.of(ground)));
        if (Arrays.equals(baseline, pixels(bridge, frame, output)))
            throw new AssertionError("Bulk removal left a partially ready terrain cell visible");
        reject(bridge, Packets.sectionReplace(1, 1001, 20, 0, 0, 16, List.of()));
        bridge.submit(Packets.sectionReplace(1, 1001, 23, 0, 0, 16, List.of()));
        bridge.submit(Packets.sectionReplace(1, 1002, 24, 0, 0, 32, List.of()));
        if (!Arrays.equals(baseline, pixels(bridge, frame, output)))
            throw new AssertionError(
                    "New producers did not restore terrain after completed history retirement");
    }

    private static byte[] pixels(NativeBridge bridge, byte[] frame, ByteBuffer output) {
        bridge.renderDiagnostic(frame, output);
        byte[] result = new byte[output.capacity()];
        output.get(0, result);
        return result;
    }

    private static void reject(NativeBridge bridge, byte[] packet) {
        try {
            bridge.submit(packet);
        } catch (IllegalStateException expected) {
            return;
        }
        throw new AssertionError("Invalid section transaction was accepted");
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
