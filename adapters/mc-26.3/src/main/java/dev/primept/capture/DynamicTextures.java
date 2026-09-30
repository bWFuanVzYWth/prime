package dev.primept.capture;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.renderpearl.api.textures.GpuTexture;
import dev.primept.NativeBridge;
import dev.primept.PrimeClient;
import dev.primept.mixin.SpriteContentsAccessor;
import java.util.IdentityHashMap;
import java.nio.ByteBuffer;
import java.util.LinkedHashSet;
import net.minecraft.client.renderer.texture.SpriteLoader;
import net.minecraft.client.renderer.texture.TextureAtlas;

/** Render-thread source snapshots taken from actual uploads; no GPU readback or resource reload. */
public final class DynamicTextures {
    private static final IdentityHashMap<GpuTexture, Texture> SOURCES = new IdentityHashMap<>();
    private static final LinkedHashSet<Texture> NEEDED = new LinkedHashSet<>();
    private static final java.util.LinkedHashMap<Integer, Long> RETIRED =
            new java.util.LinkedHashMap<>();
    private static int nextId = 2;
    private static long retainedBytes;
    private static RuntimeException failure;
    private static int reportedRecoveries;
    private DynamicTextures() {}

    private static final class Texture {
        final int id, width, height;
        final byte[] rgba;
        long sentEpoch;
        Texture(int id, int width, int height, byte[] rgba) {
            this.id = id;
            this.width = width;
            this.height = height;
            this.rgba = rgba;
        }
    }

    public static void image(GpuTexture texture, NativeImage image) {
        if (!PrimeClient.captureResourcesEnabled() || failure != null || texture == null ||
            image == null)
            return;
        try {
            captureImage(texture, image);
        } catch (RuntimeException exception) {
            failed(exception);
        }
    }

    private static void captureImage(GpuTexture texture, NativeImage image) {
        // Font distance fields and other non-color formats need their own declared material path.
        if (image.format() != NativeImage.Format.RGBA)
            return;
        var pixels = image.getPixelBytes();
        Texture previous = SOURCES.get(texture);
        // Repeated identical uploads keep their snapshot and native material generation.
        if (previous != null && previous.width == image.getWidth() &&
            previous.height == image.getHeight() &&
            pixels.mismatch(ByteBuffer.wrap(previous.rgba)) == -1)
            return;
        byte[] rgba = new byte[captureSize(texture, image.getWidth(), image.getHeight())];
        pixels.get(0, rgba);
        put(texture, image.getWidth(), image.getHeight(), rgba, false);
    }

    public static void atlas(TextureAtlas atlas, SpriteLoader.Preparations preparations) {
        if (!PrimeClient.captureResourcesEnabled() || failure != null)
            return;
        try {
            captureAtlas(atlas, preparations);
        } catch (RuntimeException exception) {
            failed(exception);
        }
    }

    private static void captureAtlas(TextureAtlas atlas, SpriteLoader.Preparations preparations) {
        if (atlas.location().equals(TextureAtlas.LOCATION_BLOCKS)) {
            var captured = PrimeClient.CAPTURE.atlas();
            if (captured != null)
                put(atlas.getTexture(), captured.width(), captured.height(), captured.rgba(), true);
            return;
        }
        int width = preparations.width(), height = preparations.height();
        byte[] rgba = new byte[captureSize(atlas.getTexture(), width, height)];
        for (var sprite : preparations.regions().values()) {
            var contents = sprite.contents();
            var pixels = ((SpriteContentsAccessor)contents).primept$originalImage();
            var source = pixels.getPixelBytes();
            int w = contents.width(), h = contents.height();
            int frame = contents.isAnimated() ? contents.getUniqueFrames().getInt(0) : 0;
            int sx = frame % (pixels.getWidth() / w) * w, sy = frame / (pixels.getWidth() / w) * h;
            int dx = Math.round(sprite.getU0() * width), dy = Math.round(sprite.getV0() * height);
            for (int y = 0; y < h; ++y)
                source.get(((sy + y) * pixels.getWidth() + sx) * 4, rgba,
                           ((dy + y) * width + dx) * 4, w * 4);
        }
        put(atlas.getTexture(), width, height, rgba, false);
    }

