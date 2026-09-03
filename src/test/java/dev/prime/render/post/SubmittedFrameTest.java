package dev.prime.render.post;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

final class SubmittedFrameTest {
    @Test
    void submissionRequiresExactlyOneExecutionClaim() {
        SubmittedFrame<String> frame = new SubmittedFrame<>("frame");
        assertEquals("frame", frame.plan());
        assertThrows(IllegalArgumentException.class, frame::submitted);

        assertEquals("frame", frame.claimForExecution());
        assertThrows(IllegalArgumentException.class, frame::claimForExecution);
        frame.submitted();
        assertThrows(IllegalArgumentException.class, frame::submitted);
        assertThrows(IllegalArgumentException.class, frame::abandon);
    }

    @Test
    void abandonmentAcceptsPlannedOrClaimedFramesOnlyOnce() {
        SubmittedFrame<String> planned = new SubmittedFrame<>("planned");
        planned.abandon();
        assertThrows(IllegalArgumentException.class, planned::claimForExecution);
        assertThrows(IllegalArgumentException.class, planned::abandon);

        SubmittedFrame<String> claimed = new SubmittedFrame<>("claimed");
        claimed.claimForExecution();
        claimed.abandon();
        assertThrows(IllegalArgumentException.class, claimed::submitted);
    }
}
