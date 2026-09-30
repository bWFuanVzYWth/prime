package dev.primept.capture;

import com.mojang.blaze3d.platform.NativeImage;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import javax.imageio.ImageIO;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.metadata.animation.FrameSize;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.PackLocationInfo;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.metadata.MetadataSectionType;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraft.server.packs.resources.IoSupplier;
import net.minecraft.server.packs.resources.MultiPackResourceManager;

/** Actual host resource lookup and NativeImage PNG decoding; no Minecraft singleton or GPU. */
final class LabPbrSourcesCpuSmoke {
    private static final Identifier NAME =
            Identifier.fromNamespaceAndPath("prime_fixture", "block/material");
    private static final Identifier FORMAT =
            Identifier.withDefaultNamespace("optifine/texture.properties");
    private static final Identifier NORMAL =
            Identifier.fromNamespaceAndPath("prime_fixture", "textures/block/material_n.png");
    private static final Identifier SPECULAR =
            Identifier.fromNamespaceAndPath("prime_fixture", "textures/block/material_s.png");
    private static final byte[] DECLARATION =
            "format = LAB-PBR/1.3 \n".getBytes(StandardCharsets.ISO_8859_1);
    private static final byte[] NORMAL_BYTES = {20, (byte)180, 90, 3, (byte)235, 10, 5, (byte)255};
    private static final byte[] SPECULAR_BYTES = {(byte)128, (byte)255, 65, (byte)255,
                                                  (byte)255, (byte)238, 66, (byte)254};

    private static final class MemoryPack implements PackResources {
        private final Map<Identifier, byte[]> files;
        private final PackLocationInfo location =
                new PackLocationInfo("prime_labpbr_fixture", Component.literal("LabPBR fixture"),
                                     PackSource.DEFAULT, Optional.empty());
        MemoryPack(Map<Identifier, byte[]> files) {
            this.files = files;
        }
        @Override
        public PackLocationInfo location() {
            return location;
        }
        @Override
        public IoSupplier<InputStream> getRootResource(String... elements) {
            return null;
        }
        @Override
        public IoSupplier<InputStream> getResource(PackType type, Identifier name) {
            byte[] data = type == PackType.CLIENT_RESOURCES ? files.get(name) : null;
            return data == null ? null : () -> new ByteArrayInputStream(data);
        }
        @Override
        public void listResources(PackType type, String namespace, String path,
                                  ResourceOutput output) {
            for (var name : files.keySet())
                if (name.getNamespace().equals(namespace) && name.getPath().startsWith(path))
                    output.accept(name, getResource(type, name));
        }
        @Override
        public Set<String> getNamespaces(PackType type) {
            return files.keySet()
                    .stream()
                    .map(Identifier::getNamespace)
                    .collect(Collectors.toSet());
        }
        @Override
        public <T> T getMetadataSection(MetadataSectionType<T> type) {
            return null;
        }
        @Override
        public void close() {}
    }

    static void run() throws Exception {
        byte[] normal = png(NORMAL_BYTES), specular = png(SPECULAR_BYTES);
        var contents = new SpriteContents(NAME, new FrameSize(1, 1), new NativeImage(1, 1, true));
        try {
            var constructor = TextureAtlasSprite.class.getDeclaredConstructor(
                    Identifier.class, SpriteContents.class, int.class, int.class, int.class,
                    int.class, int.class);
            constructor.setAccessible(true);
            var sprite = constructor.newInstance(TextureAtlas.LOCATION_BLOCKS, contents, 16, 16, 0,
                                                 0, 0);
            verify(sprite, Map.of(FORMAT, DECLARATION, NORMAL, normal, SPECULAR, specular),
                   NORMAL_BYTES, SPECULAR_BYTES);
            verify(sprite, Map.of(FORMAT, DECLARATION, NORMAL, normal), NORMAL_BYTES, null);
            verify(sprite, Map.of(FORMAT, DECLARATION, SPECULAR, specular), null, SPECULAR_BYTES);
            absent(sprite, Map.of(FORMAT, DECLARATION), false);
            absent(sprite, Map.of(NORMAL, normal, SPECULAR, specular), false);
            absent(sprite,
                   Map.of(FORMAT, "format=lab-pbr/1.2\n".getBytes(StandardCharsets.ISO_8859_1),
                          NORMAL, normal),
                   false);
            absent(sprite,
                   Map.of(FORMAT, "format=\\uZZZZ\n".getBytes(StandardCharsets.ISO_8859_1), NORMAL,
                          normal),
                   false);
            // NativeImage normally converts 16-bit PNG samples to 8-bit, losing source categories.
            // A valid second plane must not turn a malformed/lossy first plane into a partial record.
            var sixteen = new BufferedImage(2, 1, BufferedImage.TYPE_USHORT_GRAY);
            sixteen.getRaster().setSample(0, 0, 0, 1);
            sixteen.getRaster().setSample(1, 0, 0, 65534);
            var encoded = new ByteArrayOutputStream();
            check(ImageIO.write(sixteen, "png", encoded), "16-bit PNG fixture writer");
            byte[] lossy = encoded.toByteArray();
            check(lossy[24] == 16, "fixture must contain 16-bit PNG samples");
            absent(sprite, Map.of(FORMAT, DECLARATION, NORMAL, lossy, SPECULAR, specular), true);
            absent(sprite, Map.of(FORMAT, DECLARATION, NORMAL, normal, SPECULAR, lossy), true);
            absent(sprite,
                   Map.of(FORMAT, DECLARATION, NORMAL, normal, SPECULAR, new byte[] {1, 2, 3}),
                   true);
            encoded.reset();
            check(ImageIO.write(new BufferedImage(16385, 1, BufferedImage.TYPE_INT_ARGB), "png",
                                encoded),
                  "oversized PNG fixture writer");
            absent(sprite,
                   Map.of(FORMAT, DECLARATION, NORMAL, normal, SPECULAR, encoded.toByteArray()),
                   true);
        } finally {
            contents.close();
        }
        System.out.println(
                "PRIME_PT_LABPBR_RESOURCE_IO_OK: actual resource manager, RGBA PNG bytes, optional planes, format gate, atomic malformed/lossy rejection");
    }

