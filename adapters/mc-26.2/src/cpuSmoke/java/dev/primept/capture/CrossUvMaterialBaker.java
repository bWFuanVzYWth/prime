package dev.primept.capture;

import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.sprite.Material;
import net.minecraft.client.resources.model.sprite.MaterialBaker;
import net.minecraft.resources.Identifier;

/** Actual 26.2 material binding without atlas upload or a resource manager. */
final class CrossUvMaterialBaker {
    static MaterialBaker create(TextureAtlasSprite sprite, Identifier texture) {
        return new MaterialBaker(sprite) {
            @Override
            protected Material.Baked bake(Material material) {
                if (!material.sprite().equals(texture))
                    throw new AssertionError("Unexpected source texture slot");
                return new Material.Baked(sprite, material.forceTranslucent());
            }
        };
    }
}
