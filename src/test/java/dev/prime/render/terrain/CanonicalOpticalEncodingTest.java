// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

package dev.prime.render.terrain;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

final class CanonicalOpticalEncodingTest {
    @Test
    void everySourceCodePreservesItsPhysicalFieldsAndDistinctDielectricIdentity() {
        for (int green = 0; green < 256; green++) {
            for (int blue = 0; blue < 256; blue++) {
                int roughness = (green + blue) & 255;
                int emission = (green * 7 + blue) & 255;
                int encoded = CanonicalOpticalEncoding.fromLabPbrArgb(
                        emission << 24 | roughness << 16 | green << 8 | blue);
                int fresnel = encoded >>> 8 & 255;
                int scattering = encoded & 255;
                int expectedFresnel = green < 230 ? green + 1
                        : green <= 237 ? green + 1 : green == 255 ? 239 : 0;
                assertEquals(expectedFresnel, fresnel);
                assertEquals(blue >= 66 ? blue - 65 : 0, scattering > 64 ? scattering - 64 : 0);
                assertEquals(blue <= 64 ? blue : 0, scattering <= 64 ? scattering : 0);
                assertEquals(roughness, encoded >>> 16 & 255);
                assertEquals(emission, encoded >>> 24);
            }
        }
    }

    @Test
    void voxelBakingUsesTheCanonicalEncodingForEveryFrameAndTexel() {
        int[] pixels = new int[512];
        for (int index = 0; index < pixels.length; index++) {
            pixels[index] = index << 24 | (511 - index) << 16
                    | (index & 255) << 8 | (index * 43 & 255);
        }
        var source = LabPbrAtlasFrame.MaterialSource.create(pixels, 256, 2, 256, 1, 256, 2);
        var material = new LabPbrMaterialMap(null, source);
        for (int frame = 0; frame < 2; frame++) {
            for (int x = 0; x < 256; x++) {
                assertEquals(
                        LabPbrMaterialMap.packArgb(CanonicalOpticalEncoding.fromLabPbrArgb(
                                pixels[frame * 256 + x])),
                        material.sampleSpecular(frame, (x + 0.5F) / 256, 0.5F));
            }
        }
        assertEquals(0xff00_0500, new LabPbrMaterialMap(null, null).sampleSpecular(0, 0, 0));
    }
}
