package dev.primept.capture;

/** Terrain-specific CPU allocations; the staged world/hand feature buffer remains shared. */
public interface ExclusiveRenderBuffers {
    void primept$retireTerrainBuffers();
    void primept$restoreTerrainBuffers();
}
