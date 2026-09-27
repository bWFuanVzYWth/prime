package dev.primept.capture;

import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexSorting;
import net.minecraft.client.renderer.chunk.SectionCompiler;

/** Proof that this private compile has no raster consumer. Never suppresses source callbacks. */
public final class TerrainRasterOutput implements AutoCloseable {
    private static final ThreadLocal<TerrainRasterOutput> ACTIVE = new ThreadLocal<>();
    private static final boolean KNOWN = BlockEntityCandidates.known(MeshData.class) &&
                                         BlockEntityCandidates.known(SectionCompiler.class);
    private static final boolean KNOWN_VISIBILITY =
            BlockEntityCandidates.known(SectionCompiler.class) &&
            BlockEntityCandidates.known(SectionCompiler.Results.class) &&
            BlockEntityCandidates.known(net.minecraft.client.renderer.chunk.VisGraph.class);
    final VertexSorting sorting = VertexSorting.byDistance(0, 0, 0);
    private final TerrainRasterOutput previous;
    private final SourceQuads source;
    private boolean sourceBorrowed;
    private TerrainRasterOutput(SourceQuads source) {
        this.source = source;
        previous = ACTIVE.get();
        ACTIVE.set(this);
    }
    static TerrainRasterOutput open() {
        return open(null);
    }
    static TerrainRasterOutput open(SourceQuads source) {
        return new TerrainRasterOutput(source);
    }
    static SourceQuads borrowSource() {
        var scope = ACTIVE.get();
        // A nested foreign compile cannot overwrite the outer compile's unsealed vertices.
        if (scope == null || scope.source == null || scope.sourceBorrowed)
            return new SourceQuads();
        scope.sourceBorrowed = true;
        scope.source.clear();
        return scope.source;
    }
    public static boolean omitSort(MeshData mesh, VertexSorting sorting) {
        var scope = ACTIVE.get();
        return KNOWN && scope != null && scope.sorting == sorting &&
                TerrainCapture.current() != null && mesh.getClass() == MeshData.class;
    }
    public static boolean omitVisibility(VertexSorting sorting) {
        var scope = ACTIVE.get();
        return KNOWN_VISIBILITY && scope != null && scope.sorting == sorting &&
                TerrainCapture.current() != null;
    }
    public void close() {
        if (previous == null)
            ACTIVE.remove();
        else
            ACTIVE.set(previous);
    }
}
