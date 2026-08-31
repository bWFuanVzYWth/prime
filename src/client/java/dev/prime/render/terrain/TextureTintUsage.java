package dev.prime.render.terrain;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Exact static-texture/tint requirements observed in one translated renderer payload. */
public final class TextureTintUsage {
    public static final TextureTintUsage EMPTY = new TextureTintUsage(
            Map.of(), 0L, 0L, 0L, 0L, 0L, 0L);

    private final Map<Pair, Long> pairReferences;
    private final long staticSurfaceReferences;
    private final long relationMaterialReferences;
    private final long lightEmitterReferences;
    private final long voxelSurfaceReferences;
    private final long dynamicReferences;
    private final long bakedReferences;

    private TextureTintUsage(
            Map<Pair, Long> pairReferences,
            long staticSurfaceReferences,
            long relationMaterialReferences,
            long lightEmitterReferences,
            long voxelSurfaceReferences,
            long dynamicReferences,
            long bakedReferences) {
        this.pairReferences = Map.copyOf(pairReferences);
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
        for (Map.Entry<Pair, Long> entry : this.pairReferences.entrySet()) {
            Objects.requireNonNull(entry.getKey(), "pair");
            if (entry.getValue() == null || entry.getValue() <= 0L) {
                throw new IllegalArgumentException(
                        "Texture/tint pair reference count must be positive");
            }
        }
    }

    /** Measures the same material identity rules used by the hit and light shaders. */
    public static TextureTintUsage measure(CpuClusterMesh mesh) {
        Objects.requireNonNull(mesh, "mesh");
        Builder result = new Builder();
        for (CpuClusterMesh.Segment segment : mesh.segments()) {
            int[] primitives = segment.primitiveRecords();
            int primitiveCount = primitives.length / CpuSectionMesh.PRIMITIVE_WORDS;
            for (int primitive = 0; primitive < primitiveCount; primitive++) {
                int base = primitive * CpuSectionMesh.PRIMITIVE_WORDS;
                result.addPrimitive(
                        primitives, base, null, mesh.lights(), ReferenceKind.STATIC_SURFACE);
                int[] relation = SurfaceRelationTable.record(
                        segment.surfaceRelationRecords(), primitiveCount, primitive);
                if (relation != null) {
                    result.addRelation(relation);
                }
            }
        }
        for (int emitter = 0; emitter < mesh.lights().emitterCount(); emitter++) {
            CompiledClusterLights.EmitterMaterial material =
                    mesh.lights().emitterMaterial(emitter);
            result.addPair(
                    material.textureId(),
                    material.packedTint(),
                    ReferenceKind.LIGHT_EMITTER,
                    1L);
        }
        List<CpuVoxelMesh> voxelMeshes = mesh.voxelMeshes();
        CpuVoxelInstances instances = mesh.voxelInstances();
        for (int instance = 0; instance < instances.count(); instance++) {
            CpuVoxelMesh voxel = voxelMeshes.get(instances.meshIndex(instance));
            int[] primitives = voxel.primitiveRecords();
            Integer tint = instances.packedTint(instance);
            for (int base = 0; base < primitives.length;
                    base += CpuSectionMesh.PRIMITIVE_WORDS) {
                result.addPrimitive(
                        primitives,
                        base,
                        tint,
                        CompiledClusterLights.EMPTY,
                        ReferenceKind.VOXEL_SURFACE);
            }
        }
        return result.build();
    }

    /** Sums concurrently resident cluster requirements. */
    public static TextureTintUsage combine(List<TextureTintUsage> values) {
        Builder result = new Builder();
        for (TextureTintUsage value : values) {
            result.add(Objects.requireNonNull(value, "value"), false);
        }
        return result.build();
    }

    /** Unions observations over time without multiplying residency counts by frame duration. */
    public TextureTintUsage observedUnion(TextureTintUsage other) {
        Objects.requireNonNull(other, "other");
        Builder result = new Builder();
        result.add(this, true);
        result.add(other, true);
        return result.build();
    }

    public Map<Pair, Long> pairReferences() {
        return this.pairReferences;
    }

    public Set<Integer> textureIds() {
        HashSet<Integer> result = new HashSet<>();
        for (Pair pair : this.pairReferences.keySet()) {
            result.add(pair.textureId());
        }
        return Set.copyOf(result);
    }

