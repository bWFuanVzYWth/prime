package dev.primept.capture;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.renderer.texture.SpriteLoader;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.sprite.MaterialBaker;
import net.minecraft.resources.Identifier;

/** Actual 26.3 atlas-value material binding, without atlas upload. */
final class CrossUvMaterialBaker {
    static MaterialBaker create(TextureAtlasSprite sprite, Identifier texture) {
        var atlas = new SpriteLoader.Preparations(16, 16, 0, sprite, Map.of(texture, sprite),
                                                  CompletableFuture.completedFuture(null));
        return new MaterialBaker(atlas, atlas);
    }
}
