// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

package dev.prime.render.terrain;

/** Immutable CPU material texels used when voxel primitives bake LabPBR inputs. */
public record LabPbrMaterialMap(
        LabPbrAtlasFrame.MaterialSource normal,
        LabPbrAtlasFrame.MaterialSource specular) {
    static final int DEFAULT_NORMAL = 0xffff_8080;
    static final int DEFAULT_SPECULAR = 0xff00_0400;

    int sampleNormal(int requestedFrame, float localU, float localV) {
        return this.normal == null
                ? DEFAULT_NORMAL
                : packArgb(this.normal.argb(
                        this.normal.index(requestedFrame, localU, localV)));
    }

    int sampleSpecular(int requestedFrame, float localU, float localV) {
        return this.specular == null
                ? DEFAULT_SPECULAR
                : packArgb(this.specular.argb(
                        this.specular.index(requestedFrame, localU, localV)));
    }

    static int packArgb(int argb) {
        return argb >>> 16 & 0xff
                | (argb >>> 8 & 0xff) << 8
                | (argb & 0xff) << 16
                | (argb >>> 24) << 24;
    }

}
