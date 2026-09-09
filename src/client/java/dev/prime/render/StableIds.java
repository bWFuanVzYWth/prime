// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

package dev.prime.render;

import java.util.Optional;
import java.util.function.Function;

/** Allocation-free lookup for persisted enum identifiers. */
public final class StableIds {
    private StableIds() {
    }

    public static <T> Optional<T> find(
            T[] values, String id, Function<T, String> identifier) {
        if (id == null) {
            return Optional.empty();
        }
        for (T value : values) {
            if (identifier.apply(value).equals(id)) {
                return Optional.of(value);
            }
        }
        return Optional.empty();
    }
}
