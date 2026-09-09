// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

package dev.prime.render.post;

import java.util.Objects;

/** One device-free temporal version consumed exactly once by device execution. */
public final class SubmittedFrame<P> {
    private final P plan;
    private State state = State.PLANNED;

    public SubmittedFrame(P plan) {
        this.plan = Objects.requireNonNull(plan, "plan");
    }

    public P plan() {
        return this.plan;
    }

    public P claimForExecution() {
        if (this.state != State.PLANNED) {
            throw new IllegalArgumentException("Submitted frame was already consumed");
        }
        this.state = State.CLAIMED;
        return this.plan;
    }

    public void submitted() {
        if (this.state != State.CLAIMED) {
            throw new IllegalArgumentException("Submitted frame was not claimed for execution");
        }
        this.state = State.SUBMITTED;
    }

    public void abandon() {
        if (this.state == State.SUBMITTED || this.state == State.ABANDONED) {
            throw new IllegalArgumentException("Submitted frame was already completed");
        }
        this.state = State.ABANDONED;
    }

    private enum State {
        PLANNED,
        CLAIMED,
        SUBMITTED,
        ABANDONED
    }
}
