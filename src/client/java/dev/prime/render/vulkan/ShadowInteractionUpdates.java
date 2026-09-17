// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

package dev.prime.render.vulkan;

import dev.prime.render.vulkan.terrain.TerrainScene.ShadowSurface;
import dev.prime.render.vulkan.terrain.TerrainScene.ShadowSurfaces;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;

/** Render-thread state inside TraceBackend; accepted frames alone clear pending compilation. */
final class ShadowInteractionUpdates {
    private ShadowSurfaces snapshot = ShadowSurfaces.EMPTY;
    private HashMap<Integer, ShadowSurface> surfaces = new HashMap<>();
    private final HashMap<Integer, List<ShadowSurface>> textures = new HashMap<>();
    // Walk only pending keys, even after a large initial compile grew the backing table.
    private final LinkedHashSet<Integer> dirty = new LinkedHashSet<>();

    void ensure(ShadowSurfaces next) {
        if (this.snapshot == next) return;
        HashMap<Integer, ShadowSurface> replacement = new HashMap<>();
        this.textures.clear();
        for (ShadowSurface surface : next.entries()) {
            replacement.put(surface.key(), surface);
            this.textures.computeIfAbsent(surface.textureId(), ignored -> new ArrayList<>()).add(surface);
            if (!surface.equals(this.surfaces.get(surface.key()))) this.dirty.add(surface.key());
        }
        this.dirty.retainAll(replacement.keySet());
        this.surfaces = replacement;
        this.snapshot = next;
    }

    void invalidate() { this.dirty.addAll(this.surfaces.keySet()); }

    List<ShadowSurface> prepare(int[] changedTextures) {
        for (int texture : changedTextures) {
            for (ShadowSurface surface : this.textures.getOrDefault(texture, List.of())) {
                this.dirty.add(surface.key());
            }
        }
        if (this.dirty.isEmpty()) return List.of();
        return this.dirty.stream().sorted().map(this.surfaces::get).toList();
    }

    void submitted(List<ShadowSurface> compiled) {
        for (ShadowSurface surface : compiled) {
            if (surface.equals(this.surfaces.get(surface.key()))) this.dirty.remove(surface.key());
        }
    }
}
