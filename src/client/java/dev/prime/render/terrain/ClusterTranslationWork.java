package dev.prime.render.terrain;

import java.util.Objects;

/** Invocation-local bounded cancellation budget shared by translation stages. */
final class ClusterTranslationWork {
    static final int CHECK_INTERVAL = 1_024;

    private final ClusterTranslationControl control;
    private int remaining = CHECK_INTERVAL;

    ClusterTranslationWork(ClusterTranslationControl control) {
        this.control = Objects.requireNonNull(control, "control");
    }

    void checkpoint() {
        this.remaining = CHECK_INTERVAL;
        this.control.checkpoint();
    }

    void step() {
        if (--this.remaining == 0) {
            this.checkpoint();
        }
    }
}
