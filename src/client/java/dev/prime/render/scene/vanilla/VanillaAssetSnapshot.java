// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

package dev.prime.render.scene.vanilla;

import java.util.Objects;
import net.fabricmc.fabric.api.client.renderer.v1.sprite.SpriteFinder;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.renderer.block.BlockStateModelSet;
import net.minecraft.client.renderer.block.FluidStateModelSet;

/**
 * Minecraft capture services fixed once before Section jobs are dispatched.
 *
 * <p>The referenced Minecraft registries are treated as immutable for one reload epoch. Reload
 * invalidation replaces this whole value before another generation can become resident.
 */
public record VanillaAssetSnapshot(
        BlockStateModelSet blockModels,
        FluidStateModelSet fluidModels,
        BlockColors blockColors,
        SpriteFinder blockSpriteFinder,
        boolean cutoutLeaves) {
    public VanillaAssetSnapshot {
        Objects.requireNonNull(blockModels, "blockModels");
        Objects.requireNonNull(fluidModels, "fluidModels");
        Objects.requireNonNull(blockColors, "blockColors");
        Objects.requireNonNull(blockSpriteFinder, "blockSpriteFinder");
    }
}