    public Set<Integer> packedTints() {
        HashSet<Integer> result = new HashSet<>();
        for (Pair pair : this.pairReferences.keySet()) {
            result.add(pair.packedTint());
        }
        return Set.copyOf(result);
    }

    public long pairReferenceCount() {
        long result = 0L;
        for (long count : this.pairReferences.values()) {
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

    public record Pair(int textureId, int packedTint) {
        public Pair {
            if (textureId <= 0 || textureId > PrimitivePacking.MAX_TEXTURE_ID) {
                throw new IllegalArgumentException("TextureId exceeds its exact ABI domain");
            }
            if ((packedTint & 0xff00_0000) != 0) {
                throw new IllegalArgumentException("Packed tint exceeds RGB8");
            }
        }
    }

    private enum ReferenceKind {
        STATIC_SURFACE,
        RELATION_MATERIAL,
        LIGHT_EMITTER,
        VOXEL_SURFACE
    }

    private static final class Builder {
        private final HashMap<Pair, Long> pairs = new HashMap<>();
        private long staticSurfaceReferences;
        private long relationMaterialReferences;
        private long lightEmitterReferences;
        private long voxelSurfaceReferences;
        private long dynamicReferences;
        private long bakedReferences;

        void addPrimitive(
                int[] words,
                int base,
                Integer tintOverride,
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
            int textureId = PrimitivePacking.unpackTextureId(flagsEmitter);
            int emitter = PrimitivePacking.unpackEmitterIndex(flagsEmitter);
            if (emitter != PrimitivePacking.NO_EMITTER_INDEX) {
                textureId = lights.emitterMaterial(emitter).textureId();
            }
            if (textureId == 0) {
                throw new IllegalStateException(
                        "Static material primitive has no exact TextureId");
            }
            int tint = tintOverride == null
                    ? words[base + 3] & 0x00ff_ffff
                    : tintOverride;
            this.addPair(textureId, tint, kind, 1L);
        }

        void addRelation(int[] relation) {
            int kind = relation[0] & CpuSectionMesh.SURFACE_RELATION_KIND_MASK;
            if (kind == CpuSectionMesh.SURFACE_RELATION_BOUNDARY) {
                this.addPair(
                        relation[3],
                        relation[2] & 0x00ff_ffff,
                        ReferenceKind.RELATION_MATERIAL,
                        1L);
                return;
            }
            this.addPrimitive(
                    relation,
                    1,
                    null,
                    CompiledClusterLights.EMPTY,
                    ReferenceKind.RELATION_MATERIAL);
        }

        void addPair(int textureId, int tint, ReferenceKind kind, long count) {
            Pair pair = new Pair(textureId, tint);
            this.pairs.merge(pair, count, Math::addExact);
            switch (kind) {
                case STATIC_SURFACE -> this.staticSurfaceReferences =
                        Math.addExact(this.staticSurfaceReferences, count);
                case RELATION_MATERIAL -> this.relationMaterialReferences =
                        Math.addExact(this.relationMaterialReferences, count);
                case LIGHT_EMITTER -> this.lightEmitterReferences =
                        Math.addExact(this.lightEmitterReferences, count);
                case VOXEL_SURFACE -> this.voxelSurfaceReferences =
                        Math.addExact(this.voxelSurfaceReferences, count);
            }
        }

        void add(TextureTintUsage value, boolean maximum) {
            for (Map.Entry<Pair, Long> entry : value.pairReferences.entrySet()) {
                if (maximum) {
                    this.pairs.merge(entry.getKey(), entry.getValue(), Math::max);
                } else {
                    this.pairs.merge(entry.getKey(), entry.getValue(), Math::addExact);
                }
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

        TextureTintUsage build() {
            if (this.pairs.isEmpty()
                    && this.dynamicReferences == 0L
                    && this.bakedReferences == 0L) {
                return EMPTY;
            }
            return new TextureTintUsage(
                    this.pairs,
                    this.staticSurfaceReferences,
                    this.relationMaterialReferences,
                    this.lightEmitterReferences,
                    this.voxelSurfaceReferences,
                    this.dynamicReferences,
                    this.bakedReferences);
        }
    }

    private static long requireNonNegative(long value, String name) {
        if (value < 0L) {
            throw new IllegalArgumentException(name + " must not be negative");
        }
        return value;
    }
}
