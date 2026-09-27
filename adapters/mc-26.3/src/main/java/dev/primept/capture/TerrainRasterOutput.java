package dev.primept.capture;

import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexSorting;
import net.minecraft.client.renderer.chunk.SectionCompiler;

/** Proof that this private compile has no raster consumer. Never suppresses source callbacks. */
public final class TerrainRasterOutput implements AutoCloseable {
    private static final ThreadLocal<TerrainRasterOutput> ACTIVE = new ThreadLocal<>();
    private static final boolean KNOWN = BlockEntityCandidates.known(MeshData.class) &&
                                         BlockEntityCandidates.known(SectionCompiler.class);
    final VertexSorting sorting = VertexSorting.byDistance(0, 0, 0);
    private final TerrainRasterOutput previous;
    private TerrainRasterOutput() {
        previous = ACTIVE.get();
        ACTIVE.set(this);
    }
    static TerrainRasterOutput open() {
        return new TerrainRasterOutput();
    }
    public static boolean omitSort(MeshData mesh, VertexSorting sorting) {
        var scope = ACTIVE.get();
        return KNOWN && scope != null && scope.sorting == sorting &&
                TerrainCapture.current() != null && mesh.getClass() == MeshData.class;
    }
    public void close() {
        if (previous == null)
            ACTIVE.remove();
        else
            ACTIVE.set(previous);
    }
}
