package dev.prime.render.terrain;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import java.util.List;
import java.util.Objects;
import java.util.function.ToIntFunction;

/** Resolves renderer-lifetime MaterialIds and packs exact u16 identity lanes. */
public final class MaterialIdResolver {
    public static final int MAX_ID = 0xffff;

    private MaterialIdResolver() {
    }

    public static int[] primitiveRecords(
            int[] resolvedMediumRecords,
            int[] localRecords,
            CompiledClusterLights lights,
            Cache cache) {
        Objects.requireNonNull(resolvedMediumRecords, "resolvedMediumRecords");
        Objects.requireNonNull(localRecords, "localRecords");
        Objects.requireNonNull(lights, "lights");
        Objects.requireNonNull(cache, "cache");
        if (resolvedMediumRecords.length != localRecords.length
                || localRecords.length % CpuSectionMesh.PRIMITIVE_WORDS != 0) {
            throw new IllegalArgumentException(
                    "MaterialId packing requires matching complete primitive records");
        }
        if (localRecords.length == 0) {
            return resolvedMediumRecords;
        }
        int[] result = resolvedMediumRecords.clone();
        for (int base = 0; base < result.length;
                base += CpuSectionMesh.PRIMITIVE_WORDS) {
            int materialId = cache.primitiveId(localRecords, base, lights);
            result[base + PrimitivePacking.MEDIUM_ID_WORD] = pack(
                    materialId == 0
                            ? resolvedMediumRecords[base + PrimitivePacking.MEDIUM_ID_WORD]
                            : 0,
                    materialId);
            if (materialId != 0) {
                // A resolved MaterialId owns immutable material and medium facts on the GPU. Keep
                // only per-triangle orientation beside the primitive; dynamic/baked ABI zero
                // identities retain their inline representation.
                result[base + 3] = PrimitivePacking.retainGeometryTintControl(
                        result[base + 3]);
                result[base + 5] = PrimitivePacking.retainGeometryFlagsControl(
                        result[base + 5]);
            }
        }
        return result;
    }

    public static int[] surfaceRelations(
            int[] resolvedMediumRelations,
            int[] localRelations,
            int primitiveCount,
            Cache cache) {
        Objects.requireNonNull(resolvedMediumRelations, "resolvedMediumRelations");
        Objects.requireNonNull(localRelations, "localRelations");
        Objects.requireNonNull(cache, "cache");
        if (resolvedMediumRelations.length != localRelations.length) {
            throw new IllegalArgumentException(
                    "MaterialId packing requires matching surface-relation tables");
        }
        if (localRelations.length == 0) {
            return resolvedMediumRelations;
        }
        SurfaceRelationTable.validate(localRelations, primitiveCount);
        int[] result = resolvedMediumRelations.clone();
        int cursor = primitiveCount;
        while (cursor < result.length) {
            int kind = localRelations[cursor] & CpuSectionMesh.SURFACE_RELATION_KIND_MASK;
            int identityWord;
            int materialId;
            if (kind == CpuSectionMesh.SURFACE_RELATION_BOUNDARY) {
                identityWord = cursor + 4;
                materialId = cache.boundaryId(localRelations, cursor);
            } else {
                identityWord = cursor + 1 + PrimitivePacking.MEDIUM_ID_WORD;
                materialId = cache.primitiveId(
                        localRelations,
                        cursor + 1,
                        CompiledClusterLights.EMPTY);
                if (materialId == 0) {
                    throw new IllegalArgumentException(
                            "Surface relation does not contain a table-backed material");
                }
            }
            result[identityWord] = pack(
                    resolvedMediumRelations[identityWord],
                    materialId);
            cursor += SurfaceRelationTable.wordsForControl(localRelations[cursor]);
        }
        return result;
    }

    public static int pack(int lowIdentity, int materialId) {
        requireId(lowIdentity, "Low identity", true);
        requireId(materialId, "MaterialId", true);
        return lowIdentity | materialId << 16;
    }

