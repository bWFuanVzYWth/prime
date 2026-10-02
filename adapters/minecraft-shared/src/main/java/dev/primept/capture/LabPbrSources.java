package dev.primept.capture;

import com.mojang.blaze3d.platform.NativeImage;
import dev.primept.PrimeClient;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Properties;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.server.packs.resources.ResourceManager;

/** Resource I/O only. Rust owns LabPBR decoding, frame layout, filtering and sanitization. */
final class LabPbrSources {
    private Boolean supported;
    private boolean supported(ResourceManager manager) {
        if (supported == null)
            supported = declared(manager);
        return supported;
    }
    boolean hasMaps(Iterable<TextureAtlasSprite> sprites) {
        var minecraft = Minecraft.getInstance();
        if (minecraft == null)
            return false;
        var manager = minecraft.getResourceManager();
        return hasMaps(manager, sprites);
    }
    boolean hasMaps(ResourceManager manager, Iterable<TextureAtlasSprite> sprites) {
        if (manager == null || !supported(manager))
            return false;
        for (var sprite : sprites) {
            var name = sprite.contents().name();
            if (manager.getResource(location(name, "_n")).isPresent() ||
                manager.getResource(location(name, "_s")).isPresent())
                return true;
        }
        return false;
    }
    int[] prepare(McSourceBatch out, Identifier name) {
        var minecraft = Minecraft.getInstance();
        if (minecraft == null)
            return new int[] {-1, -1};
        var manager = minecraft.getResourceManager();
        return prepare(manager, out, name);
    }
    int[] prepare(ResourceManager manager, McSourceBatch out, Identifier name) {
        if (manager == null || !supported(manager))
            return new int[] {-1, -1};
        try (var normal = image(manager, name, "_n"); var specular = image(manager, name, "_s")) {
            if (normal == null && specular == null)
                return new int[] {-1, -1};
            return new int[] {write(out, normal), write(out, specular)};
        } catch (IOException | RuntimeException exception) {
            // Both planes are fully validated before appending the record. A malformed optional
            // plane must not publish a partially changed material under the same sprite identity.
            PrimeClient.LOGGER.warn("Unable to read LabPBR material {}", name, exception);
            return new int[] {-1, -1};
        }
    }
    private static boolean declared(ResourceManager manager) {
        var resource =
                manager.getResource(Identifier.withDefaultNamespace("optifine/texture.properties"));
        if (resource.isEmpty())
            return false;
        try (var input = resource.orElseThrow().open()) {
            var properties = new Properties();
            properties.load(input);
            String format = properties.getProperty("format", "").trim();
            if (format.equalsIgnoreCase("lab-pbr/1.3"))
                return true;
            PrimeClient.LOGGER.warn(
                    "Ignoring unsupported material format '{}'; requires lab-pbr/1.3", format);
        } catch (IOException | RuntimeException exception) {
            PrimeClient.LOGGER.warn("Unable to read LabPBR format declaration", exception);
        }
        return false;
    }
    private static Identifier location(Identifier name, String suffix) {
        return Identifier.fromNamespaceAndPath(name.getNamespace(),
                                               "textures/" + name.getPath() + suffix + ".png");
    }
    private static int write(McSourceBatch out, NativeImage image) {
        if (image == null) {
            return -1;
        }
        var pixels = image.getPixelBytes();
        return Math.toIntExact(out.image(image.getWidth(), image.getHeight(), pixels));
    }
    private static NativeImage image(ResourceManager manager, Identifier name, String suffix)
            throws IOException {
        var location = location(name, suffix);
        var resource = manager.getResource(location);
        if (resource.isEmpty())
            return null;
        NativeImage image = null;
        try (var input = new BufferedInputStream(resource.orElseThrow().open())) {
            input.mark(33);
            byte[] header = input.readNBytes(33);
            input.reset();
            if (header.length != 33)
                throw new IOException("Truncated material PNG: " + location);
            var png = ByteBuffer.wrap(header);
            if (png.getLong() != 0x89504e470d0a1a0aL || png.getInt() != 13 ||
                png.getInt() != 0x49484452)
                throw new IOException("Invalid material PNG header: " + location);
            int width = png.getInt(), height = png.getInt();
            if (width > 16384 || height > 16384)
                throw new IOException("Material PNG exceeds the source image extent: " + location);
            if ((png.get() & 255) > 8)
                throw new IOException("Material PNG exceeds the lossless RGBA8 source format: " +
                                      location);
            Packets.texturePixelBytes(width, height);
            image = NativeImage.read(input);
            if (image.format() != NativeImage.Format.RGBA)
                throw new IllegalArgumentException("Material source must be RGBA8");
            Packets.texturePixelBytes(image.getWidth(), image.getHeight());
            return image;
        } catch (IOException | RuntimeException exception) {
            if (image != null)
                image.close();
            throw exception;
        }
    }
}
