// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

package dev.prime.render.vulkan;

import static org.junit.jupiter.api.Assertions.*;
import dev.prime.render.vulkan.terrain.TerrainScene.ShadowSurface;
import dev.prime.render.vulkan.terrain.TerrainScene.ShadowSurfaces;
import java.util.List;
import org.junit.jupiter.api.Test;

final class ShadowInteractionUpdatesTest {
    @Test void compilationTracksAcceptedWritesAnimationAndReusedKeys() {
        var updates = new ShadowInteractionUpdates();
        var a = new ShadowSurface(4, 12, 1);
        var b = new ShadowSurface(19, 23, 2);
        var initial = List.of(a, b);
        var snapshot = new ShadowSurfaces(initial);
        updates.ensure(snapshot);
        assertEquals(initial, updates.prepare(new int[0]));
        // An abandoned frame leaves the same work pending, including an animation update.
        assertEquals(initial, updates.prepare(new int[] {12}));
        updates.submitted(initial);
        for (int frame = 0; frame < 64; frame++) {
            var scene = new dev.prime.render.vulkan.terrain.TerrainScene.SurfaceBinding(frame + 1L, 1024L, snapshot);
            assertSame(snapshot, scene.shadows());
            updates.ensure(scene.shadows());
            assertTrue(updates.prepare(new int[0]).isEmpty());
        }
        assertTrue(updates.prepare(new int[0]).isEmpty());
        assertTrue(updates.prepare(new int[] {77}).isEmpty());
        assertEquals(List.of(b), updates.prepare(new int[] {23}));
        updates.submitted(List.of(b));
        var c = new ShadowSurface(4, 12, 3);
        updates.ensure(new ShadowSurfaces(List.of(c, b)));
        updates.submitted(List.of(a)); // An old completion cannot bless the replacement slot.
        assertEquals(List.of(c), updates.prepare(new int[0]));
        updates.submitted(List.of(c));
        updates.invalidate(); // Resource generation, sampler or sidecar replacement.
        assertEquals(List.of(c, b), updates.prepare(new int[0]));
        updates.ensure(new ShadowSurfaces(List.of(c)));
        assertEquals(List.of(c), updates.prepare(new int[] {23}));
        updates.submitted(List.of(c));
        updates.ensure(ShadowSurfaces.EMPTY);
        assertTrue(updates.prepare(new int[] {12, 23}).isEmpty());
    }
}