    private static byte[] png(byte[] rgba) throws Exception {
        var image = new BufferedImage(2, 1, BufferedImage.TYPE_INT_ARGB);
        for (int x = 0; x < 2; ++x) {
            int at = x * 4;
            image.setRGB(x, 0,
                         ((rgba[at + 3] & 255) << 24) | ((rgba[at] & 255) << 16) |
                                 ((rgba[at + 1] & 255) << 8) | (rgba[at + 2] & 255));
        }
        var encoded = new ByteArrayOutputStream();
        check(ImageIO.write(image, "png", encoded), "RGBA PNG fixture writer");
        return encoded.toByteArray();
    }

    private static void verify(TextureAtlasSprite sprite, Map<Identifier, byte[]> files,
                               byte[] normal, byte[] specular) {
        try (var manager = new MultiPackResourceManager(PackType.CLIENT_RESOURCES,
                                                        List.of(new MemoryPack(files)));
             var pages = new SourcePages()) {
            var sources = new LabPbrSources();
            check(sources.hasMaps(manager, List.of(sprite)),
                  "declared optional material source must be discoverable");
            sources.prepare(manager, pages, 73, NAME);
            var bytes = ByteBuffer.wrap(read(pages)).order(ByteOrder.LITTLE_ENDIAN);
            check(bytes.getInt() == 9 && bytes.getInt() == 73, "kind9 must retain sprite identity");
            plane(bytes, normal);
            plane(bytes, specular);
            check(!bytes.hasRemaining(), "kind9 record length");
        }
    }

    private static void absent(TextureAtlasSprite sprite, Map<Identifier, byte[]> files,
                               boolean present) {
        try (var manager = new MultiPackResourceManager(PackType.CLIENT_RESOURCES,
                                                        List.of(new MemoryPack(files)));
             var pages = new SourcePages()) {
            var sources = new LabPbrSources();
            check(sources.hasMaps(manager, List.of(sprite)) == present,
                  "material availability must respect format declaration");
            pages.i(0x10203040);
            sources.prepare(manager, pages, 73, NAME);
            check(pages.bytes() == 4,
                  "invalid/absent material must not append a partial kind9 record");
            check(ByteBuffer.wrap(read(pages)).order(ByteOrder.LITTLE_ENDIAN).getInt() ==
                          0x10203040,
                  "previous source bytes must survive rejected material I/O");
        }
    }

    private static void plane(ByteBuffer bytes, byte[] expected) {
        check(bytes.getInt() == (expected == null ? 0 : 1), "optional plane presence");
        if (expected == null)
            return;
        check(bytes.getInt() == 2 && bytes.getInt() == 1 && bytes.getInt() == 2,
              "decoded source dimensions");
        for (byte value : expected)
            check(bytes.get() == value,
                  "resource I/O must preserve raw channel/category/sentinel bytes");
    }

    private static byte[] read(SourcePages pages) {
        var table = pages.table();
        byte[] result = new byte[Math.toIntExact(pages.bytes())];
        long at = 0;
        for (long i = 0; i < pages.pageCount(); ++i) {
            long length = table.get(ValueLayout.JAVA_LONG, i * 16 + 8);
            var source = table.get(ValueLayout.ADDRESS, i * 16).reinterpret(length);
            MemorySegment.copy(source, 0, MemorySegment.ofArray(result), at, length);
            at += length;
        }
        check(at == result.length, "borrowed transport page lengths");
        return result;
    }

    private static void check(boolean value, String message) {
        if (!value)
            throw new AssertionError(message);
    }
}
