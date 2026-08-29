package dev.prime.render.terrain;

import java.util.Objects;

/** Immutable, replayable inputs for one captured-cluster translation. */
public record ClusterTranslationInput(
        CapturedCluster captured,
        LabPbrMaterialSet materials,
        ClusterTranslationSettings settings) {
    public ClusterTranslationInput {
        Objects.requireNonNull(captured, "captured");
        Objects.requireNonNull(materials, "materials");
        Objects.requireNonNull(settings, "settings");
    }
}