    private static int captureSize(GpuTexture texture, int width, int height) {
        int bytes = Math.multiplyExact(Math.multiplyExact(width, height), 4);
        if (width <= 0 || height <= 0 || bytes > (256 << 20) - 40)
            throw new IllegalStateException("Dynamic source texture exceeds the packet capacity");
        Texture previous = SOURCES.get(texture);
        if (retainedBytes - (previous == null ? 0 : previous.rgba.length) + bytes > 512L << 20)
            throw new IllegalStateException("Dynamic source textures exceed 512 MiB");
        return bytes;
    }

    private static void put(GpuTexture texture, int width, int height, byte[] rgba,
                            boolean blockAtlas) {
        Texture previous = SOURCES.get(texture);
        long newTotal = retainedBytes - (previous == null ? 0 : previous.rgba.length) + rgba.length;
        if (newTotal > 512L << 20)
            throw new IllegalStateException("Dynamic source textures exceed 512 MiB");
        if (!blockAtlas && previous == null && nextId >= 0x40000000)
            throw new IllegalStateException("Dynamic texture identity space exhausted");
        int id = blockAtlas ? 1 : previous == null ? nextId++ : previous.id;
        SOURCES.put(texture, new Texture(id, width, height, rgba));
        retainedBytes = newTotal;
    }

    public static void release(GpuTexture texture) {
        CanonicalTextureSources.release(texture);
        Texture previous = SOURCES.remove(texture);
        if (previous != null) {
            retainedBytes -= previous.rgba.length;
            if (previous.id != 1)
                RETIRED.put(previous.id, PrimeClient.CAPTURE.epoch());
        }
    }

    public static void beginFrame() {
        NEEDED.clear();
    }
    public static void releaseSources() {
        SOURCES.clear();
        NEEDED.clear();
        retainedBytes = 0;
        nextId = 2;
        RETIRED.clear();
        failure = null;
        reportedRecoveries = 0;
    }
    /** Report capture-only failures at the PT hook, after vanilla's resource upload has succeeded. */
    public static void throwIfFailed() {
        if (failure != null)
            throw new IllegalStateException("Dynamic source texture capture failed", failure);
    }
    private static void failed(RuntimeException exception) {
        failure = exception;
        SOURCES.clear();
        NEEDED.clear();
        retainedBytes = 0;
    }
    public static int use(GpuTexture texture) {
        Texture source = SOURCES.get(texture);
        if (source == null) {
            NativeImage canonical = CanonicalTextureSources.pixels(texture);
            if (canonical != null) {
                captureImage(texture, canonical);
                source = SOURCES.get(texture);
                if (source != null && reportedRecoveries++ < 4)
                    PrimeClient.LOGGER.info(
                            "Restored canonical texture source kind={} label={} size={}x{}",
                            CanonicalTextureSources.kind(texture), texture.getLabel(), source.width,
                            source.height);
            }
        }
        if (source == null)
            throw new IllegalStateException(
                    "No captured source pixels for dynamic texture label=" + texture.getLabel() +
                    " sourceKind=" + CanonicalTextureSources.kind(texture));
        if (source.id != 1)
            NEEDED.add(source);
        return source.id;
    }
    public static void submit(long epoch, NativeBridge bridge) {
        for (Texture source : NEEDED) {
            if (source.sentEpoch == epoch)
                continue;
            bridge.submit(
                    Packets.texture(epoch, source.id, source.width, source.height, source.rgba));
            source.sentEpoch = epoch;
        }
        if (!RETIRED.isEmpty()) {
            int[] ids = RETIRED.entrySet()
                                .stream()
                                .filter(entry -> entry.getValue() == epoch)
                                .mapToInt(java.util.Map.Entry::getKey)
                                .toArray();
            if (ids.length != 0)
                bridge.submit(Packets.retireTextures(epoch, ids));
            RETIRED.clear();
        }
    }
}
