package dev.prime.client;

import dev.prime.PrimeClient;
import dev.prime.infrastructure.ResourceCleanup;
import dev.prime.streamline.StreamlineFrameGeneration;
import dev.prime.streamline.StreamlineReflex;

/** One owner for NVIDIA feature, NGX context and Streamline base-runtime shutdown ordering. */
public final class PrimeShutdown {
    private PrimeShutdown() {
    }

    public static RuntimeException run() {
        return run(
                StreamlineFrameGeneration::shutdown,
                StreamlineReflex::shutdown,
                PrimeRuntime.instance()::shutdown,
                PrimeClient::shutdownStreamline);
    }

    static RuntimeException run(
            Runnable frameGeneration,
            Runnable reflex,
            Runnable renderer,
            Runnable streamline) {
        RuntimeException failure = null;
        failure = ResourceCleanup.run(frameGeneration, failure);
        failure = ResourceCleanup.run(reflex, failure);
        // RR and Streamline use the same NVIDIA NGX process state. Every RR feature and context
        // must retire while that state is still initialized; slShutdown is the final owner.
        failure = ResourceCleanup.run(renderer, failure);
        failure = ResourceCleanup.run(streamline, failure);
        return failure;
    }
}
