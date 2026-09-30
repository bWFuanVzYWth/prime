package dev.primept.capture;

import dev.primept.PrimeClient;
import dev.primept.mixin.SpriteContentsAccessor;
import java.nio.ByteBuffer;
import net.minecraft.client.renderer.texture.SpriteLoader;

/** Resource epoch and atlas ownership. Terrain scheduling lives solely in Rust. */
public final class CaptureInbox {
    private long epoch = 1, atlasVersion;
    private boolean resourceActive = Boolean.getBoolean("primept.enabled");
    private RuntimeException failure;
    private Atlas atlas;
    public record Atlas(long version, int width, int height, byte[] rgba) {}
    public synchronized void captureAtlas(SpriteLoader.Preparations preparations) {
        if (!resourceActive)
            return;
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
            // UVs from earlier compiles refer to the old packing; invalidate that entire capture epoch.
            reset();
            atlas = new Atlas(++atlasVersion, width, height, rgba);
            PrimeClient.LOGGER.info("Captured block atlas {}x{}, {} bytes, resource epoch {}",
                                    width, height, size, epoch);
        } catch (RuntimeException exception) {
            fail(exception);
        }
    }

    public synchronized Atlas atlas() {
        return atlas;
    }
    public synchronized long epoch() {
        return epoch;
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
        failure = null;
    }
    private void fail(RuntimeException exception) {
        failure = exception;
        disable();
        PrimeClient.LOGGER.error("Prime PT capture disabled", exception);
    }
}
