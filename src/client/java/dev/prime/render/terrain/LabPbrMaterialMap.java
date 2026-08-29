package dev.prime.render.terrain;

import java.util.Arrays;

/** Immutable CPU material texels used when voxel primitives bake LabPBR inputs. */
public record LabPbrMaterialMap(Pixels normal, Pixels specular) {
    static final int DEFAULT_NORMAL = 0xffff_8080;
    static final int DEFAULT_SPECULAR = 0xff00_0400;

    int sampleNormal(int requestedFrame, float localU, float localV) {
        return this.normal == null
                ? DEFAULT_NORMAL
                : packArgb(this.normal.sample(requestedFrame, localU, localV));
    }

    int sampleSpecular(int requestedFrame, float localU, float localV) {
        return this.specular == null
                ? DEFAULT_SPECULAR
                : packArgb(this.specular.sample(requestedFrame, localU, localV));
    }

    static int packArgb(int argb) {
        return argb >>> 16 & 0xff
                | (argb >>> 8 & 0xff) << 8
                | (argb & 0xff) << 16
                | (argb >>> 24) << 24;
    }

    public static final class Pixels {
        private final int[] argb;
        private final SpriteSheetLayout layout;

        public Pixels(
                int[] argb,
                int imageWidth,
                int frameWidth,
                int frameHeight,
                int columns,
                int frameCount) {
            this.argb = Arrays.copyOf(argb, argb.length);
            this.layout = SpriteSheetLayout.forPixels(
                    argb.length,
                    imageWidth,
                    frameWidth,
                    frameHeight,
                    columns,
                    frameCount);
        }

        int sample(int requestedFrame, float localU, float localV) {
            return this.argb[this.layout.index(requestedFrame, localU, localV)];
        }

        int[] replayArgb() {
            return Arrays.copyOf(this.argb, this.argb.length);
        }

        SpriteSheetLayout replayLayout() {
            return this.layout;
        }

        @Override
        public boolean equals(Object other) {
            return this == other
                    || other instanceof Pixels pixels
                            && this.layout.equals(pixels.layout)
                            && Arrays.equals(this.argb, pixels.argb);
        }

        @Override
        public int hashCode() {
            int result = Arrays.hashCode(this.argb);
            return 31 * result + this.layout.hashCode();
        }
    }

}
