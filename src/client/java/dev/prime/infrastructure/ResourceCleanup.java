// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

package dev.prime.infrastructure;

import com.mojang.renderpearl.backend.vulkan.Destroyable;

/** Failure-preserving cleanup for platform resource owners. */
public final class ResourceCleanup {
    private ResourceCleanup() {}

    public static RuntimeException destroy(
            Destroyable resource, RuntimeException failure) {
        if (resource == null) {
            return failure;
        }
        try {
            resource.destroy();
        } catch (RuntimeException exception) {
            return append(failure, exception);
        }
        return failure;
    }

    public static RuntimeException close(
            AutoCloseable resource, RuntimeException failure) {
        if (resource == null) {
            return failure;
        }
        try {
            resource.close();
        } catch (Exception exception) {
            RuntimeException cleanupFailure = exception instanceof RuntimeException runtime
                    ? runtime
                    : new IllegalStateException("Prime resource cleanup failed", exception);
            return append(failure, cleanupFailure);
        }
        return failure;
    }

    public static RuntimeException run(
            Runnable cleanup, RuntimeException failure) {
        if (cleanup == null) {
            return failure;
        }
        try {
            cleanup.run();
        } catch (RuntimeException exception) {
            return append(failure, exception);
        }
        return failure;
    }

    public static void throwIfFailed(RuntimeException failure) {
        if (failure != null) {
            throw failure;
        }
    }

    private static RuntimeException append(
            RuntimeException failure, RuntimeException cleanupFailure) {
        if (failure == null) {
            return cleanupFailure;
        }
        failure.addSuppressed(cleanupFailure);
        return failure;
    }
}
