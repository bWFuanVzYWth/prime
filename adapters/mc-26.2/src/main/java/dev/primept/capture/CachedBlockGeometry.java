package dev.primept.capture;

import net.fabricmc.fabric.api.client.renderer.v1.mesh.Mesh;
import org.joml.Matrix4fc;

/** A render state borrows immutable resource geometry; pose, tint and visibility remain per invocation. */
public interface CachedBlockGeometry {
    void primept$geometry(Mesh mesh, Matrix4fc transformation, boolean translucent);
}
