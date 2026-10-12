package dev.primept.capture;

import dev.primept.StartupOptions;
import dev.primept.PrimeClient;
import dev.primept.mixin.SpriteContentsAccessor;
import java.nio.ByteBuffer;
import java.util.IdentityHashMap;
import java.util.List;
import net.minecraft.client.renderer.texture.SpriteLoader;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;

/** Resource epoch and atlas ownership. Terrain scheduling lives solely in Rust. */
public final class CaptureInbox {
    private long epoch = 1, atlasVersion;
    private long resourceGeneration, observedAtlas, itemVersion, observedItems;
    private Object observedModels, observedFluids;
    private boolean resourceActive = StartupOptions.enabled();
    private RuntimeException failure;
    private Atlas atlas;
    private List<TextureAtlasSprite> sprites = List.of(), itemSprites = List.of();
    private int itemAtlasTexture;
    // Published once after the matching native resource transaction succeeds. Never mutate a
    // published map: the render path reads real sprite identity without a lock or name lookup.
    private volatile IdentityHashMap<TextureAtlasSprite, Integer> itemTextures =
            new IdentityHashMap<>();
    public record Atlas(long version, int width, int height, byte[] rgba) {}
    public synchronized void captureAtlas(SpriteLoader.Preparations preparations) {
        if (!resourceActive)
            return;
        itemTextures = new IdentityHashMap<>();
        try {
            int width = preparations.width(), height = preparations.height();
            int size = Packets.texturePixelBytes(width, height);
            byte[] rgba = new byte[size];
            for (var sprite : preparations.regions().values()) {
                var contents = sprite.contents();
                var source = ((SpriteContentsAccessor)contents).primept$originalImage();
                ByteBuffer sourceBytes = source.getPixelBytes();
                int spriteWidth = contents.width(), spriteHeight = contents.height();
                int startX = Math.round(sprite.getU0() * width),
                    startY = Math.round(sprite.getV0() * height);
                // Capture the first source animation frame without evaluating animation or material colors.
                // 26.2 getUniqueFrames() returns [1] for static sprites; only animated entries are frame indices.
                int firstFrame = contents.isAnimated() ? contents.getUniqueFrames().getInt(0) : 0;
                int framesPerRow = source.getWidth() / spriteWidth;
                int sourceX = (firstFrame % framesPerRow) * spriteWidth;
                int sourceY = (firstFrame / framesPerRow) * spriteHeight;
                for (int y = 0; y < spriteHeight; y++) {
                    int offset = ((sourceY + y) * source.getWidth() + sourceX) * 4;
                    sourceBytes.get(offset, rgba, ((startY + y) * width + startX) * 4,
                                    spriteWidth * 4);
                }
            }
            // Resource replacement revokes old packing through its own generation transaction.
            // A new atlas does not replace the world or request whole-image temporal reset.
            atlas = new Atlas(++atlasVersion, width, height, rgba);
            sprites = List.copyOf(preparations.regions().values());
            PrimeClient.LOGGER.info("Captured block atlas {}x{}, {} bytes, resource epoch {}",
                                    width, height, size, epoch);
        } catch (RuntimeException exception) {
            fail(exception);
        }
    }

    public synchronized Atlas atlas() {
        return atlas;
    }
    public synchronized List<TextureAtlasSprite> sprites() {
        return sprites;
    }
    public synchronized void captureItemSprites(SpriteLoader.Preparations preparations) {
        captureItemSprites(preparations, 0);
    }
    public synchronized void captureItemSprites(SpriteLoader.Preparations preparations,
                                                int texture) {
        if (!resourceActive)
            return;
        itemSprites = List.copyOf(preparations.regions().values());
        itemAtlasTexture = texture;
        ++itemVersion;
        itemTextures = new IdentityHashMap<>();
    }
    public synchronized List<TextureAtlasSprite> itemSprites() {
        return itemSprites;
    }
    void publishItemSprites(IdentityHashMap<TextureAtlasSprite, Integer> sourceIds) {
        var textures = new IdentityHashMap<TextureAtlasSprite, Integer>();
        sourceIds.forEach((sprite, id) -> textures.put(sprite, 0x40000000 + id));
        itemTextures = textures;
    }
    int itemTexture(TextureAtlasSprite sprite, int atlasTexture) {
        if (sprite == null || atlasTexture != itemAtlasTexture || itemAtlasTexture == 0)
            return 0;
        return itemTextures.getOrDefault(sprite, 0);
    }
    public synchronized long epoch() {
        return epoch;
    }
    /** Resource identity is independent of world/geometry resets. No model callback is executed. */
    public synchronized long resourceGeneration(Object models, Object fluids) {
        if (atlas == null)
            throw new IllegalStateException("Missing prepared block atlas");
        if (observedAtlas != atlas.version() || observedItems != itemVersion ||
            observedModels != models || observedFluids != fluids) {
            resourceGeneration = Math.incrementExact(resourceGeneration);
            observedAtlas = atlas.version();
            observedItems = itemVersion;
            observedModels = models;
            observedFluids = fluids;
        }
        return resourceGeneration;
    }
    public synchronized RuntimeException failure() {
        return failure;
    }

    public synchronized void reset() {
        ++epoch;
    }

    public synchronized void disable() {
        reset();
    }
    public synchronized void enable() {
        reset();
        failure = null;
        resourceActive = true;
    }
    /** Drop PT-owned pixel copies while vanilla is selected. Host resource pixels remain host-owned. */
    public synchronized void releaseSources() {
        disable();
        resourceActive = false;
        atlas = null;
        sprites = List.of();
        itemSprites = List.of();
        itemAtlasTexture = 0;
        itemTextures = new IdentityHashMap<>();
        observedModels = null;
        observedFluids = null;
        observedAtlas = 0;
        observedItems = 0;
        failure = null;
    }
    private void fail(RuntimeException exception) {
        failure = exception;
        disable();
        PrimeClient.LOGGER.error("Prime PT capture disabled", exception);
    }
}
