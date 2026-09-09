// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

package dev.prime.render.terrain;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * One logically immutable BLAS payload backed by bounded CPU segments.
 *
 * <p>Segment arrays are ownership-transferred and exposed only as borrowed read-only storage. The
 * representation deliberately avoids joining them into another full-size CPU mesh.
 */
public final class CpuClusterMesh {
    private final List<CpuMeshSegment> segments;
    private final TriangleLayout triangleLayout;
    private final OpacityMicromapData opacityMicromap;
    private final CompiledClusterLights lights;
    private final List<CpuVoxelMesh> voxelMeshes;
    private final CpuVoxelInstances voxelInstances;
    private final List<MediumKey> mediumCatalog;
    private final Set<StaticCompatibilityIssue> compatibilityIssues;

    private CpuClusterMesh(
            List<CpuMeshSegment> segments,
            OpacityMicromapData opacityMicromap,
            CompiledClusterLights lights,
            List<CpuVoxelMesh> voxelMeshes,
            CpuVoxelInstances voxelInstances) {
        this(
                segments,
                opacityMicromap,
                lights,
                voxelMeshes,
                voxelInstances,
                List.of(),
                Set.of());
    }

    private CpuClusterMesh(
            List<CpuMeshSegment> segments,
            OpacityMicromapData opacityMicromap,
            CompiledClusterLights lights,
            List<CpuVoxelMesh> voxelMeshes,
            CpuVoxelInstances voxelInstances,
            List<MediumKey> mediumCatalog,
        Set<StaticCompatibilityIssue> compatibilityIssues) {
        this.segments = List.copyOf(segments);
        TriangleLayout combined = TriangleLayout.triangles(0L, 0L, 0L);
        for (CpuMeshSegment segment : this.segments) {
            combined = combined.plus(segment.triangleLayout());
        }
        Objects.requireNonNull(opacityMicromap, "opacityMicromap");
        Objects.requireNonNull(lights, "lights");
        this.voxelMeshes = List.copyOf(voxelMeshes);
        this.voxelInstances = Objects.requireNonNull(
                voxelInstances, "voxelInstances");
        this.mediumCatalog = List.copyOf(mediumCatalog);
        if (new java.util.HashSet<>(this.mediumCatalog).size()
                != this.mediumCatalog.size()) {
            throw new IllegalArgumentException(
                    "Cluster medium catalog contains duplicate identities");
        }
        this.compatibilityIssues = Set.copyOf(compatibilityIssues);
        if (opacityMicromap.triangleCount() != combined.cutoutTriangleCount()) {
            throw new IllegalArgumentException(
                    "Cluster opacity micromap does not match cutout geometry");
        }
        this.triangleLayout = combined;
        requireMacroTail(this.segments, 0);
        requireMacroTail(this.segments, 1);
        requireMacroTail(this.segments, 2);
        this.opacityMicromap = opacityMicromap;
        this.lights = lights;
        for (int meshIndex : this.voxelInstances.meshIndices()) {
            if (meshIndex < 0 || meshIndex >= this.voxelMeshes.size()) {
                throw new IllegalArgumentException(
                        "Voxel-surface instance references an invalid mesh");
            }
        }
        if (this.voxelMeshes.isEmpty() != (this.voxelInstances.count() == 0)) {
            throw new IllegalArgumentException(
                    "Reusable voxel meshes and their instances must be present together");
        }
    }

    public static CpuClusterMesh fromSegments(List<CpuSectionMesh> meshes) {
        return fromSegments(meshes, List.of(), CpuVoxelInstances.EMPTY);
    }

    /** Dynamic prototypes and unique fallbacks share one explicit TLAS-instance stream. */
    public static CpuClusterMesh fromInstances(
            List<CpuVoxelMesh> meshes, CpuVoxelInstances instances) {
        return new CpuClusterMesh(
                List.of(),
                OpacityMicromapData.EMPTY,
                CompiledClusterLights.EMPTY,
                meshes,
                instances);
    }

    static CpuClusterMesh fromSegments(
            List<CpuSectionMesh> meshes,
            List<CpuVoxelMesh> voxelMeshes,
            CpuVoxelInstances voxelInstances) {
        ArrayList<CpuMeshSegment> segments = new ArrayList<>(meshes.size());
        ArrayList<CpuSectionLights.Translated> lightSources = new ArrayList<>();
        OpacityMicromapData.Builder opacityMicromap = new OpacityMicromapData.Builder();
        for (CpuSectionMesh mesh : meshes) {
            if (mesh.isEmpty()) {
                continue;
            }
            segments.add(mesh.geometry());
            opacityMicromap.append(mesh.opacityMicromap());
            if (!mesh.lights().isEmpty()) {
                lightSources.add(new CpuSectionLights.Translated(
                        mesh.lights(), 0.0F, 0.0F, 0.0F));
            }
        }
        return new CpuClusterMesh(
                segments,
                opacityMicromap.build(),
                CompiledClusterLights.compile(CpuSectionLights.merge(lightSources)),
                voxelMeshes,
                voxelInstances);
    }

