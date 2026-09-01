package dev.prime.render.terrain;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/** Exact material-table keys recoverable from the current primitive ABI without changing it. */
public final class MaterialTableCandidate {
    public static final int MAX_MATERIAL_ID = 0xffff;
    public static final MaterialTableCandidate EMPTY = new MaterialTableCandidate(
            Map.of(), 0L, 0L, 0L, 0L, 0L, 0L);

    private final Map<Key, Long> references;
    private final long staticSurfaceReferences;
    private final long relationMaterialReferences;
    private final long lightEmitterReferences;
    private final long voxelSurfaceReferences;
    private final long dynamicReferences;
    private final long bakedReferences;

    private MaterialTableCandidate(
            Map<Key, Long> references,
            long staticSurfaceReferences,
            long relationMaterialReferences,
            long lightEmitterReferences,
            long voxelSurfaceReferences,
            long dynamicReferences,
            long bakedReferences) {
        this.references = Map.copyOf(references);
        this.staticSurfaceReferences = requireNonNegative(
                staticSurfaceReferences, "Static-surface reference count");
        this.relationMaterialReferences = requireNonNegative(
                relationMaterialReferences, "Relation-material reference count");
        this.lightEmitterReferences = requireNonNegative(
                lightEmitterReferences, "Light-emitter reference count");
        this.voxelSurfaceReferences = requireNonNegative(
                voxelSurfaceReferences, "Voxel-surface reference count");
        this.dynamicReferences = requireNonNegative(
                dynamicReferences, "Dynamic reference count");
        this.bakedReferences = requireNonNegative(
                bakedReferences, "Baked reference count");
        for (Map.Entry<Key, Long> entry : this.references.entrySet()) {
            Objects.requireNonNull(entry.getKey(), "material key");
            if (entry.getValue() == null || entry.getValue() <= 0L) {
                throw new IllegalArgumentException(
                        "Material-table reference count must be positive");
            }
        }
        if (this.references.size() > MAX_MATERIAL_ID) {
            throw new IllegalStateException(
                    "Observed material table exceeds its exact u16 identity domain");
        }
    }

    /** Measures texture-backed material facts while leaving geometry-varying fields out of keys. */
    public static MaterialTableCandidate measure(CpuClusterMesh mesh) {
        Objects.requireNonNull(mesh, "mesh");
        Builder result = new Builder(mesh.mediumCatalog());
        for (CpuClusterMesh.Segment segment : mesh.segments()) {
            int[] primitives = segment.primitiveRecords();
            int primitiveCount = primitives.length / CpuSectionMesh.PRIMITIVE_WORDS;
            for (int primitive = 0; primitive < primitiveCount; primitive++) {
                int base = primitive * CpuSectionMesh.PRIMITIVE_WORDS;
                result.addPrimitive(
                        primitives,
                        base,
                        mesh.lights(),
                        ReferenceKind.STATIC_SURFACE);
                int[] relation = SurfaceRelationTable.record(
                        segment.surfaceRelationRecords(), primitiveCount, primitive);
                if (relation != null) {
                    result.addRelation(relation);
                }
            }
        }
        // Voxel instance tint and placement are geometry-varying. The immutable mesh contributes
        // one material reference set regardless of its number of instances in this cluster.
        for (CpuVoxelMesh voxel : mesh.voxelMeshes()) {
            int[] primitives = voxel.primitiveRecords();
            for (int base = 0; base < primitives.length;
                    base += CpuSectionMesh.PRIMITIVE_WORDS) {
                result.addPrimitive(
                        primitives,
                        base,
                        CompiledClusterLights.EMPTY,
                        ReferenceKind.VOXEL_SURFACE);
            }
        }
        return result.build();
    }

    /** Sums concurrently resident source references and globally unifies their exact keys. */
    public static MaterialTableCandidate combine(List<MaterialTableCandidate> values) {
        Builder result = new Builder(List.of());
        for (MaterialTableCandidate value : values) {
            result.add(Objects.requireNonNull(value, "value"), false);
        }
        return result.build();
    }

    /** Unions observed keys over time while retaining maximum, rather than frame-summed, counts. */
    public MaterialTableCandidate observedUnion(MaterialTableCandidate other) {
        Objects.requireNonNull(other, "other");
        Builder result = new Builder(List.of());
        result.add(this, true);
        result.add(other, true);
        return result.build();
    }

    public Map<Key, Long> references() {
        return this.references;
    }

    public int uniqueMaterialCount() {
        return this.references.size();
    }

