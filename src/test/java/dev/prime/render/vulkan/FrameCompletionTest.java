package dev.prime.render.vulkan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.prime.infrastructure.ResourceCleanup;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

final class FrameCompletionTest {
    @Test
    void failureBeforeHostAcceptanceAbandonsEveryPreparedOwnerInProtocolOrder() {
        FrameCompletion completion = new FrameCompletion();
        List<String> events = new ArrayList<>();
        completion.onAbandon(30, failure -> record(events, "processor", failure));
        completion.onAbandon(0, failure -> record(events, "submission", failure));
        completion.onAbandon(20, failure -> record(events, "material", failure));
        completion.onAbandon(10, failure -> record(events, "atmosphere", failure));
        RuntimeException original = new IllegalStateException("record failed");

        RuntimeException result = completion.abandon(original);

        assertSame(original, result);
        assertEquals(
                List.of("submission", "atmosphere", "material", "processor"),
                events);
        assertThrows(IllegalStateException.class, () -> completion.abandon(original));
        assertThrows(IllegalStateException.class, completion::acceptedBySubmission);
    }

    @Test
    void acceptedFrameCommitsEveryHistoryEvenWhenOneCommitFails() {
        FrameCompletion completion = new FrameCompletion();
        List<String> events = new ArrayList<>();
        completion.onCommit(20, () -> events.add("atmosphere"));
        completion.onCommit(0, () -> events.add("submission"));
        completion.onCommit(10, () -> {
            events.add("exposure");
            throw new IllegalStateException("exposure completion failed");
        });
        completion.onCommit(30, () -> events.add("processor"));
        completion.onAbandon(0, failure -> record(events, "rollback", failure));
        completion.acceptedBySubmission();

        IllegalStateException failure = assertThrows(
                IllegalStateException.class, completion::commit);

        assertEquals("exposure completion failed", failure.getMessage());
        assertEquals(
                List.of("submission", "exposure", "atmosphere", "processor"),
                events);
        RuntimeException acceptedFailure = new IllegalStateException("post-accept failure");
        assertSame(acceptedFailure, completion.abandon(acceptedFailure));
        assertEquals(
                List.of("submission", "exposure", "atmosphere", "processor"),
                events);
        assertThrows(IllegalStateException.class, completion::commit);
    }

    @Test
    void abandonAggregatesCleanupFailuresWithoutLosingTheFrameFailure() {
        FrameCompletion completion = new FrameCompletion();
        completion.onAbandon(0, failure -> ResourceCleanup.run(
                () -> {
                    throw new IllegalArgumentException("first cleanup");
                }, failure));
        completion.onAbandon(1, failure -> ResourceCleanup.run(
                () -> {
                    throw new IllegalStateException("second cleanup");
                }, failure));
        RuntimeException original = new RuntimeException("frame");

        RuntimeException result = completion.abandon(original);

        assertSame(original, result);
        assertEquals(2, result.getSuppressed().length);
        assertEquals("first cleanup", result.getSuppressed()[0].getMessage());
        assertEquals("second cleanup", result.getSuppressed()[1].getMessage());
    }

    @Test
    void commitCannotRunBeforeOwnershipTransferAndRegistrationClosesAtAcceptance() {
        FrameCompletion completion = new FrameCompletion();
        assertThrows(IllegalStateException.class, completion::commit);

        completion.acceptedBySubmission();

        assertThrows(
                IllegalStateException.class,
                () -> completion.onCommit(0, () -> {
                }));
        assertThrows(
                IllegalStateException.class,
                () -> completion.onAbandon(0, failure -> failure));
    }

    private static RuntimeException record(
            List<String> events,
            String event,
            RuntimeException failure) {
        events.add(event);
        return failure;
    }
}
