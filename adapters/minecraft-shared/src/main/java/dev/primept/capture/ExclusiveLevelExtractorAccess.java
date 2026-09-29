package dev.primept.capture;

/** Terrain scheduling state is exclusive; source callbacks remain in the shared level extractor. */
public interface ExclusiveLevelExtractorAccess {
    void primept$discardTerrainTracker();
}
