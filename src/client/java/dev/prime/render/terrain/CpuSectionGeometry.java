// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

package dev.prime.render.terrain;

import java.util.List;

/**
 * Immutable Section-local geometry split at the last point where unit block faces are still
 * recoverable.
 */
public record CpuSectionGeometry(List<CpuSectionMesh> meshes, List<MergeFace> mergeFaces) {
    public CpuSectionGeometry {
        meshes = List.copyOf(meshes);
        mergeFaces = List.copyOf(mergeFaces);
    }
}