    public static int unpackMediumId(int packed) {
        return packed & MAX_ID;
    }

    public static int unpackMaterialId(int packed) {
        return packed >>> 16;
    }

    public static Cache cache(
            List<MediumKey> mediumCatalog,
            ToIntFunction<MaterialTableCandidate.Key> resolver) {
        return new Cache(mediumCatalog, resolver);
    }

    private static void requireId(int id, String name, boolean allowZero) {
        if (id < (allowZero ? 0 : 1) || id > MAX_ID) {
            throw new IllegalArgumentException(name + " exceeds its exact u16 domain");
        }
    }

    public static final class Cache {
        private final List<MediumKey> mediumCatalog;
        private final ToIntFunction<MaterialTableCandidate.Key> resolver;
        private final Long2IntOpenHashMap ids = new Long2IntOpenHashMap();

        private Cache(
                List<MediumKey> mediumCatalog,
                ToIntFunction<MaterialTableCandidate.Key> resolver) {
            this.mediumCatalog = List.copyOf(mediumCatalog);
            this.resolver = Objects.requireNonNull(resolver, "resolver");
        }

        int primitiveId(
                int[] words,
                int base,
                CompiledClusterLights lights) {
            int flagsEmitter = words[base + 5];
            if ((flagsEmitter & PrimitivePacking.DYNAMIC_TEXTURE_FLAG) != 0
                    || words[base + 6] == PrimitivePacking.CONSTANT_UV_DENSITY
                            && (words[base + 2]
                                    & PrimitivePacking.CONSTANT_UV_BAKED_MATERIAL) != 0) {
                return 0;
            }
            int textureId = PrimitivePacking.unpackTextureId(flagsEmitter);
            int emitter = PrimitivePacking.unpackEmitterIndex(flagsEmitter);
            if (emitter != PrimitivePacking.NO_EMITTER_INDEX) {
                textureId = lights.emitterMaterial(emitter).textureId();
            }
            int localMediumId = words[base + PrimitivePacking.MEDIUM_ID_WORD];
            int control = semanticControl(PrimitivePacking.unpackControl(
                    words[base + 3], flagsEmitter));
            long localKey = localKey(textureId, localMediumId, control);
            int existing = this.ids.get(localKey);
            if (existing != 0) {
                return existing;
            }
            return this.resolve(
                    localKey,
                    Objects.requireNonNull(
                            MaterialTableCandidate.primitiveKey(
                                    words,
                                    base,
                                    this.mediumCatalog,
                                    lights),
                            "eligible primitive material key"));
        }

        int boundaryId(int[] relation, int base) {
            int textureId = relation[base + 3];
            int localMediumId = relation[base + 4];
            int control = semanticControl(relation[base] >>> 8);
            long localKey = localKey(textureId, localMediumId, control);
            int existing = this.ids.get(localKey);
            if (existing != 0) {
                return existing;
            }
            return this.resolve(
                    localKey,
                    MaterialTableCandidate.boundaryKey(
                            relation, base, this.mediumCatalog));
        }

        private int resolve(long localKey, MaterialTableCandidate.Key key) {
            int materialId = this.resolver.applyAsInt(key);
            requireId(materialId, "MaterialId", false);
            this.ids.put(localKey, materialId);
            return materialId;
        }

        private static long localKey(int textureId, int localMediumId, int control) {
            if (textureId <= 0 || textureId > MAX_ID
                    || localMediumId < 0 || localMediumId > MAX_ID
                    || control < 0 || control > MAX_ID) {
                throw new IllegalArgumentException(
                        "Local material facts exceed their packed resolver key");
            }
            return Integer.toUnsignedLong(textureId)
                    | (long) localMediumId << 16
                    | (long) control << 32;
        }
    }

    private static int semanticControl(int control) {
        return control & ~(PrimitivePacking.CONTROL_TANGENT_NEGATIVE
                | PrimitivePacking.CONTROL_FRONT_FACE_ONLY);
    }
}
