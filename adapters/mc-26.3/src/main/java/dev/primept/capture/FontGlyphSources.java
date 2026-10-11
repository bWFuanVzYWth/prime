package dev.primept.capture;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.textures.GpuTexture;
import java.nio.ByteBuffer;
import java.util.WeakHashMap;

/** Owned CPU mirrors of actual glyph uploads; no glyph callback is replayed. */
public final class FontGlyphSources {
    private record Bitmap(int width, int height, byte[] rgba) {}
    private static final WeakHashMap<GpuBuffer, Bitmap> BITMAPS = new WeakHashMap<>();
    private static final WeakHashMap<GpuTexture, Atlas> ATLASES = new WeakHashMap<>();
    private static final class Atlas {
        final int width, height;
        final byte[] rgba;
        long revision, publishedRevision = -1;
        Atlas(int width, int height) {
            this.width = width;
            this.height = height;
            rgba = new byte[Math.multiplyExact(Math.multiplyExact(width, height), 4)];
        }
    }
    private FontGlyphSources() {}
    public static void bitmap(GpuBuffer buffer, NativeImage image) {
        if (!BITMAPS.containsKey(buffer))
            BITMAPS.put(buffer, new Bitmap(image.getWidth(), image.getHeight(), rgba(image)));
    }
    public static void image(GpuTexture texture, NativeImage image, int mip, int layer, int x,
                             int y) {
        if (mip != 0 || layer != 0)
            throw new IllegalArgumentException("Unsupported font upload layer");
        patch(texture, x, y, image.getWidth(), image.getHeight(), image.getWidth(), 0, 0,
              rgba(image));
    }
    public static void bytes(GpuTexture texture, ByteBuffer source, int mip, int layer, int x,
                             int y, int width, int height) {
        if (mip != 0 || layer != 0)
            throw new IllegalArgumentException("Unsupported font upload layer");
        byte[] rgba = new byte[Math.multiplyExact(Math.multiplyExact(width, height), 4)];
        source.duplicate().get(rgba);
        patch(texture, x, y, width, height, width, 0, 0, rgba);
    }
    public static void copy(GpuBufferSlice source, int sx, int sy, int sw, int sh,
                            GpuTexture texture, int x, int y, int width, int height, int mip,
                            int layer) {
        Bitmap bitmap = BITMAPS.get(source.buffer());
        if (bitmap == null || source.offset() != 0 || bitmap.width != sw || bitmap.height != sh ||
            mip != 0 || layer != 0)
            throw new IllegalStateException("Missing canonical bitmap font upload source");
        patch(texture, x, y, width, height, sw, sx, sy, bitmap.rgba);
    }
    private static byte[] rgba(NativeImage image) {
        int channels = image.format().components();
        if (channels != 1 && channels != 4)
            throw new IllegalArgumentException("Unsupported glyph pixel format");
        int count = Math.multiplyExact(image.getWidth(), image.getHeight());
        byte[] rgba = new byte[Math.multiplyExact(count, 4)];
        ByteBuffer pixels = image.getPixelBytes();
        if (channels == 4)
            pixels.get(0, rgba);
        else
            for (int i = 0; i < count; ++i) {
                rgba[4 * i] = rgba[4 * i + 1] = rgba[4 * i + 2] = (byte)255;
                rgba[4 * i + 3] = pixels.get(i);
            }
        return rgba;
    }
    private static void patch(GpuTexture texture, int x, int y, int width, int height,
                              int sourceWidth, int sx, int sy, byte[] source) {
        Atlas atlas =
                ATLASES.computeIfAbsent(texture, t -> new Atlas(t.getWidth(0), t.getHeight(0)));
        if (x < 0 || y < 0 || width < 0 || height < 0 || x + width > atlas.width ||
            y + height > atlas.height)
            throw new IllegalArgumentException("Font patch outside atlas");
        for (int row = 0; row < height; ++row)
            System.arraycopy(source, ((sy + row) * sourceWidth + sx) * 4, atlas.rgba,
                             ((y + row) * atlas.width + x) * 4, width * 4);
        ++atlas.revision;
    }
    public static boolean publish(GpuTexture texture) {
        Atlas atlas = ATLASES.get(texture);
        if (atlas == null)
            return false;
        if (atlas.publishedRevision != atlas.revision) {
            DynamicTextures.font(texture, atlas.width, atlas.height, atlas.rgba.clone());
            atlas.publishedRevision = atlas.revision;
        }
        return true;
    }
    public static void invalidate() {
        for (Atlas atlas : ATLASES.values())
            atlas.publishedRevision = -1;
    }
    public static void release(GpuTexture texture) {
        ATLASES.remove(texture);
    }
}
