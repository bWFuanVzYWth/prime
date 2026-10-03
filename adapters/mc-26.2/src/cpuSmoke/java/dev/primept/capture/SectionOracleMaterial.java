package dev.primept.capture;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
/** Only the material constructor differs between the two actual compiler oracles. */
final class SectionOracleMaterial {
    static BakedQuad.MaterialInfo create(TextureAtlasSprite sprite, int tint) {
        return create(sprite, tint, ChunkSectionLayer.SOLID);
    }
    static BakedQuad.MaterialInfo create(TextureAtlasSprite sprite, int tint,
                                         ChunkSectionLayer layer) {
        return new BakedQuad.MaterialInfo(
                sprite, layer, net.minecraft.client.renderer.Sheets.cutoutBlockItemSheet(), tint,
                false, 0);
    }
}
