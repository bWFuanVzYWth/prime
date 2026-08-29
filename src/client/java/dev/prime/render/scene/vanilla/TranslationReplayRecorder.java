package dev.prime.render.scene.vanilla;

import dev.prime.infrastructure.PrimeInfo;
import dev.prime.render.terrain.ClusterTranslationInput;
import dev.prime.render.terrain.ClusterTranslationReplay;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReferenceArray;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Bounded, opt-in publisher for user-site translation replay inputs.
 *
 * <p>One {@link VanillaClusterCompiler} owns this recorder for its full lifetime. Compile jobs may
 * finish on different worker threads, so atomics reserve distinct file slots and suppress repeated
 * warnings without introducing a lock domain or lock order.
 */
final class TranslationReplayRecorder {
    static final String ENABLE_PROPERTY = "prime.translation.replay";
    static final String MIN_MILLIS_PROPERTY = "prime.translation.replay.minMillis";
    static final String MAX_FILES_PROPERTY = "prime.translation.replay.maxFiles";
    static final long DEFAULT_MIN_MILLIS = 250L;
    static final int DEFAULT_MAX_FILES = 8;
    static final int HARD_MAX_FILES = 32;

    private final Path directory;
    private final long minimumSuccessNanos;
    private final Lane successful;
    private final Lane cancelled;
    private final Lane failed;
    private final AtomicBoolean warned = new AtomicBoolean();

    private TranslationReplayRecorder() {
        this.directory = null;
        this.minimumSuccessNanos = Long.MAX_VALUE;
        this.successful = new Lane(0);
        this.cancelled = new Lane(0);
        this.failed = new Lane(0);
    }

    TranslationReplayRecorder(Path directory, long minimumSuccessMillis, int maximumFiles) {
        this.directory = Objects.requireNonNull(directory, "directory");
        if (minimumSuccessMillis < 0) {
            throw new IllegalArgumentException("Replay minimum duration must not be negative");
        }
        if (maximumFiles < 0 || maximumFiles > HARD_MAX_FILES) {
            throw new IllegalArgumentException("Replay file limit must be in [0, 32]");
        }
        this.minimumSuccessNanos = Math.multiplyExact(minimumSuccessMillis, 1_000_000L);
        int failedFiles = maximumFiles == 0 ? 0 : Math.max(1, maximumFiles / 4);
        int cancelledFiles = maximumFiles >= 3 ? Math.max(1, maximumFiles / 8) : 0;
        this.failed = new Lane(failedFiles);
        this.cancelled = new Lane(cancelledFiles);
        this.successful = new Lane(maximumFiles - failedFiles - cancelledFiles);
    }

    static TranslationReplayRecorder fromSystemProperties() {
        if (!Boolean.getBoolean(ENABLE_PROPERTY)) {
            // Do not ask Fabric for the game path while capture is disabled.
            return new TranslationReplayRecorder();
        }
        long minimumMillis = readLong(MIN_MILLIS_PROPERTY, DEFAULT_MIN_MILLIS);
        minimumMillis = Math.max(
                0L, Math.min(minimumMillis, Long.MAX_VALUE / 1_000_000L));
        int maximumFiles = (int) Math.max(
                0L,
                Math.min(
                        readLong(MAX_FILES_PROPERTY, DEFAULT_MAX_FILES),
                        HARD_MAX_FILES));
        return new TranslationReplayRecorder(
                FabricLoader.getInstance()
                        .getGameDir()
                        .resolve("prime-translation-replays"),
                minimumMillis,
                maximumFiles);
    }

    void record(
            ClusterTranslationInput input,
            ClusterTranslationReplay.Metadata metadata) {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(metadata, "metadata");
        if (this.directory == null
                || metadata.outcome() == ClusterTranslationReplay.Outcome.SUCCESS
                        && metadata.elapsedNanos() < this.minimumSuccessNanos) {
            return;
        }
        Lane lane = this.lane(metadata.outcome());
        int slot = lane.nextSlot();
        if (slot < 0) {
            return;
        }
        Path temporary = null;
        try {
            Files.createDirectories(this.directory);
            temporary = Files.createTempFile(this.directory, ".pending-", ".ptr.gz");
            ClusterTranslationReplay.write(temporary, input, metadata);
            String hash = sha256(temporary);
            Path published = this.directory.resolve(String.format(
                    "%s-%02d-%s.ptr.gz",
                    metadata.outcome().name().toLowerCase(java.util.Locale.ROOT),
                    slot,
                    hash));
            publish(temporary, published);
            temporary = null;
            Path previous = lane.replace(slot, published);
            if (previous != null && !previous.equals(published)) {
                Files.deleteIfExists(previous);
            }
        } catch (IOException | RuntimeException exception) {
            this.warnOnce(exception);
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException exception) {
                    this.warnOnce(exception);
                }
            }
        }
    }

    private Lane lane(ClusterTranslationReplay.Outcome outcome) {
        return switch (outcome) {
            case SUCCESS -> this.successful;
            case CANCELLED -> this.cancelled;
            case FAILED -> this.failed;
        };
    }

    private void warnOnce(Throwable failure) {
        if (this.warned.compareAndSet(false, true)) {
            PrimeInfo.LOGGER.warn(
                    "Unable to export a Prime cluster-translation replay; rendering continues",
                    failure);
        }
    }

    private static void publish(Path temporary, Path published) throws IOException {
        try {
            Files.move(
                    temporary,
                    published,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(temporary, published, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static String sha256(Path path) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("JVM does not provide SHA-256", exception);
        }
        try (InputStream input = Files.newInputStream(path)) {
            byte[] buffer = new byte[16 * 1024];
            int count;
            while ((count = input.read(buffer)) >= 0) {
                digest.update(buffer, 0, count);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static long readLong(String property, long fallback) {
        String value = System.getProperty(property);
        if (value == null) {
            return fallback;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }

    /** One outcome owns independent rotating slots, so ordinary work cannot consume incidents. */
    private static final class Lane {
        private final AtomicInteger cursor = new AtomicInteger();
        private final AtomicReferenceArray<Path> files;

        private Lane(int capacity) {
            this.files = new AtomicReferenceArray<>(capacity);
        }

        private int nextSlot() {
            int capacity = this.files.length();
            return capacity == 0 ? -1 : Math.floorMod(this.cursor.getAndIncrement(), capacity);
        }

        private Path replace(int slot, Path path) {
            return this.files.getAndSet(slot, path);
        }
    }
}
