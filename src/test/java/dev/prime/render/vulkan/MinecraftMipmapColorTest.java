// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

package dev.prime.render.vulkan;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.platform.Transparency;
import net.minecraft.client.renderer.texture.MipmapGenerator;
import net.minecraft.client.renderer.texture.MipmapStrategy;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ARGB;
import org.junit.jupiter.api.Test;

final class MinecraftMipmapColorTest {
    @Test
    void meanMipAveragesRgbInLinearLightAndAlphaAsCoverage() {
        NativeImage source = new NativeImage(2, 2, true);
        NativeImage[] levels = null;
        try {
            source.setPixel(0, 0, 0xff00_0000);
            source.setPixel(1, 0, 0xffff_ffff);
            source.setPixel(0, 1, 0xff00_0000);
            source.setPixel(1, 1, 0xffff_ffff);

            levels = MipmapGenerator.generateMipLevels(
                    Identifier.fromNamespaceAndPath("prime", "linear_mip_gate"),
                    new NativeImage[] {source},
                    1,
                    MipmapStrategy.MEAN,
                    0.0F,
                    Transparency.NONE);

            int mip = levels[1].getPixel(0, 0);
            assertEquals(255, ARGB.alpha(mip));
            assertEquals(188, ARGB.red(mip), 1);
            assertEquals(188, ARGB.green(mip), 1);
            assertEquals(188, ARGB.blue(mip), 1);
        } finally {
            if (levels != null) {
                for (int index = 1; index < levels.length; index++) {
                    levels[index].close();
                }
            }
            source.close();
        }
    }
}
