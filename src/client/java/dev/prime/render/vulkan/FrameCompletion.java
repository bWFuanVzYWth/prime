package dev.prime.render.vulkan;

import dev.prime.infrastructure.ResourceCleanup;

/** Orders resource/history commit or rollback without crossing submission ownership. */
final class FrameCompletion {
    private static final int ACTION_SLOTS = 6;

    private final Runnable[] commits = new Runnable[ACTION_SLOTS];
    private final FailureAction[] abandons = new FailureAction[ACTION_SLOTS];
    private State state = State.OPEN;

    void onCommit(int order, Runnable action) {
        requireOpen("register a frame commit action");
        this.commits[order] = java.util.Objects.requireNonNull(action, "action");
    }

    void onAbandon(int order, FailureAction action) {
        requireOpen("register a frame abandon action");
        this.abandons[order] = java.util.Objects.requireNonNull(action, "action");
    }

    void acceptedBySubmission() {
        requireOpen("accept frame ownership");
        this.state = State.HOST_ACCEPTED;
    }

    void commit() {
        if (this.state != State.HOST_ACCEPTED) {
            throw new IllegalStateException(
                    "Frame completion requires accepted host ownership");
        }
        this.state = State.COMMITTED;
        RuntimeException failure = null;
        for (Runnable action : this.commits) {
            if (action != null) {
                failure = ResourceCleanup.run(action, failure);
            }
        }
        ResourceCleanup.throwIfFailed(failure);
    }

    RuntimeException abandon(RuntimeException failure) {
        java.util.Objects.requireNonNull(failure, "failure");
        if (this.state == State.HOST_ACCEPTED || this.state == State.COMMITTED) {
            return failure;
        }
        if (this.state == State.ABANDONED) {
            throw new IllegalStateException("Frame completion was already abandoned");
        }
        this.state = State.ABANDONED;
        RuntimeException result = failure;
        for (FailureAction action : this.abandons) {
            if (action != null) {
                result = action.run(result);
            }
        }
        return result;
    }

    private void requireOpen(String operation) {
        if (this.state != State.OPEN) {
            throw new IllegalStateException(
                    "Cannot " + operation + " after frame state "
                            + this.state.name().toLowerCase(java.util.Locale.ROOT));
        }
    }

    @FunctionalInterface
    interface FailureAction {
        RuntimeException run(RuntimeException failure);
    }

    private enum State {
        OPEN,
        HOST_ACCEPTED,
        COMMITTED,
        ABANDONED
    }
}
