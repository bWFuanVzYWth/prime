// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

package dev.prime.render.runtime;

import dev.prime.render.terrain.ViewDistanceLimits;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;

/** Client-thread-owned handoff between Minecraft's terrain shell and Prime terrain streaming. */
public final class TerrainOwnership {
    private ClientLevel world;
    private boolean prime;

    public boolean primeOwned() {
        return this.prime;
    }

    public int vanillaDistance(int configuredDistance) {
        return ViewDistanceLimits.vanillaTerrainDistance(configuredDistance, this.prime);
    }

    public void acquire(Minecraft minecraft) {
        ClientLevel currentWorld = minecraft.level;
        boolean ownershipChanged = changeOwnership(true);
        boolean worldChanged = this.world != currentWorld;
        this.world = currentWorld;
        if (currentWorld != null && (ownershipChanged || worldChanged)) {
            rebuildMinecraftShell(minecraft);
        }
        if (ownershipChanged) {
            minecraft.options.broadcastOptions();
        }
    }

    public void restore(Minecraft minecraft, boolean clampDistance) {
        boolean ownershipChanged = changeOwnership(false);
        this.world = minecraft.level;
        int configuredDistance = minecraft.options.renderDistance().get();
        if (clampDistance
                && configuredDistance > ViewDistanceLimits.VANILLA_MAXIMUM_RENDER_DISTANCE) {
            minecraft.options.renderDistance().set(
                    ViewDistanceLimits.VANILLA_MAXIMUM_RENDER_DISTANCE);
        }
        if (ownershipChanged && minecraft.level != null) {
            rebuildMinecraftShell(minecraft);
        }
        if (ownershipChanged) {
            minecraft.options.broadcastOptions();
        }
    }

    boolean changeOwnership(boolean primeOwned) {
        boolean changed = this.prime != primeOwned;
        this.prime = primeOwned;
        return changed;
    }

    private static void rebuildMinecraftShell(Minecraft minecraft) {
        if (minecraft.level != null) {
            // LevelExtractor owns the dirty-state tracker paired with Minecraft RenderSections.
            minecraft.levelExtractor.allChanged();
        }
    }
}
