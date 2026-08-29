package dev.prime.render.vulkan;

import dev.prime.infrastructure.ResourceCleanup;
import java.util.ArrayList;
import java.util.Comparator;

/** Orders resource/history commit or rollback without crossing submission ownership. */
final class FrameCompletion {
    private final ArrayList<OrderedAction> commits = new ArrayList<>();
    private final ArrayList<OrderedFailureAction> abandons = new ArrayList<>();
    private State state = State.OPEN;

    void onCommit(int order, Runnable action) {
        requireOpen("register a frame commit action");
        this.commits.add(new OrderedAction(order, java.util.Objects.requireNonNull(action, "action")));
    }

    void onAbandon(int order, FailureAction action) {
        requireOpen("register a frame abandon action");
        this.abandons.add(new OrderedFailureAction(
                order, java.util.Objects.requireNonNull(action, "action")));
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
        this.commits.sort(Comparator.comparingInt(OrderedAction::order));
        RuntimeException failure = null;
        for (OrderedAction action : this.commits) {
            failure = ResourceCleanup.run(action.action(), failure);
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
        this.abandons.sort(Comparator.comparingInt(OrderedFailureAction::order));
        RuntimeException result = failure;
        for (OrderedFailureAction action : this.abandons) {
            result = action.action().run(result);
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

    private record OrderedAction(int order, Runnable action) {
    }

    private record OrderedFailureAction(int order, FailureAction action) {
    }
}