    public long candidateReferenceCount() {
        long result = 0L;
        for (long count : this.references.values()) {
            result = Math.addExact(result, count);
        }
        return result;
    }

    public long staticSurfaceReferences() {
        return this.staticSurfaceReferences;
    }

    public long relationMaterialReferences() {
        return this.relationMaterialReferences;
    }

    public long lightEmitterReferences() {
        return this.lightEmitterReferences;
    }

    public long voxelSurfaceReferences() {
        return this.voxelSurfaceReferences;
    }

    public long dynamicReferences() {
        return this.dynamicReferences;
    }

    public long bakedReferences() {
        return this.bakedReferences;
    }

    /** TextureId leads the key; medium and material semantics only distinguish real behavior. */
    public record Key(int textureId, @Nullable MediumKey medium, int materialControl) {
        public Key {
            if (textureId <= 0 || textureId > 0xffff) {
                throw new IllegalArgumentException(
                        "Material-table TextureId exceeds its exact u16 domain");
            }
            PrimitivePacking.requireValidControl(materialControl);
            if ((materialControl & (PrimitivePacking.CONTROL_TANGENT_NEGATIVE
                    | PrimitivePacking.CONTROL_FRONT_FACE_ONLY)) != 0) {
                throw new IllegalArgumentException(
                        "Material-table key contains geometry-varying orientation controls");
            }
            boolean solidMedium = PrimitivePacking.isTransmissive(materialControl)
                    && !PrimitivePacking.isThinWalled(materialControl);
            if (solidMedium != (medium != null)) {
                throw new IllegalArgumentException(
                        "Material-table medium disagrees with transmissive topology");
            }
        }
    }

    static @Nullable Key primitiveKey(
            int[] words,
            int base,
            List<MediumKey> mediumCatalog,
            CompiledClusterLights lights) {
        int flagsEmitter = words[base + 5];
        if ((flagsEmitter & PrimitivePacking.DYNAMIC_TEXTURE_FLAG) != 0
                || words[base + 6] == PrimitivePacking.CONSTANT_UV_DENSITY
                        && (words[base + 2] & PrimitivePacking.CONSTANT_UV_BAKED_MATERIAL) != 0) {
            return null;
        }
        int textureId = PrimitivePacking.unpackTextureId(flagsEmitter);
        int emitter = PrimitivePacking.unpackEmitterIndex(flagsEmitter);
        if (emitter != PrimitivePacking.NO_EMITTER_INDEX) {
            textureId = lights.emitterMaterial(emitter).textureId();
        }
        if (textureId == 0) {
            throw new IllegalStateException(
                    "Static material primitive has no exact TextureId");
        }
        return new Key(
                textureId,
                medium(
                        mediumCatalog,
                        PrimitivePacking.unpackSourceMediumId(
                                words[base + PrimitivePacking.MEDIUM_ID_WORD])),
                semanticControl(PrimitivePacking.unpackControl(
                        words[base + 3], flagsEmitter)));
    }

    static Key boundaryKey(int[] relation, int base, List<MediumKey> mediumCatalog) {
        return new Key(
                relation[base + 3],
                medium(
                        mediumCatalog,
                        PrimitivePacking.unpackSourceMediumId(relation[base + 4])),
                semanticControl(relation[base] >>> 8));
    }

    private enum ReferenceKind {
        STATIC_SURFACE,
        RELATION_MATERIAL,
        VOXEL_SURFACE
    }

    private static final class Builder {
        private final List<MediumKey> mediumCatalog;
        private final HashMap<Key, Long> references = new HashMap<>();
        private long staticSurfaceReferences;
        private long relationMaterialReferences;
        private long lightEmitterReferences;
        private long voxelSurfaceReferences;
        private long dynamicReferences;
        private long bakedReferences;

        Builder(List<MediumKey> mediumCatalog) {
            this.mediumCatalog = List.copyOf(mediumCatalog);
        }

        void addPrimitive(
                int[] words,
                int base,
                CompiledClusterLights lights,
                ReferenceKind kind) {
            int flagsEmitter = words[base + 5];
            if ((flagsEmitter & PrimitivePacking.DYNAMIC_TEXTURE_FLAG) != 0) {
                this.dynamicReferences = Math.addExact(this.dynamicReferences, 1L);
                return;
            }
            if (words[base + 6] == PrimitivePacking.CONSTANT_UV_DENSITY
                    && (words[base + 2] & PrimitivePacking.CONSTANT_UV_BAKED_MATERIAL) != 0) {
                this.bakedReferences = Math.addExact(this.bakedReferences, 1L);
                return;
            }
            int emitter = PrimitivePacking.unpackEmitterIndex(flagsEmitter);
            Key key = Objects.requireNonNull(
                    primitiveKey(words, base, this.mediumCatalog, lights),
                    "eligible material key");
            this.addKey(key, kind, 1L);
            if (emitter != PrimitivePacking.NO_EMITTER_INDEX) {
                this.references.merge(key, 1L, Math::addExact);
                this.lightEmitterReferences = Math.addExact(
                        this.lightEmitterReferences, 1L);
            }
        }

