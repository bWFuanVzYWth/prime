package dev.prime.render.scene.vanilla;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.prime.render.terrain.CapturedCluster;
import dev.prime.render.terrain.ClusterTranslationInput;
import dev.prime.render.terrain.ClusterTranslationReplay;
import dev.prime.render.terrain.ClusterTranslationSettings;
import dev.prime.render.terrain.LabPbrMaterialSet;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class TranslationReplayRecorderTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void concurrentRecorderNeverPublishesBeyondItsAtomicLimit() throws Exception {
        Path output = this.temporaryDirectory.resolve("replays");
        TranslationReplayRecorder recorder = new TranslationReplayRecorder(output, 0L, 3);
        ClusterTranslationInput input = emptyInput();
        try (var executor = Executors.newFixedThreadPool(8)) {
            List<? extends java.util.concurrent.Future<?>> futures =
                    java.util.stream.IntStream.range(0, 24)
                    .mapToObj(index -> executor.submit(() -> {
                        ClusterTranslationReplay.Metadata metadata = switch (index % 3) {
                            case 0 -> ClusterTranslationReplay.Metadata.success(index);
                            case 1 -> ClusterTranslationReplay.Metadata.failure(
                                    ClusterTranslationReplay.Outcome.CANCELLED,
                                    index,
                                    new RuntimeException("cancelled-" + index));
                            default -> ClusterTranslationReplay.Metadata.failure(
                                    ClusterTranslationReplay.Outcome.FAILED,
                                    index,
                                    new RuntimeException("failed-" + index));
                        };
                        recorder.record(input, metadata);
                    }))
                    .toList();
            for (var future : futures) {
                future.get();
            }
        }

        List<Path> files;
        try (var paths = Files.list(output)) {
            files = paths.toList();
        }
        assertEquals(3, files.size());
        Set<ClusterTranslationReplay.Outcome> outcomes = files.stream()
                .map(file -> read(file).metadata().outcome())
                .collect(Collectors.toSet());
        assertEquals(Set.of(
                ClusterTranslationReplay.Outcome.SUCCESS,
                ClusterTranslationReplay.Outcome.CANCELLED,
                ClusterTranslationReplay.Outcome.FAILED), outcomes);
        for (Path file : files) {
            String name = file.getFileName().toString();
            assertTrue(name.matches(
                    "(?:success|cancelled|failed)-\\d{2}-[0-9a-f]{64}\\.ptr\\.gz"),
                    name);
            assertTrue(name.contains(sha256(file)), name);
        }
    }

    @Test
    void slowThresholdAppliesOnlyToSuccessfulTranslations() throws IOException {
        Path output = this.temporaryDirectory.resolve("threshold-replays");
        TranslationReplayRecorder recorder = new TranslationReplayRecorder(output, 10_000L, 3);
        ClusterTranslationInput input = emptyInput();

        recorder.record(input, ClusterTranslationReplay.Metadata.success(1L));
        recorder.record(
                input,
                ClusterTranslationReplay.Metadata.failure(
                        ClusterTranslationReplay.Outcome.CANCELLED,
                        1L,
                        new RuntimeException("cancelled")));
        recorder.record(
                input,
                ClusterTranslationReplay.Metadata.failure(
                        ClusterTranslationReplay.Outcome.FAILED,
                        1L,
                        new RuntimeException("failed")));

        List<Path> files;
        try (var paths = Files.list(output)) {
            files = paths.toList();
        }
        assertEquals(2, files.size());
        assertTrue(files.stream().anyMatch(file ->
                file.getFileName().toString().startsWith("cancelled-00-")));
        assertTrue(files.stream().anyMatch(file ->
                file.getFileName().toString().startsWith("failed-00-")));
    }

    @Test
    void laterIncidentsRotateWithoutBeingBlockedByOrdinarySlowCaptures() throws IOException {
        Path output = this.temporaryDirectory.resolve("rotating-replays");
        TranslationReplayRecorder recorder = new TranslationReplayRecorder(output, 0L, 8);
        ClusterTranslationInput input = emptyInput();

        for (int index = 0; index < 12; index++) {
            recorder.record(input, ClusterTranslationReplay.Metadata.success(index));
        }
        for (int index = 0; index < 4; index++) {
            recorder.record(
                    input,
                    ClusterTranslationReplay.Metadata.failure(
                            ClusterTranslationReplay.Outcome.CANCELLED,
                            index,
                            new RuntimeException("cancelled-" + index)));
            recorder.record(
                    input,
                    ClusterTranslationReplay.Metadata.failure(
                            ClusterTranslationReplay.Outcome.FAILED,
                            index,
                            new RuntimeException("failed-" + index)));
        }

        List<ClusterTranslationReplay.Metadata> metadata;
        try (var paths = Files.list(output)) {
            metadata = paths.map(TranslationReplayRecorderTest::read)
                    .map(ClusterTranslationReplay.Decoded::metadata)
                    .toList();
        }
        assertEquals(8, metadata.size());
        assertEquals(5, count(metadata, ClusterTranslationReplay.Outcome.SUCCESS));
        assertEquals(1, count(metadata, ClusterTranslationReplay.Outcome.CANCELLED));
        assertEquals(2, count(metadata, ClusterTranslationReplay.Outcome.FAILED));
        assertTrue(metadata.stream().anyMatch(value ->
                value.outcome() == ClusterTranslationReplay.Outcome.CANCELLED
                        && value.failureMessage().equals("cancelled-3")));
        assertEquals(
                Set.of("failed-2", "failed-3"),
                metadata.stream()
                        .filter(value -> value.outcome() == ClusterTranslationReplay.Outcome.FAILED)
                        .map(ClusterTranslationReplay.Metadata::failureMessage)
                        .collect(Collectors.toSet()));
    }

    private static ClusterTranslationInput emptyInput() {
        return new ClusterTranslationInput(
                new CapturedCluster.Builder(0, 0, 0).build(),
                LabPbrMaterialSet.EMPTY,
                new ClusterTranslationSettings(
                        false, 64, 2, 2, false, 0.0F, false, false));
    }

    private static String sha256(Path file) throws IOException {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new AssertionError(exception);
        }
    }

    private static ClusterTranslationReplay.Decoded read(Path file) {
        try {
            return ClusterTranslationReplay.read(file);
        } catch (IOException exception) {
            throw new AssertionError(exception);
        }
    }

    private static long count(
            List<ClusterTranslationReplay.Metadata> metadata,
            ClusterTranslationReplay.Outcome outcome) {
        return metadata.stream().filter(value -> value.outcome() == outcome).count();
    }
}
