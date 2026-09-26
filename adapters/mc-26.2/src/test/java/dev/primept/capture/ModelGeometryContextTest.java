package dev.primept.capture;

import java.util.EnumSet;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.core.Direction;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ModelGeometryContextTest {
    static ModelPart.Cube cube() {
        return new ModelPart.Cube(0, 0, 0, 0, 0, 16, 16, 16, 0, 0, 0, false, 64, 64,
                                  EnumSet.allOf(Direction.class));
    }
    @Test
    void tenThousandUnchangedLeavesReadOnlyImmutableReferences() {
        var context = new ModelGeometryContext();
        var cube = cube();
        var initial = context.observe(cube);
        assertEquals(24, initial.vertexCount());
        for (int i = 0; i < 10_000; ++i)
            assertSame(initial, context.observe(cube));
        assertEquals(24, context.geometryReads());
        assertEquals(300_000, context.referenceChecks());
    }
    @Test
    void publicArrayReplacementWithinOneFrameProducesIndependentGeometry() {
        var context = new ModelGeometryContext();
        var cube = cube();
        var initial = context.observe(cube);
        var vertices = cube.polygons[0].vertices();
        var old = vertices[0];
        vertices[0] = new ModelPart.Vertex(old.x() + 2, old.y(), old.z(), old.u(), old.v());
        var changed = context.observe(cube);
        assertNotSame(initial, changed);
        assertEquals(initial.bytes().getFloat(0) + .125f, changed.bytes().getFloat(0));
        assertEquals(48, context.geometryReads());
        // A polygon replacement with another array is visible even when its numeric values agree.
        cube.polygons[0] = new ModelPart.Polygon(vertices.clone(), cube.polygons[0].normal());
        assertNotSame(changed, context.observe(cube));
        assertEquals(72, context.geometryReads());
    }
    @Test
    void localPrototypeKeepsWhiteRgbaAndSourceUvWithoutModelPose() {
        var cube = cube();
        var geometry = new ModelGeometryContext().observe(cube);
        var bytes = geometry.bytes();
        assertEquals(-1, bytes.getInt(12));
        assertEquals(cube.polygons[0].vertices()[0].u(), bytes.getFloat(16));
        assertEquals(cube.polygons[0].vertices()[0].worldX(), bytes.getFloat(0));
    }
    @Test
    void collectedSourceOwnerWaitsForItsLastVisibleInstanceBeforeNativeRetirement()
            throws Exception {
        try (var owner = new InstanceCapture(1)) {
            var context = new ModelGeometryContext(owner);
            var cube = cube();
            owner.beginFrame();
            var geometry = context.observe(cube);
            geometry.prototype =
                    owner.prototype(4, geometry.vertexCount(), 24, 0, 12, 16, geometry.bytes());
            var instance = owner.instance();
            owner.observe(instance, geometry.prototype, 0, 0, 0,
                          new float[] {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0}, 0, 0, -1,
                          new float[] {1, 1, 0, 0});
            owner.endFrame();
            assertNotNull(owner.sealDelta());
            owner.acknowledge();
            // Deliver the collector's notification deterministically, without depending on GC scheduling.
            var field = ModelGeometryContext.class.getDeclaredField("geometries");
            field.setAccessible(true);
            var cache = (java.util.Map<?, ?>) field.get(context);
            var reference = (java.lang.ref.Reference<?>)cache.keySet().iterator().next();
            reference.clear();
            reference.enqueue();
            context.collectGarbage();
            assertTrue(cache.isEmpty());
            assertNull(owner.sealDelta()); // Still protected by the actual visible instance.
            owner.beginFrame();
            owner.endFrame();
            assertNotNull(owner.sealDelta());
            assertEquals(1, owner.stats().prototypeRemoves());
            assertEquals(1, owner.stats().instanceRemoves());
            owner.acknowledge();
        }
    }
}