        void addRelation(int[] relation) {
            int kind = relation[0] & CpuSectionMesh.SURFACE_RELATION_KIND_MASK;
            if (kind == CpuSectionMesh.SURFACE_RELATION_BOUNDARY) {
                this.addKey(
                        boundaryKey(relation, 0, this.mediumCatalog),
                        ReferenceKind.RELATION_MATERIAL,
                        1L);
                return;
            }
            this.addPrimitive(
                    relation,
                    1,
                    CompiledClusterLights.EMPTY,
                    ReferenceKind.RELATION_MATERIAL);
        }

        void addKey(Key key, ReferenceKind kind, long count) {
            this.references.merge(key, count, Math::addExact);
            switch (kind) {
                case STATIC_SURFACE -> this.staticSurfaceReferences =
                        Math.addExact(this.staticSurfaceReferences, count);
                case RELATION_MATERIAL -> this.relationMaterialReferences =
                        Math.addExact(this.relationMaterialReferences, count);
                case VOXEL_SURFACE -> this.voxelSurfaceReferences =
                        Math.addExact(this.voxelSurfaceReferences, count);
            }
        }

        void add(MaterialTableCandidate value, boolean maximum) {
            for (Map.Entry<Key, Long> entry : value.references.entrySet()) {
                this.references.merge(
                        entry.getKey(), entry.getValue(), maximum ? Math::max : Math::addExact);
            }
            if (maximum) {
                this.staticSurfaceReferences = Math.max(
                        this.staticSurfaceReferences, value.staticSurfaceReferences);
                this.relationMaterialReferences = Math.max(
                        this.relationMaterialReferences, value.relationMaterialReferences);
                this.lightEmitterReferences = Math.max(
                        this.lightEmitterReferences, value.lightEmitterReferences);
                this.voxelSurfaceReferences = Math.max(
                        this.voxelSurfaceReferences, value.voxelSurfaceReferences);
                this.dynamicReferences = Math.max(
                        this.dynamicReferences, value.dynamicReferences);
                this.bakedReferences = Math.max(
                        this.bakedReferences, value.bakedReferences);
            } else {
                this.staticSurfaceReferences = Math.addExact(
                        this.staticSurfaceReferences, value.staticSurfaceReferences);
                this.relationMaterialReferences = Math.addExact(
                        this.relationMaterialReferences, value.relationMaterialReferences);
                this.lightEmitterReferences = Math.addExact(
                        this.lightEmitterReferences, value.lightEmitterReferences);
                this.voxelSurfaceReferences = Math.addExact(
                        this.voxelSurfaceReferences, value.voxelSurfaceReferences);
                this.dynamicReferences = Math.addExact(
                        this.dynamicReferences, value.dynamicReferences);
                this.bakedReferences = Math.addExact(
                        this.bakedReferences, value.bakedReferences);
            }
        }

        MaterialTableCandidate build() {
            if (this.references.isEmpty()
                    && this.dynamicReferences == 0L
                    && this.bakedReferences == 0L) {
                return EMPTY;
            }
            return new MaterialTableCandidate(
                    this.references,
                    this.staticSurfaceReferences,
                    this.relationMaterialReferences,
                    this.lightEmitterReferences,
                    this.voxelSurfaceReferences,
                    this.dynamicReferences,
                    this.bakedReferences);
        }
    }

    private static int semanticControl(int control) {
        return control & ~(PrimitivePacking.CONTROL_TANGENT_NEGATIVE
                | PrimitivePacking.CONTROL_FRONT_FACE_ONLY);
    }

    private static @Nullable MediumKey medium(List<MediumKey> catalog, int localId) {
        if (localId == 0) {
            return null;
        }
        if (localId < 0 || localId > catalog.size()) {
            throw new IllegalArgumentException(
                    "Material primitive references a medium outside its local catalog");
        }
        return catalog.get(localId - 1);
    }

    private static long requireNonNegative(long value, String name) {
        if (value < 0L) {
            throw new IllegalArgumentException(name + " must not be negative");
        }
        return value;
    }
}
