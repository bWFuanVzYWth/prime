package dev.prime.render.vulkan;

import com.mojang.blaze3d.vulkan.Destroyable;
import dev.prime.render.terrain.CanonicalColorEncoding;
import dev.prime.render.terrain.LabPbrAtlasFrame;
import org.lwjgl.system.MemoryUtil;

/** Translated mip chains for one animated canonical texture channel. */
final class TextureAnimationFrames implements Destroyable {
    private final TexturePageLayout.Placement placement;
    private final boolean color;
    private final boolean specular;
    private final int frameCount;
    private final int mipLevels;
    private final long frameStride;
    private final long[] mipOffsets;
    private final long byteSize;
    private long address;

    static TextureAnimationFrames color(
            TexturePageLayout.Placement placement,
            LabPbrAtlasFrame.ColorSource source,
            int mipLevels) {
        return new TextureAnimationFrames(placement, source, mipLevels, true, false);
    }

    static TextureAnimationFrames material(
            TexturePageLayout.Placement placement,
            LabPbrAtlasFrame.MaterialSource source,
            int mipLevels,
            boolean specular) {
        return new TextureAnimationFrames(placement, source, mipLevels, false, specular);
    }

    private TextureAnimationFrames(
            TexturePageLayout.Placement placement,
            LabPbrAtlasFrame.TextureSource source,
            int mipLevels,
            boolean color,
            boolean specular) {
        if (mipLevels <= 0) {
            throw new IllegalArgumentException("Animation mip count must be positive");
        }
        this.placement = placement;
        this.color = color;
        this.specular = specular;
        this.frameCount = source.frameCount();
        this.mipLevels = mipLevels;
        this.mipOffsets = new long[mipLevels];
        long stride = 0L;
        LabPbrAtlasFrame.Sprite sprite = placement.sprite();
        for (int mip = 0; mip < mipLevels; mip++) {
            this.mipOffsets[mip] = stride;
            stride = Math.addExact(
                    stride,
                    Math.multiplyExact(
                            Math.multiplyExact(
                                    (long) sprite.mipWidth(mip), sprite.mipHeight(mip)),
                            color ? 4L * Float.BYTES : 4L));
        }
        this.frameStride = stride;
        this.byteSize = Math.multiplyExact(stride, this.frameCount);
        this.address = MemoryUtil.nmemAllocChecked(this.byteSize);
        try {
            for (int frame = 0; frame < this.frameCount; frame++) {
                LabPbrAtlasFrame.AnimationSample sample =
                        new LabPbrAtlasFrame.AnimationSample(frame, frame, 0);
                for (int mip = 0; mip < mipLevels; mip++) {
                    long target = this.address + (long) frame * this.frameStride
                            + this.mipOffsets[mip];
                    if (color) {
                        MaterialTexturePages.writeColorSpriteF32(
                                target,
                                0L,
                                sprite.mipWidth(mip),
                                placement,
                                (LabPbrAtlasFrame.ColorSource) source,
                                sample,
                                mip,
                                true);
                    } else {
                        MaterialTexturePages.writeSprite(
                                target,
                                0L,
                                sprite.mipWidth(mip),
                                placement,
                                (LabPbrAtlasFrame.MaterialSource) source,
                                sample,
                                mip,
                                true,
                                specular);
                    }
                }
            }
        } catch (RuntimeException | Error failure) {
            MemoryUtil.nmemFree(this.address);
            this.address = 0L;
            throw failure;
        }
    }

    TexturePageLayout.Placement placement() {
        return this.placement;
    }

    int mipLevels() {
        return this.mipLevels;
    }

    long byteSize() {
        return this.byteSize;
    }

    void write(long target, LabPbrAtlasFrame.AnimationSample sample, int mip) {
        if (this.address == 0L) {
            throw new IllegalStateException("Animation frames are destroyed");
        }
        if (mip < 0 || mip >= this.mipLevels) {
            throw new IllegalArgumentException("Animation mip is out of range");
        }
        int current = this.frameIndex(sample.currentFrame());
        int next = this.frameIndex(sample.nextFrame());
        long currentAddress = this.frameAddress(current, mip);
        long nextAddress = this.frameAddress(next, mip);
        if (!this.color) {
            this.writeMaterial(target, sample, mip, current, next, currentAddress, nextAddress);
            return;
        }
        float nextWeight = current == next ? 0.0F : sample.progressThousandths() / 1000.0F;
        long pixels = this.mipPixels(mip);
        for (long pixel = 0L; pixel < pixels; pixel++) {
            long currentPixel = currentAddress + pixel * 4L * Float.BYTES;
            long nextPixel = nextAddress + pixel * 4L * Float.BYTES;
            float red = lerp(
                    MemoryUtil.memGetFloat(currentPixel),
                    MemoryUtil.memGetFloat(nextPixel),
                    nextWeight);
            float green = lerp(
                    MemoryUtil.memGetFloat(currentPixel + 4L),
                    MemoryUtil.memGetFloat(nextPixel + 4L),
                    nextWeight);
            float blue = lerp(
                    MemoryUtil.memGetFloat(currentPixel + 8L),
                    MemoryUtil.memGetFloat(nextPixel + 8L),
                    nextWeight);
            float coverage = lerp(
                    MemoryUtil.memGetFloat(currentPixel + 12L),
                    MemoryUtil.memGetFloat(nextPixel + 12L),
                    nextWeight);
            MaterialTexturePages.writeRgba16f(
                    target,
                    pixel * 8L,
                    CanonicalColorEncoding.encodeLinearRgba16f(
                            red, green, blue, coverage));
        }
    }

    private void writeMaterial(
            long target,
            LabPbrAtlasFrame.AnimationSample sample,
            int mip,
            int current,
            int next,
            long currentAddress,
            long nextAddress) {
        long bytes = this.mipPixels(mip) * 4L;
        int progress = this.frameCount == 1 ? 0 : sample.progressThousandths();
        if (progress == 0 || current == next) {
            MemoryUtil.memCopy(currentAddress, target, bytes);
            return;
        }
        for (long offset = 0L; offset < bytes; offset += 4L) {
            int blended = LabPbrAtlasFrame.MaterialSource.blendFiltered(
                    readArgb(currentAddress + offset),
                    readArgb(nextAddress + offset),
                    progress,
                    this.specular);
            MaterialTexturePages.writeArgb(target, offset, blended);
        }
    }

    private static float lerp(float current, float next, float nextWeight) {
        return current + (next - current) * nextWeight;
    }

    private int frameIndex(int requested) {
        return this.frameCount == 1
                ? 0
                : Math.max(0, Math.min(requested, this.frameCount - 1));
    }

    private long frameAddress(int frame, int mip) {
        return this.address + (long) frame * this.frameStride + this.mipOffsets[mip];
    }

    private long mipPixels(int mip) {
        LabPbrAtlasFrame.Sprite sprite = this.placement.sprite();
        return (long) sprite.mipWidth(mip) * sprite.mipHeight(mip);
    }

    private static int readArgb(long address) {
        return (MemoryUtil.memGetByte(address + 3L) & 0xff) << 24
                | (MemoryUtil.memGetByte(address) & 0xff) << 16
                | (MemoryUtil.memGetByte(address + 1L) & 0xff) << 8
                | MemoryUtil.memGetByte(address + 2L) & 0xff;
    }

    @Override
    public void destroy() {
        long released = this.address;
        this.address = 0L;
        if (released != 0L) {
            MemoryUtil.nmemFree(released);
        }
    }
}
