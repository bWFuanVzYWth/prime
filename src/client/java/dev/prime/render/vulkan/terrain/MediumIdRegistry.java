package dev.prime.render.vulkan.terrain;

import dev.prime.render.terrain.MaterialIdResolver;
import dev.prime.render.terrain.MediumKey;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Render-thread-owned renderer-lifetime MediumId allocator; IDs are never reused. */
final class MediumIdRegistry {
    static final int WATER_ID = 1;

    private final Map<MediumKey, Integer> ids = new HashMap<>();
    private long nextId = 2L;

    MediumIdRegistry() {
        this.ids.put(MediumKey.CAMERA_WATER, WATER_ID);
    }

    int[] resolve(List<MediumKey> localCatalog) {
        int[] result = new int[Math.addExact(localCatalog.size(), 1)];
        for (int index = 0; index < localCatalog.size(); index++) {
            result[index + 1] = this.resolve(localCatalog.get(index));
        }
        return result;
    }

    Snapshot snapshot() {
        return new Snapshot(this.ids.size(), this.nextId - 1L);
    }

    int resolve(MediumKey key) {
        Integer existing = this.ids.get(key);
        if (existing != null) {
            return existing;
        }
        if (this.nextId > MaterialIdResolver.MAX_ID) {
            throw new IllegalStateException("Prime exhausted its u16 MediumId space");
        }
        int assigned = (int) this.nextId++;
        this.ids.put(key, assigned);
        return assigned;
    }

    record Snapshot(int assignedCount, long highWaterId) {
        Snapshot {
            if (assignedCount < 1
                    || highWaterId < WATER_ID
                    || highWaterId > MaterialIdResolver.MAX_ID) {
                throw new IllegalArgumentException("Invalid renderer MediumId statistics");
            }
        }
    }
}
