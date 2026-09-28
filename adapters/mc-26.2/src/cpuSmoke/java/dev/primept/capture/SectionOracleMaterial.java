package dev.primept.capture;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
/** Only the material constructor differs between the two actual compiler oracles. */
final class SectionOracleMaterial {
    static BakedQuad.MaterialInfo create(TextureAtlasSprite sprite) {
        return new BakedQuad.MaterialInfo(
                sprite, ChunkSectionLayer.SOLID,
                net.minecraft.client.renderer.Sheets.cutoutBlockItemSheet(), -1, false, 0);
    }
}
