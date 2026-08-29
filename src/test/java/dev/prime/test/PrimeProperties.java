package dev.prime.test;

import java.util.Objects;
import java.util.function.Consumer;
import org.jetbrains.jetCheck.Generator;
import org.jetbrains.jetCheck.PropertyChecker;

/** Repository-stable JetCheck configuration with one-command failure replay. */
public final class PrimeProperties {
    private static final long DEFAULT_SEED = 0x5eed_2026_0829L;

    private PrimeProperties() {
    }

    @SuppressWarnings("deprecation")
    public static <T> void check(
            String name,
            int iterations,
            Generator<T> generator,
            Consumer<T> assertion) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(generator, "generator");
        Objects.requireNonNull(assertion, "assertion");
        if (iterations <= 0) {
            throw new IllegalArgumentException("Property iteration count must be positive");
        }
        long repositorySeed = repositorySeed();
        long propertySeed = mix(repositorySeed ^ name.hashCode());
        try {
            PropertyChecker.customized()
                    .withSeed(propertySeed)
                    .withIterationCount(iterations)
                    .forAll(generator, value -> {
                        assertion.accept(value);
                        return true;
                    });
        } catch (AssertionError failure) {
            throw new AssertionError(
                    "Property '" + name + "' failed with repository seed "
                            + repositorySeed
                            + "; replay with -Dprime.test.seed=" + repositorySeed,
                    failure);
        }
    }

    private static long repositorySeed() {
        String configured = System.getProperty("prime.test.seed");
        if (configured == null || configured.isBlank()) {
            return DEFAULT_SEED;
        }
        try {
            return Long.decode(configured.trim());
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(
                    "prime.test.seed must be a decimal or 0x-prefixed signed long",
                    exception);
        }
    }

    private static long mix(long value) {
        value = (value ^ (value >>> 30)) * 0xbf58_476d_1ce4_e5b9L;
        value = (value ^ (value >>> 27)) * 0x94d0_49bb_1331_11ebL;
        return value ^ (value >>> 31);
    }
}
