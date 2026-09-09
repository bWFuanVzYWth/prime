// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

package dev.prime.mixin.accessor;

import java.util.List;
import java.util.Map;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Minecraft adapter exposing the immutable stitch layout and animation states. */
@Mixin(TextureAtlas.class)
public interface TextureAtlasAccessor {
    @Accessor("sprites")
    List<TextureAtlasSprite> prime$sprites();

    @Accessor("texturesByName")
    Map<Identifier, TextureAtlasSprite> prime$texturesByName();

    @Accessor("animatedTexturesStates")
    List<SpriteContents.AnimationState> prime$animatedTextureStates();

    @Accessor("width")
    int prime$width();

    @Accessor("height")
    int prime$height();

    @Accessor("maxMipLevel")
    int prime$maxMipLevel();
}