    public static CpuClusterMesh empty() {
        return new CpuClusterMesh(
                List.of(),
                OpacityMicromapData.EMPTY,
                CompiledClusterLights.EMPTY,
                List.of(),
                CpuVoxelInstances.EMPTY);
    }

    public List<CpuMeshSegment> segments() {
        return this.segments;
    }

    public TriangleLayout triangleLayout() {
        return this.triangleLayout;
    }

    public OpacityMicromapData opacityMicromap() {
        return this.opacityMicromap;
    }

    public CompiledClusterLights lights() {
        return this.lights;
    }

    public List<CpuVoxelMesh> voxelMeshes() {
        return this.voxelMeshes;
    }

    public CpuVoxelInstances voxelInstances() {
        return this.voxelInstances;
    }

    /** Cluster-local MediumId n names {@code mediumCatalog().get(n - 1)}. */
    public List<MediumKey> mediumCatalog() {
        return this.mediumCatalog;
    }

    public Set<StaticCompatibilityIssue> compatibilityIssues() {
        return this.compatibilityIssues;
    }

    CpuClusterMesh withCompatibilityIssues(Set<StaticCompatibilityIssue> issues) {
        Set<StaticCompatibilityIssue> copied = Set.copyOf(issues);
        if (this.compatibilityIssues.equals(copied)) {
            return this;
        }
        return new CpuClusterMesh(
                this.segments,
                this.opacityMicromap,
                this.lights,
                this.voxelMeshes,
                this.voxelInstances,
                this.mediumCatalog,
                copied);
    }

    CpuClusterMesh withMediumCatalog(List<MediumKey> catalog) {
        List<MediumKey> copied = List.copyOf(catalog);
        if (this.mediumCatalog.equals(copied)) {
            return this;
        }
        return new CpuClusterMesh(
                this.segments,
                this.opacityMicromap,
                this.lights,
                this.voxelMeshes,
                this.voxelInstances,
                copied,
                this.compatibilityIssues);
    }

    public boolean isEmpty() {
        return this.segments.isEmpty() && this.voxelInstances.count() == 0;
    }

    public long positionBytes() {
        return Math.multiplyExact(
                this.triangleLayout.triangleCount(), 9L * Float.BYTES);
    }

    public long primitiveBytes() {
        return Math.multiplyExact(
                this.triangleLayout.primitiveCount(),
                (long) CpuSectionMesh.PRIMITIVE_WORDS * Integer.BYTES);
    }

    public long surfaceRelationBytes() {
        long words = 0L;
        for (CpuMeshSegment segment : this.segments) {
            words = Math.addExact(words, segment.surfaceRelationRecords().length);
        }
        return Math.multiplyExact(words, Integer.BYTES);
    }

    public long byteSize() {
        long result = Math.addExact(
                Math.addExact(this.positionBytes(), this.primitiveBytes()),
                Math.addExact(
                        this.surfaceRelationBytes(),
                        Math.addExact(
                                this.opacityMicromap.byteSize(), this.lights.byteSize())));
        for (CpuVoxelMesh voxelMesh : this.voxelMeshes) {
            result = Math.addExact(result, voxelMesh.byteSize());
        }
        result = Math.addExact(
                result,
                Math.multiplyExact(
                        (long) this.voxelInstances.count(),
                        2L * Integer.BYTES + 3L * Float.BYTES));
        result = Math.addExact(result, this.voxelInstances.motionByteSize());
        return result;
    }

    private static void requireMacroTail(List<CpuMeshSegment> segments, int category) {
        boolean macroStarted = false;
        for (CpuMeshSegment segment : segments) {
            int triangleCount = switch (category) {
                case 0 -> segment.opaqueTriangleCount();
                case 1 -> segment.cutoutTriangleCount();
                default -> segment.transmissiveTriangleCount();
            };
            int macroTriangleCount = switch (category) {
                case 0 -> segment.opaqueMacroTriangleCount();
                case 1 -> segment.cutoutMacroTriangleCount();
                default -> segment.transmissiveMacroTriangleCount();
            };
            if (macroStarted && triangleCount != macroTriangleCount) {
                throw new IllegalArgumentException(
                        "Macro triangles must remain at the tail of each geometry partition");
            }
            macroStarted |= macroTriangleCount != 0;
        }
    }
}
