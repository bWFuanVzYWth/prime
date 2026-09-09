// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

package dev.prime.mixin;

import dev.prime.client.PrimeRuntime;
import net.minecraft.client.renderer.texture.TextureAtlas;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Freezes animated block textures while a screenshot is accumulating. */
@Mixin(TextureAtlas.class)
@SuppressWarnings("deprecation")
public abstract class TextureAtlasMixin {
    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void prime$freezeBlockAnimations(CallbackInfo callbackInfo) {
        TextureAtlas atlas = (TextureAtlas) (Object) this;
        if (PrimeRuntime.instance().screenshotActive()
                && TextureAtlas.LOCATION_BLOCKS.equals(atlas.location())) {
            callbackInfo.cancel();
        }
    }
}
