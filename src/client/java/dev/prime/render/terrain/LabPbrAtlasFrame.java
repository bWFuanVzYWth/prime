package dev.prime.render.terrain;

import java.util.List;
import java.util.Objects;

/** Immutable Minecraft-independent LabPBR atlas source and current animation samples. */
public record LabPbrAtlasFrame(
        long sourceGeneration,
        Snapshot snapshot,
        List<AnimationSample> animations) {
    public LabPbrAtlasFrame {
        if (sourceGeneration < 0L) {
            throw new IllegalArgumentException("LabPBR source generation must be nonnegative");
        }
        Objects.requireNonNull(snapshot, "snapshot");
        animations = List.copyOf(animations);
        for (Sprite sprite : snapshot.sprites()) {
            if (sprite.animationIndex() >= animations.size()) {
                throw new IllegalArgumentException(
                        "LabPBR sprite animation index exceeds the captured samples");
            }
        }
    }

    public record Snapshot(
            int width,
            int height,
            int mipLevels,
            LabPbrMaterialSet materials,
            List<Sprite> sprites) {
        public Snapshot {
            if (width <= 0 || height <= 0 || mipLevels <= 0) {
                throw new IllegalArgumentException("LabPBR atlas extent and mip count must be positive");
            }
            Objects.requireNonNull(materials, "materials");
            sprites = List.copyOf(sprites);
        }

        public Snapshot withoutBaseColorSources() {
            boolean present = false;
            for (Sprite sprite : this.sprites) {
                present |= sprite.baseColor() != null;
            }
            if (!present) {
                return this;
            }
            java.util.ArrayList<Sprite> stripped = new java.util.ArrayList<>(this.sprites.size());
            for (Sprite sprite : this.sprites) {
                stripped.add(new Sprite(
                        sprite.textureId(),
                        sprite.x(),
                        sprite.y(),
                        sprite.contentWidth(),
                        sprite.contentHeight(),
                        sprite.padding(),
                        null,
                        sprite.normal(),
                        sprite.specular(),
                        sprite.animationIndex()));
            }
            return new Snapshot(
                    this.width,
                    this.height,
                    this.mipLevels,
                    this.materials,
                    stripped);
        }
    }

    public record Sprite(
            int textureId,
            int x,
            int y,
            int contentWidth,
            int contentHeight,
            int padding,
            ColorSource baseColor,
            MaterialSource normal,
            MaterialSource specular,
            int animationIndex) {
        public Sprite {
            if (textureId <= 0 || textureId > (1 << 24) - 1
                    || x < 0 || y < 0 || contentWidth <= 0 || contentHeight <= 0 || padding < 0) {
                throw new IllegalArgumentException("Invalid LabPBR sprite placement");
            }
            if (animationIndex < -1) {
                throw new IllegalArgumentException("Invalid LabPBR animation index");
            }
            if (baseColor != null
                    && (baseColor.frameWidth() != contentWidth
                            || baseColor.frameHeight() != contentHeight)) {
                throw new IllegalArgumentException(
                        "Base-color frame extent does not match its sprite");
            }
        }

        public int mipX(int mip) {
            return this.x >> mip;
        }

        public int mipY(int mip) {
            return this.y >> mip;
        }

        public int mipWidth(int mip) {
            return Math.max(1, (this.contentWidth + 2 * this.padding) >> mip);
        }

        public int mipHeight(int mip) {
            return Math.max(1, (this.contentHeight + 2 * this.padding) >> mip);
        }

        public boolean animated() {
            return this.animationIndex >= 0;
        }
    }

    public record AnimationSample(
            int currentFrame,
            int nextFrame,
            int progressThousandths) {
        public static final AnimationSample ZERO = new AnimationSample(0, 0, 0);

        public AnimationSample {
            if (currentFrame < 0 || nextFrame < 0
                    || progressThousandths < 0 || progressThousandths > 999) {
                throw new IllegalArgumentException("Invalid LabPBR animation sample");
            }
        }
    }

    public interface TextureSource {
        int[] pixels();

        int width();

        int height();

        int frameWidth();

        int frameHeight();

        int columns();

        int frameCount();
    }

    /** Exact captured ARGB8 source owned only until canonical pages and animation caches exist. */
    public static final class ColorSource implements TextureSource {
        private final int[] pixels;
        private final int width;
        private final int height;
        private final int frameWidth;
        private final int frameHeight;
        private final int columns;
        private final int frameCount;

        private ColorSource(
                int[] pixels,
                int width,
                int height,
                int frameWidth,
                int frameHeight,
                boolean owned) {
            if (width <= 0 || height <= 0 || frameWidth <= 0 || frameHeight <= 0
                    || width % frameWidth != 0 || height % frameHeight != 0
                    || pixels.length != Math.multiplyExact(width, height)) {
                throw new IllegalArgumentException("Invalid base-color source layout");
            }
            this.pixels = owned ? pixels : pixels.clone();
            this.width = width;
            this.height = height;
            this.frameWidth = frameWidth;
            this.frameHeight = frameHeight;
            this.columns = width / frameWidth;
            this.frameCount = Math.multiplyExact(this.columns, height / frameHeight);
        }

        public static ColorSource copyOf(
                int[] pixels, int width, int height, int frameWidth, int frameHeight) {
            Objects.requireNonNull(pixels, "pixels");
            return new ColorSource(
                    pixels, width, height, frameWidth, frameHeight, false);
        }

        public static ColorSource capture(
                int width,
                int height,
                int frameWidth,
                int frameHeight,
                ArgbReader reader) {
            Objects.requireNonNull(reader, "reader");
            int[] pixels = new int[Math.multiplyExact(width, height)];
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    pixels[y * width + x] = reader.argb(x, y);
                }
            }
            return new ColorSource(
                    pixels, width, height, frameWidth, frameHeight, true);
        }

        @Override
        public int[] pixels() {
            return this.pixels.clone();
        }

        @Override
        public int width() {
            return this.width;
        }

        @Override
        public int height() {
            return this.height;
        }

        @Override
        public int frameWidth() {
            return this.frameWidth;
        }

        @Override
        public int frameHeight() {
            return this.frameHeight;
        }

        @Override
        public int columns() {
            return this.columns;
        }

        @Override
        public int frameCount() {
            return this.frameCount;
        }

        public void filtered(
                AnimationSample sample,
                double baseX0,
                double baseY0,
                double baseX1,
                double baseY1,
                int baseFrameWidth,
                int baseFrameHeight,
                float[] output) {
            Objects.requireNonNull(sample, "sample");
            if (output == null || output.length < 4) {
                throw new IllegalArgumentException("Base-color filter output requires four lanes");
            }
            this.filteredFrame(
                    sample.currentFrame(),
                    baseX0,
                    baseY0,
                    baseX1,
                    baseY1,
                    baseFrameWidth,
                    baseFrameHeight,
                    output);
            int progress = this.frameCount == 1 ? 0 : sample.progressThousandths();
            if (progress <= 0 || sample.currentFrame() == sample.nextFrame()) {
                return;
            }
            float currentRed = output[0];
            float currentGreen = output[1];
            float currentBlue = output[2];
            float currentCoverage = output[3];
            this.filteredFrame(
                    sample.nextFrame(),
                    baseX0,
                    baseY0,
                    baseX1,
                    baseY1,
                    baseFrameWidth,
                    baseFrameHeight,
                    output);
            float nextWeight = progress / 1000.0F;
            float currentWeight = 1.0F - nextWeight;
            output[0] = currentRed * currentWeight + output[0] * nextWeight;
            output[1] = currentGreen * currentWeight + output[1] * nextWeight;
            output[2] = currentBlue * currentWeight + output[2] * nextWeight;
            output[3] = currentCoverage * currentWeight + output[3] * nextWeight;
        }

        private void filteredFrame(
                int requestedFrame,
                double baseX0,
                double baseY0,
                double baseX1,
                double baseY1,
                int baseFrameWidth,
                int baseFrameHeight,
                float[] output) {
            int frame = this.frameCount == 1
                    ? 0
                    : Math.max(0, Math.min(requestedFrame, this.frameCount - 1));
            int frameX = frame % this.columns * this.frameWidth;
            int frameY = frame / this.columns * this.frameHeight;
            int sourceX0 = clamp(
                    (int) Math.floor(baseX0 * this.frameWidth / baseFrameWidth),
                    0,
                    this.frameWidth - 1);
            int sourceY0 = clamp(
                    (int) Math.floor(baseY0 * this.frameHeight / baseFrameHeight),
                    0,
                    this.frameHeight - 1);
            int sourceX1 = clamp(
                    (int) Math.ceil(baseX1 * this.frameWidth / baseFrameWidth),
                    sourceX0 + 1,
                    this.frameWidth);
            int sourceY1 = clamp(
                    (int) Math.ceil(baseY1 * this.frameHeight / baseFrameHeight),
                    sourceY0 + 1,
                    this.frameHeight);
            double red = 0.0;
            double green = 0.0;
            double blue = 0.0;
            long coverage = 0L;
            int count = 0;
            for (int y = sourceY0; y < sourceY1; y++) {
                for (int x = sourceX0; x < sourceX1; x++) {
                    int argb = this.pixels[(frameY + y) * this.width + frameX + x];
                    float sourceRed = CanonicalColorEncoding.decodeSrgb8(argb >>> 16);
                    float sourceGreen = CanonicalColorEncoding.decodeSrgb8(argb >>> 8);
                    float sourceBlue = CanonicalColorEncoding.decodeSrgb8(argb);
                    red += CanonicalColorEncoding.matrixValue(
                            0, sourceRed, sourceGreen, sourceBlue);
                    green += CanonicalColorEncoding.matrixValue(
                            1, sourceRed, sourceGreen, sourceBlue);
                    blue += CanonicalColorEncoding.matrixValue(
                            2, sourceRed, sourceGreen, sourceBlue);
                    coverage += argb >>> 24;
                    count++;
                }
            }
            output[0] = (float) (red / count);
            output[1] = (float) (green / count);
            output[2] = (float) (blue / count);
            output[3] = (float) coverage / count;
        }

        private static int clamp(int value, int minimum, int maximum) {
            return Math.max(minimum, Math.min(maximum, value));
        }

        @FunctionalInterface
        public interface ArgbReader {
            int argb(int x, int y);
        }
    }

    public record MaterialSource(
            int[] pixels,
            int width,
            int height,
            int frameWidth,
            int frameHeight,
            int columns,
            int frameCount) implements TextureSource {
        private static final double[] MACRO_NORMAL_LENGTH_BY_ROUGHNESS_BYTE =
                createMacroNormalLengthTable();

        public MaterialSource {
            Objects.requireNonNull(pixels, "pixels");
            if (width <= 0 || height <= 0 || frameWidth <= 0 || frameHeight <= 0
                    || columns <= 0 || frameCount <= 0
                    || pixels.length != Math.multiplyExact(width, height)) {
                throw new IllegalArgumentException("Invalid LabPBR material source layout");
            }
            pixels = pixels.clone();
        }

        @Override
        public int[] pixels() {
            return this.pixels.clone();
        }

        public static MaterialSource create(
                int[] pixels,
                int width,
                int height,
                int baseFrameWidth,
                int baseFrameHeight,
                int baseImageWidth,
                int baseImageHeight) {
            int baseColumns = Math.max(1, baseImageWidth / baseFrameWidth);
            int baseRows = Math.max(1, baseImageHeight / baseFrameHeight);
            int frameWidth;
            int frameHeight;
            int columns;
            int frameCount;
            if (width == baseFrameWidth && height == baseFrameHeight) {
                frameWidth = width;
                frameHeight = height;
                columns = 1;
                frameCount = 1;
            } else if (width % baseColumns == 0 && height % baseRows == 0) {
                frameWidth = width / baseColumns;
                frameHeight = height / baseRows;
                columns = baseColumns;
                frameCount = baseColumns * baseRows;
            } else {
                frameWidth = width;
                frameHeight = height;
                columns = 1;
                frameCount = 1;
            }
            return new MaterialSource(
                    pixels, width, height, frameWidth, frameHeight, columns, frameCount);
        }

        public int filtered(
                AnimationSample sample,
                double baseX0,
                double baseY0,
                double baseX1,
                double baseY1,
                int baseFrameWidth,
                int baseFrameHeight) {
            return this.filtered(
                    sample,
                    baseX0,
                    baseY0,
                    baseX1,
                    baseY1,
                    baseFrameWidth,
                    baseFrameHeight,
                    false);
        }

        public int filtered(
                AnimationSample sample,
                double baseX0,
                double baseY0,
                double baseX1,
                double baseY1,
                int baseFrameWidth,
                int baseFrameHeight,
                boolean specular) {
            int current = this.filteredFrame(
                    sample.currentFrame,
                    baseX0,
                    baseY0,
                    baseX1,
                    baseY1,
                    baseFrameWidth,
                    baseFrameHeight,
                    specular);
            int progress = this.frameCount == 1 ? 0 : sample.progressThousandths;
            if (progress <= 0 || sample.currentFrame == sample.nextFrame) {
                return current;
            }
            int next = this.filteredFrame(
                    sample.nextFrame,
                    baseX0,
                    baseY0,
                    baseX1,
                    baseY1,
                    baseFrameWidth,
                    baseFrameHeight,
                    specular);
            return blendFiltered(current, next, progress, specular);
        }

        private int filteredFrame(
                int requestedFrame,
                double baseX0,
                double baseY0,
                double baseX1,
                double baseY1,
                int baseFrameWidth,
                int baseFrameHeight,
                boolean specular) {
            int frame = this.frameCount == 1
                    ? 0
                    : Math.max(0, Math.min(requestedFrame, this.frameCount - 1));
            int frameX = frame % this.columns * this.frameWidth;
            int frameY = frame / this.columns * this.frameHeight;
            int sourceX0 = clamp(
                    (int) Math.floor(baseX0 * this.frameWidth / baseFrameWidth),
                    0,
                    this.frameWidth - 1);
            int sourceY0 = clamp(
                    (int) Math.floor(baseY0 * this.frameHeight / baseFrameHeight),
                    0,
                    this.frameHeight - 1);
            int sourceX1 = clamp(
                    (int) Math.ceil(baseX1 * this.frameWidth / baseFrameWidth),
                    sourceX0 + 1,
                    this.frameWidth);
            int sourceY1 = clamp(
                    (int) Math.ceil(baseY1 * this.frameHeight / baseFrameHeight),
                    sourceY0 + 1,
                    this.frameHeight);
            int centerX = clamp(
                    (int) Math.floor(
                            0.5 * (baseX0 + baseX1) * this.frameWidth / baseFrameWidth),
                    0,
                    this.frameWidth - 1);
            int centerY = clamp(
                    (int) Math.floor(
                            0.5 * (baseY0 + baseY1) * this.frameHeight / baseFrameHeight),
                    0,
                    this.frameHeight - 1);
            int centerPixel = this.pixels[
                    (frameY + centerY) * this.width + frameX + centerX];
            long red = 0L;
            long blue = 0L;
            double normalX = 0.0;
            double normalY = 0.0;
            double normalZ = 0.0;
            int count = 0;
            long emission = 0L;
            int sentinelCount = 0;
            for (int y = sourceY0; y < sourceY1; y++) {
                for (int x = sourceX0; x < sourceX1; x++) {
                    int pixel = this.pixels[(frameY + y) * this.width + frameX + x];
                    int encodedAlpha = pixel >>> 24;
                    if (specular) {
                        if (encodedAlpha == 255) {
                            sentinelCount++;
                        } else {
                            emission += encodedAlpha;
                        }
                        red += pixel >>> 16 & 0xff;
                    } else {
                        double xNormal = (pixel >>> 16 & 0xff) * (2.0 / 255.0) - 1.0;
                        double yNormal = (pixel >>> 8 & 0xff) * (2.0 / 255.0) - 1.0;
                        double zNormal = Math.sqrt(Math.max(
                                1.0 - xNormal * xNormal - yNormal * yNormal,
                                0.0));
                        double inverseLength = 1.0 / Math.sqrt(Math.max(
                                xNormal * xNormal
                                        + yNormal * yNormal
                                        + zNormal * zNormal,
                                1.0E-20));
                        normalX += xNormal * inverseLength;
                        normalY += yNormal * inverseLength;
                        normalZ += zNormal * inverseLength;
                        blue += pixel & 0xff;
                    }
                    count++;
                }
            }
            if (!specular) {
                double meanX = normalX / count;
                double meanY = normalY / count;
                double meanZ = normalZ / count;
                double meanLength = Math.sqrt(
                        meanX * meanX + meanY * meanY + meanZ * meanZ);
                double inverseMeanLength = 1.0 / Math.max(meanLength, 1.0E-20);
                return encodeDistributionRoughness(meanLength) << 24
                        | encodeSnorm(meanX * inverseMeanLength) << 16
                        | encodeSnorm(meanY * inverseMeanLength) << 8
                        | (int) ((blue + count / 2L) / count);
            }
            int filteredAlpha = sentinelCount == count
                    ? 255
                    : (int) ((emission + count / 2L) / count);
            return filteredAlpha << 24
                    | (int) ((red + count / 2L) / count) << 16
                    | (centerPixel & 0x0000_ffff);
        }

        /** Blends two already filtered animation texels without repeating mip filtering. */
        public static int blendFiltered(
                int current, int next, int progress, boolean specular) {
            if (progress < 0 || progress > 999) {
                throw new IllegalArgumentException("Invalid material animation progress");
            }
            int inverse = 1000 - progress;
            int currentAlpha = current >>> 24;
            int nextAlpha = next >>> 24;
            int alpha;
            if (specular) {
                if (currentAlpha == 255 && nextAlpha == 255) {
                    alpha = 255;
                } else {
                    int currentEmission = currentAlpha == 255 ? 0 : currentAlpha;
                    int nextEmission = nextAlpha == 255 ? 0 : nextAlpha;
                    alpha = (currentEmission * inverse + nextEmission * progress + 500) / 1000;
                }
            } else {
                alpha = (currentAlpha * inverse + nextAlpha * progress + 500) / 1000;
            }
            if (!specular) {
                double currentX = (current >>> 16 & 0xff) * (2.0 / 255.0) - 1.0;
                double currentY = (current >>> 8 & 0xff) * (2.0 / 255.0) - 1.0;
                double currentZ = Math.sqrt(Math.max(
                        1.0 - currentX * currentX - currentY * currentY,
                        0.0));
                double nextX = (next >>> 16 & 0xff) * (2.0 / 255.0) - 1.0;
                double nextY = (next >>> 8 & 0xff) * (2.0 / 255.0) - 1.0;
                double nextZ = Math.sqrt(Math.max(
                        1.0 - nextX * nextX - nextY * nextY,
                        0.0));
                double x = currentX * inverse + nextX * progress;
                double y = currentY * inverse + nextY * progress;
                double z = currentZ * inverse + nextZ * progress;
                double inverseLength = 1.0 / Math.sqrt(Math.max(
                        x * x + y * y + z * z,
                        1.0E-20));
                int blue = ((current & 0xff) * inverse
                        + (next & 0xff) * progress + 500) / 1000;
                return alpha << 24
                        | encodeSnorm(x * inverseLength) << 16
                        | encodeSnorm(y * inverseLength) << 8
                        | blue;
            }
            int red = ((current >>> 16 & 0xff) * inverse
                    + (next >>> 16 & 0xff) * progress + 500) / 1000;
            // LabPBR G/B are categorical codes. Animation keeps the current frame until its
            // discrete frame transition; only continuous roughness/emission channels blend.
            return alpha << 24 | red << 16 | (current & 0x0000_ffff);
        }

        private static int clamp(int value, int minimum, int maximum) {
            return Math.max(minimum, Math.min(maximum, value));
        }

        private static int encodeSnorm(double value) {
            return encodeUnorm(value * 0.5 + 0.5);
        }

        private static int encodeUnorm(double value) {
            return clamp((int) Math.round(value * 255.0), 0, 255);
        }

        private static int encodeDistributionRoughness(double meanNormalLength) {
            if (meanNormalLength >= 1.0) {
                return 0;
            }
            if (meanNormalLength <= MACRO_NORMAL_LENGTH_BY_ROUGHNESS_BYTE[255]) {
                return 255;
            }
            int lower = 0;
            int upper = 255;
            while (upper - lower > 1) {
                int middle = (lower + upper) >>> 1;
                if (MACRO_NORMAL_LENGTH_BY_ROUGHNESS_BYTE[middle]
                        > meanNormalLength) {
                    lower = middle;
                } else {
                    upper = middle;
                }
            }
            return MACRO_NORMAL_LENGTH_BY_ROUGHNESS_BYTE[lower] - meanNormalLength
                            <= meanNormalLength
                                    - MACRO_NORMAL_LENGTH_BY_ROUGHNESS_BYTE[upper]
                    ? lower
                    : upper;
        }

        private static double[] createMacroNormalLengthTable() {
            double[] table = new double[256];
            for (int encoded = 0; encoded < table.length; encoded++) {
                double roughness = encoded / 255.0;
                table[encoded] = macroNormalLength(roughness * roughness);
            }
            return table;
        }

        private static double macroNormalLength(double alpha) {
            if (alpha <= 0.0) {
                return 1.0;
            }
            if (alpha >= 1.0) {
                return 2.0 / 3.0;
            }
            // Hill et al., "Material Advances in Call of Duty: WWII", Listing 7: closed-form
            // macrosurface-area GGX average normal length.
            double a = Math.sqrt(1.0 - alpha * alpha);
            double inverseHyperbolicTangent =
                    0.5 * Math.log((1.0 + a) / (1.0 - a));
            return (a - alpha * alpha * inverseHyperbolicTangent)
                    / (a * a * a);
        }
    }
}
