package dev.primept.capture;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
/** Only the material constructor differs between the two actual compiler oracles. */
final class SectionOracleMaterial {
    static BakedQuad.MaterialInfo create(TextureAtlasSprite sprite, int tint) {
        return new BakedQuad.MaterialInfo(
                sprite, ChunkSectionLayer.SOLID,
                net.minecraft.client.renderer.Sheets.cutoutBlockItemSheet(),
                net.minecraft.client.renderer.Sheets.cutoutBlockItemGlintSheet(),
                net.minecraft.client.renderer.Sheets.cutoutBlockItemGlintSpecialSheet(), tint, null,
                0);
    }
}
