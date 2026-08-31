package dev.prime.render.vulkan.terrain;

import dev.prime.render.terrain.MaterialIdResolver;
import dev.prime.render.terrain.MaterialTableCandidate;
import dev.prime.render.shader.ShaderAbi;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/** Render-thread-owned renderer-lifetime MaterialId allocator; IDs are never reused. */
final class MaterialIdRegistry {
    private static final int CORE_WORDS = ShaderAbi.MATERIAL_CORE_RECORD_SIZE / Integer.BYTES;
    static final long BUFFER_BYTES = Math.multiplyExact(
            (long) MaterialIdResolver.MAX_ID + 1L,
            ShaderAbi.MATERIAL_CORE_RECORD_SIZE);

    private final MediumIdRegistry mediumIds;
    private final Map<MaterialTableCandidate.Key, Integer> ids = new HashMap<>();
    private final int[] coreRecords =
            new int[(MaterialIdResolver.MAX_ID + 1) * CORE_WORDS];
    private int nextId = 1;

    MaterialIdRegistry(MediumIdRegistry mediumIds) {
        this.mediumIds = Objects.requireNonNull(mediumIds, "mediumIds");
    }

    int resolve(MaterialTableCandidate.Key key) {
        Objects.requireNonNull(key, "key");
        Integer existing = this.ids.get(key);
        if (existing != null) {
            return existing;
        }
        if (this.nextId > MaterialIdResolver.MAX_ID) {
            throw new IllegalStateException("Prime exhausted its u16 MaterialId space");
        }
        int assigned = this.nextId++;
        this.ids.put(key, assigned);
        int base = assigned * CORE_WORDS;
        this.coreRecords[base] = encodeCoreWord(key);
        this.coreRecords[base + ShaderAbi.MATERIAL_CORE_MEDIUM_ID_OFFSET / Integer.BYTES] =
                encodeMediumId(key.medium() == null ? 0 : this.mediumIds.resolve(key.medium()));
        return assigned;
    }

    int[] encodedCoreRecords() {
        return Arrays.copyOf(this.coreRecords, this.nextId * CORE_WORDS);
    }

    Snapshot snapshot() {
        return new Snapshot(this.ids.size(), this.nextId - 1);
    }

    record Snapshot(int assignedCount, int highWaterId) {
        Snapshot {
            if (assignedCount < 0
                    || assignedCount > MaterialIdResolver.MAX_ID
                    || highWaterId < 0
                    || highWaterId > MaterialIdResolver.MAX_ID
                    || assignedCount != highWaterId) {
                throw new IllegalArgumentException("Invalid renderer MaterialId statistics");
            }
        }
    }

    static int encodeCoreWord(MaterialTableCandidate.Key key) {
        Objects.requireNonNull(key, "key");
        int textureId = key.textureId();
        int control = key.materialControl();
        if ((textureId & ~ShaderAbi.MATERIAL_CORE_TEXTURE_ID_MASK) != 0
                || (control & ~ShaderAbi.MATERIAL_CORE_RECIPE_CONTROL_MASK) != 0) {
            throw new IllegalArgumentException(
                    "Material core facts exceed their generated ABI fields");
        }
        return textureId | control << ShaderAbi.MATERIAL_CORE_RECIPE_CONTROL_SHIFT;
    }

    static int encodeMediumId(int mediumId) {
        if ((mediumId & ~ShaderAbi.MATERIAL_CORE_MEDIUM_ID_MASK) != 0) {
            throw new IllegalArgumentException("MediumId exceeds the material core ABI field");
        }
        return mediumId;
    }
}
