// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

package dev.prime.render.scene.vanilla;

import java.util.Objects;
import net.minecraft.client.renderer.chunk.RenderSectionRegion;

/**
 * Captured block-state neighborhood and coordinate identity of one vanilla Section.
 *
 * <p>{@link RenderSectionRegion} owns copied {@code SectionCopy} state and block-entity maps.
 * Biome tint and light-engine queries still delegate to its captured level as part of the
 * Minecraft adapter boundary; Prime does not duplicate those services for formal purity.
 */
public record VanillaSectionSnapshot(
        int sectionX,
        int sectionY,
        int sectionZ,
        RenderSectionRegion region) {
    public VanillaSectionSnapshot {
        Objects.requireNonNull(region, "region");
    }
}
