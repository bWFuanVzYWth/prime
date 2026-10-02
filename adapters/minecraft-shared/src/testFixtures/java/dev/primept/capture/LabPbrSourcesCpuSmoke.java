package dev.primept.capture;

import com.mojang.blaze3d.platform.NativeImage;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import dev.primept.abi.PrimeAbi.*;
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
             var batch = new McSourceBatch()) {
            var sources = new LabPbrSources();
            check(sources.hasMaps(manager, List.of(sprite)),
                  "declared optional material source must be discoverable");
            int[] images = sources.prepare(manager, batch, NAME);
            check(images.length == 2, "normal/specular image identities");
            plane(batch, images[0], normal);
            plane(batch, images[1], specular);
            check(batch.images.count() == (normal == null ? 0 : 1) + (specular == null ? 0 : 1),
                  "only present material images must be appended");
            check(batch.bytes.count() == (normal == null ? 0 : normal.length) +
                                                 (specular == null ? 0 : specular.length),
                  "material payload length");
            if (normal != null && specular != null)
                check(images[0] != images[1], "normal/specular image identities must be distinct");
        }
    }

    private static void absent(TextureAtlasSprite sprite, Map<Identifier, byte[]> files,
                               boolean present) {
        try (var manager = new MultiPackResourceManager(PackType.CLIENT_RESOURCES,
                                                        List.of(new MemoryPack(files)));
             var batch = new McSourceBatch()) {
            var sources = new LabPbrSources();
            check(sources.hasMaps(manager, List.of(sprite)) == present,
                  "material availability must respect format declaration");
            byte[] previous = {0x10, 0x20, 0x30, 0x40};
            batch.bytes.copy(MemorySegment.ofArray(previous));
            batch.emptyImage(3, 5);
            long before = batch.bytes();
            int[] images = sources.prepare(manager, batch, NAME);
            check(images.length == 2 && images[0] == -1 && images[1] == -1,
                  "invalid/absent material must return absent image identities");
            check(batch.bytes() == before && batch.images.count() == 1,
                  "invalid/absent material must not append partial typed images or payloads");
            check(java.util.Arrays.equals(batch.bytes.data().toArray(ValueLayout.JAVA_BYTE),
                                          previous),
                  "previous source bytes must survive rejected material I/O");
            check(PrimeMcImage.width(batch.images.get(0)) == 3 &&
                          PrimeMcImage.height(batch.images.get(0)) == 5,
                  "previous typed image must survive rejected material I/O");
        }
    }

    private static void plane(McSourceBatch batch, int index, byte[] expected) {
        check((index == -1) == (expected == null), "optional plane presence");
        if (expected == null)
            return;
        var image = batch.images.get(Integer.toUnsignedLong(index));
        check(PrimeMcImage.width(image) == 2 && PrimeMcImage.height(image) == 1,
              "decoded source dimensions");
        var range = PrimeMcImage.pixels(image);
        check(PrimeMcRange.count(range) == expected.length, "typed RGBA byte range length");
        var pixels =
                batch.bytes.data().asSlice(PrimeMcRange.offset(range), PrimeMcRange.count(range));
        for (int i = 0; i < expected.length; ++i)
            check(pixels.get(ValueLayout.JAVA_BYTE, i) == expected[i],
                  "resource I/O must preserve raw channel/category/sentinel bytes");
    }

    private static void check(boolean value, String message) {
        if (!value)
            throw new AssertionError(message);
    }
}
