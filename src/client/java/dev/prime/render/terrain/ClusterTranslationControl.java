package dev.prime.render.terrain;

/** Non-blocking control callback polled at bounded points during one translation. */
@FunctionalInterface
public interface ClusterTranslationControl {
    ClusterTranslationControl UNINTERRUPTIBLE = () -> {
    };

    /** May abort the translation by throwing the caller's cancellation exception. */
    void checkpoint();
}
