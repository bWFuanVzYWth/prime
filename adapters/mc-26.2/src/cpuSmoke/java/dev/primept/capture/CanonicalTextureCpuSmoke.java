package dev.primept.capture;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.textures.GpuTexture;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.function.Supplier;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.MissingTextureAtlasSprite;
import net.minecraft.client.renderer.texture.SkinTextureDownloader;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.client.resources.MapTextureManager;
import net.minecraft.core.ClientAsset;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.maps.MapId;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;

/** Actual transformed owner callbacks, with a CPU recording device in place of a graphics backend. */
final class CanonicalTextureCpuSmoke {
    private static final IdentityHashMap<GpuTexture, byte[]> UPLOADED = new IdentityHashMap<>();
    private static int uploads;
    static void run() throws Exception {
        Field device = field(RenderSystem.class, "DEVICE"),
              thread = field(RenderSystem.class, "renderThread");
        Object previousDevice = device.get(null), previousThread = thread.get(null);
        check(previousDevice == null, "PreLaunch must not have initialized a real GPU");
        device.set(null, fakeDevice());
        thread.set(null, Thread.currentThread());
        Object client = field(dev.primept.PrimeClient.class, "INSTANCE").get(null);
        Field requested = field(dev.primept.PrimeClient.class, "requested");
        Object previousRequested = requested.get(client);
        requested.set(client, "vanilla");
        DynamicTextures.releaseSources();
        check(!dev.primept.PrimeClient.captureResourcesEnabled(),
              "Actual production capture gate disabled in vanilla");
        fonts();
        try (var manager = new TextureManager(null)) {
            var missing =
                    (DynamicTexture)manager.getTexture(MissingTextureAtlasSprite.getLocation());
            check(CanonicalTextureSources.kind(missing.getTexture()).equals("MISSING"),
                  "Actual missing-image creator source");
            restored(missing);

            // Preserve actual upload sources across freeze/thaw, including noncanonical mutable textures.
            var offline =
                    (dev.primept.render.OfflineMode)field(dev.primept.PrimeClient.class, "offline")
                            .get(client);
            requested.set(client, "path_trace");
            try (var changing =
                         new DynamicTexture(() -> "offline source", new NativeImage(2, 2, true))) {
                int id = DynamicTextures.use(changing.getTexture());
                offline.request(true);
                offline.committed(true);
                changing.getPixels().setPixel(0, 0, 0xff2468ac);
                changing.upload();
                var sources = (java.util.Map<?,?>)field(DynamicTextures.class,"SOURCES").get(null);
                Object snapshot = sources.get(changing.getTexture());
                check(snapshot != null &&
                              Arrays.equals(
                                      (byte[])field(snapshot.getClass(), "rgba").get(snapshot),
                                      UPLOADED.get(changing.getTexture())),
                      "Frozen actual resource upload remains available for thaw");
                offline.reset();
                check(DynamicTextures.use(changing.getTexture()) == id,
                      "Thaw keeps source texture identity");
            } finally {
                offline.reset();
                requested.set(client, "vanilla");
                DynamicTextures.releaseSources();
            }

            var skinImage = new NativeImage(4, 4, true);
            skinImage.setPixel(0, 0, 0xff123456);
            var asset = new ClientAsset.ResourceTexture(Identifier.parse("primept:cpu_skin"));
            var downloader =
                    new SkinTextureDownloader(java.net.Proxy.NO_PROXY, manager, Runnable::run);
            var createSkin = SkinTextureDownloader.class.getDeclaredMethod(
                    "lambda$registerTextureInManager$0", ClientAsset.Texture.class,
                    NativeImage.class);
            createSkin.setAccessible(true);
            int before = uploads;
            check(createSkin.invoke(downloader, asset, skinImage) == asset,
                  "Actual skin registration return preserved");
            var skin = (DynamicTexture)manager.getTexture(asset.texturePath());
            check(uploads == before + 1 &&
                          CanonicalTextureSources.kind(skin.getTexture()).equals("SKIN"),
                  "Skin uploads exactly once and is tagged");
            restored(skin);
            NativeImage alias = skin.getPixels();
            check(alias == skinImage, "Real mutable alias result preserved");
            alias.setPixel(
                    0, 0,
                    0xfffedcba); // Changed CPU pixels without an upload must never be mistaken for GPU contents.
            DynamicTextures.releaseSources();
            rejected(skin.getTexture(), "SKIN/mutable-alias-exposed");
            skin.upload();
            CanonicalTextureSources.register(skin, CanonicalTextureSources.Kind.SKIN);
            DynamicTextures.releaseSources();
            rejected(skin.getTexture(), "SKIN/mutable-alias-exposed");
            var replacement = new NativeImage(4, 4, true);
            before = uploads;
            skin.setPixels(replacement);
            check(skinImage.isClosed() && uploads == before,
                  "Real setPixels closes old image without upload");
            rejected(skin.getTexture(), "SKIN/mutable-alias-exposed");

            try (var unknown = new DynamicTexture(
                         () -> "unknown CPU fixture", new NativeImage(2, 2, true))) {
                DynamicTextures.releaseSources();
                rejected(unknown.getTexture(), "unknown");
            }
            try (var neverUploaded = new DynamicTexture("map not yet uploaded", 128, 128, true)) {
                before = uploads;
                CanonicalTextureSources.register(neverUploaded, CanonicalTextureSources.Kind.MAP);
                check(uploads == before &&
                              CanonicalTextureSources.pixels(neverUploaded.getTexture()) == null,
                      "Width/height constructor does not imply uploaded pixels");
            }
            try (var maps = new MapTextureManager(manager)) {
                var data = MapItemSavedData.createForClient((byte)0, false, Level.OVERWORLD);
                Arrays.fill(data.colors, (byte)17);
                var id = new MapId(7341);
                before = uploads;
                var location = maps.prepareMapTexture(id, data);
                var map = (DynamicTexture)manager.getTexture(location);
                check(uploads == before + 1 &&
                              CanonicalTextureSources.kind(map.getTexture()).equals("MAP"),
                      "Actual map update uploads then tags");
                check(!((CanonicalDynamicTexture)map).primept$pixelsExposed(),
                      "Map's own pixel access is not external exposure");
                restored(map);
                before = uploads;
                maps.prepareMapTexture(id, data);
                check(uploads == before, "Stable prepare does not repeat update/upload");
                restored(map);
                data.colors[0] = 33;
                maps.update(id, data);
                maps.prepareMapTexture(id, data);
                check(uploads == before + 1, "Actual dirty map gets exactly one new upload");
                restored(map);
                map.getPixels();
                data.colors[0] = 49;
                maps.update(id, data);
                maps.prepareMapTexture(id, data);
                DynamicTextures.releaseSources();
                rejected(map.getTexture(), "MAP/mutable-alias-exposed");
                var mapTexture = map.getTexture();
                maps.resetData();
                check(mapTexture.isClosed() &&
                              CanonicalTextureSources.kind(mapTexture).equals("unknown"),
                      "Actual map close releases source provenance");
            }
            var skinTexture = skin.getTexture();
            skin.close();
            check(replacement.isClosed() &&
                          CanonicalTextureSources.kind(skinTexture).equals("unknown"),
                  "Actual texture close releases image and provenance");
        } finally {
            DynamicTextures.releaseSources();
            UPLOADED.clear();
            requested.set(client, previousRequested);
            device.set(null, previousDevice);
            thread.set(null, previousThread);
        }
        System.out.println(
                "PRIME_PT_CANONICAL_TEXTURE_CPU_OK: real Skin/Missing/Map callbacks; no replay/upload on recovery; private snapshot release; never-uploaded/unknown/mutable-alias/setPixels/close rejection; no GPU initialized");
    }
    private static void restored(DynamicTexture owner) throws Exception {
        GpuTexture texture = owner.getTexture();
        NativeImage host = ((CanonicalDynamicTexture)owner).primept$peekPixels();
        int before = uploads;
        check((long)field(DynamicTextures.class, "retainedBytes").get(null) == 0,
              "Actual uploads while vanilla retained no PT pixels");
        Object client = field(dev.primept.PrimeClient.class, "INSTANCE").get(null);
        field(dev.primept.PrimeClient.class, "requested").set(client, "path_trace");
        DynamicTextures.releaseSources();
        check((long)field(DynamicTextures.class, "retainedBytes").get(null) == 0 &&
                      !host.isClosed(),
              "Vanilla transition frees only PT snapshot");
        int id = DynamicTextures.use(texture);
        Object snapshot = ((IdentityHashMap<?, ?>) field(DynamicTextures.class, "SOURCES").get(null)).get(texture);
        byte[] rgba = (byte[])field(snapshot.getClass(), "rgba").get(snapshot);
        check(Arrays.equals(rgba, UPLOADED.get(texture)),
              "Recovered pixels exactly equal the actual upload");
        check(DynamicTextures.use(texture) == id && uploads == before,
              "One recovery, no repeated upload or resource callback");
        check(((IdentityHashMap<?, ?>) field(DynamicTextures.class, "SOURCES").get(null)).get(texture) == snapshot, "Stable use retains same snapshot");
        DynamicTextures.releaseSources();
        field(dev.primept.PrimeClient.class, "requested").set(client, "vanilla");
    }
    private static void rejected(GpuTexture texture, String kind) {
        try {
            DynamicTextures.use(texture);
            throw new AssertionError("Unsupported source accepted: " + kind);
        } catch (IllegalStateException expected) {
            check(expected.getMessage().contains("label=" + texture.getLabel()) &&
                          expected.getMessage().contains("sourceKind=" + kind),
                  "Diagnostic names actual label and source kind: " + expected);
        }
    }
    private static void fonts() throws Exception {
        for (String name :
             new String[] {"com.mojang.blaze3d.font.TrueTypeGlyphProvider$Glyph$1",
                           "net.minecraft.client.gui.font.providers.BitmapProvider$Glyph$1",
                           "net.minecraft.client.gui.font.providers.BitmapProvider$ImageDataHolder",
                           "net.minecraft.client.gui.font.providers.UnihexProvider$Glyph$2"})
            Class.forName(name); // Apply each real upload Mixin without initializing a GPU.
        var types = net.minecraft.client.gui.font.GlyphRenderTypes.createForColorTexture(
                Identifier.fromNamespaceAndPath("primept", "font-test"));
        try (var atlas = new net.minecraft.client.gui.font.FontTexture(
                     () -> "font test", types, true)) {
            var stitcher = new net.minecraft.client.gui.font.GlyphStitcher(
                    null, Identifier.fromNamespaceAndPath("primept", "font-test")) {
                @Override
                public net.minecraft.client.gui.font.glyphs.BakedSheetGlyph stitch(
                        com.mojang.blaze3d.font.GlyphInfo info,
                        com.mojang.blaze3d.font.GlyphBitmap bitmap) {
                    return atlas.add(info, bitmap);
                }
            };
            var white = net.minecraft.client.gui.font.glyphs.SpecialGlyphs.WHITE;
            int before = uploads;
            white.bake(stitcher);
            check(uploads == before + 1, "Actual glyph callback/upload executes once");
            int id = DynamicTextures.use(atlas.getTexture());
            fontSnapshot(atlas.getTexture());
            net.minecraft.client.gui.font.glyphs.SpecialGlyphs.MISSING.bake(stitcher);
            check(DynamicTextures.use(atlas.getTexture()) == id,
                  "Glyph append retains texture identity");
            fontSnapshot(atlas.getTexture());
            Class<?> lineType = Class.forName(
                    "net.minecraft.client.gui.font.providers.UnihexProvider$LineData");
            Class<?> contentsType = Class.forName(
                    "net.minecraft.client.gui.font.providers.UnihexProvider$IntContents");
            var contentsConstructor = contentsType.getDeclaredConstructor(int[].class, int.class);
            contentsConstructor.setAccessible(true);
            int[] lines = new int[16];
            Arrays.fill(lines, 0xf0f00000);
            Object contents = contentsConstructor.newInstance(lines, 32);
            var glyphConstructor =
                    Class.forName("net.minecraft.client.gui.font.providers.UnihexProvider$Glyph")
                            .getDeclaredConstructor(lineType, int.class, int.class);
            glyphConstructor.setAccessible(true);
            var glyph = (com.mojang.blaze3d.font.UnbakedGlyph)glyphConstructor.newInstance(contents,
                                                                                           0, 7);
            before = uploads;
            glyph.bake(new com.mojang.blaze3d.font.UnbakedGlyph.Stitcher() {
                public net.minecraft.client.gui.font.glyphs.BakedGlyph stitch(
                        com.mojang.blaze3d.font.GlyphInfo info,
                        com.mojang.blaze3d.font.GlyphBitmap bitmap) {
                    return atlas.add(info, bitmap);
                }
                public net.minecraft.client.gui.font.glyphs.BakedGlyph getMissing() {
                    return null;
                }
            });
            check(uploads == before + 1, "Actual Unihex upload is captured exactly once");
            check(DynamicTextures.use(atlas.getTexture()) == id,
                  "Unihex keeps atlas texture identity");
            fontSnapshot(atlas.getTexture());
            var needed = (java.util.Set<?>)field(DynamicTextures.class, "NEEDED").get(null);
            check(needed.size() == 1, "Same-frame font revisions publish one latest identity");
            DynamicTextures.releaseSources();
            DynamicTextures.use(atlas.getTexture());
            fontSnapshot(atlas.getTexture());
        }
        var gray = new FakeTexture(7, "gray font", GpuFormat.R8_UNORM, 4, 4, 1, 1);
        try (var image = new NativeImage(NativeImage.Format.LUMINANCE, 2, 2, true)) {
            image.getPixelBytes()
                    .put(0, (byte)0)
                    .put(1, (byte)64)
                    .put(2, (byte)128)
                    .put(3, (byte)255);
            FontGlyphSources.image(gray, image, 0, 0, 1, 1);
            DynamicTextures.use(gray);
            var map = (java.util.Map<?, ?>)field(DynamicTextures.class, "SOURCES").get(null);
            Object snapshot = map.get(gray);
            byte[] rgba = (byte[])field(snapshot.getClass(), "rgba").get(snapshot);
            check(rgba[(1 * 4 + 1) * 4] == (byte)255 && rgba[(1 * 4 + 2) * 4 + 3] == 64 &&
                          rgba[(2 * 4 + 1) * 4 + 3] == (byte)128,
                  "R8 source is white glyph color and exact coverage alpha");
        }
        DynamicTextures.release(gray);
    }
    private static void fontSnapshot(GpuTexture texture) throws Exception {
        var map = (java.util.Map<?, ?>)field(DynamicTextures.class, "SOURCES").get(null);
        Object snapshot = map.get(texture);
        check(Arrays.equals((byte[])field(snapshot.getClass(), "rgba").get(snapshot),
                            UPLOADED.get(texture)),
              "CPU glyph atlas matches actual uploaded patches");
    }
    private static void uploaded(Object[] args) {
        GpuTexture texture = (GpuTexture)args[0];
        NativeImage image = args[1] instanceof NativeImage value ? value : null;
        ByteBuffer pixels = image == null ? (ByteBuffer)args[1] : image.getPixelBytes();
        int width = image == null ? (int)args[6] : image.getWidth();
        int height = image == null ? (int)args[7] : image.getHeight();
        int x = args.length >= 6 ? (int)args[4] : 0, y = args.length >= 6 ? (int)args[5] : 0;
        byte[] copy = UPLOADED.computeIfAbsent(texture,
                                               t -> new byte[t.getWidth(0) * t.getHeight(0) * 4]);
        for (int row = 0; row < height; ++row)
            pixels.get(row * width * 4, copy, ((y + row) * texture.getWidth(0) + x) * 4, width * 4);
        ++uploads;
    }
    private static Object fakeDevice() {
        return new FakeDevice();
    }
    private static final class FakeDevice extends GpuDevice {
        FakeDevice() {
            super(null, () -> {});
        }
        @Override
        public CommandEncoder createCommandEncoder() {
            var backend = (com.mojang.blaze3d.systems.CommandEncoderBackend)Proxy.newProxyInstance(
                    CanonicalTextureCpuSmoke.class.getClassLoader(),
                    new Class<?>[] {com.mojang.blaze3d.systems.CommandEncoderBackend.class},
                    (proxy, method, args) -> {
                        if (method.getName().equals("writeToTexture")) {
                            uploaded(args);
                            return null;
                        }
                        throw new AssertionError(method);
                    });
            return new CommandEncoder(null, null, backend);
        }
        @Override
        public GpuTexture createTexture(Supplier<String> label, int usage, GpuFormat format,
                                        int width, int height, int depth, int mips) {
            return new FakeTexture(usage, label.get(), format, width, height, depth, mips);
        }
        @Override
        public GpuTexture createTexture(String label, int usage, GpuFormat format, int width,
                                        int height, int depth, int mips) {
            return new FakeTexture(usage, label, format, width, height, depth, mips);
        }
        @Override
        public com.mojang.blaze3d.textures.GpuTextureView createTextureView(GpuTexture texture) {
            return new com.mojang.blaze3d.textures.GpuTextureView(texture, 0, 1) {
                public void close() {}
                public boolean isClosed() {
                    return false;
                }
            };
        }
        @Override
        public com.mojang.blaze3d.textures.GpuSampler
        createSampler(com.mojang.blaze3d.textures.AddressMode a,
                      com.mojang.blaze3d.textures.AddressMode b,
                      com.mojang.blaze3d.textures.FilterMode c,
                      com.mojang.blaze3d.textures.FilterMode d, int e, java.util.OptionalDouble f) {
            return null;
        }
    }
    private static final class FakeTexture extends GpuTexture {
        boolean closed;
        FakeTexture(int usage, String label, GpuFormat format, int width, int height, int depth,
                    int mips) {
            super(usage, label, format, width, height, depth, mips);
        }
        public boolean isClosed() {
            return closed;
        }
        public void close() {
            closed = true;
        }
    }
    private static Field field(Class<?> type, String name) throws Exception {
        Field result = type.getDeclaredField(name);
        result.setAccessible(true);
        return result;
    }
    private static void check(boolean pass, String message) {
        if (!pass)
            throw new AssertionError(message);
    }
}
