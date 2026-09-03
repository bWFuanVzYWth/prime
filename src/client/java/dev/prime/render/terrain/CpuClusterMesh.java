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
    private final List<Segment> segments;
    private final TriangleLayout triangleLayout;
    private final OpacityMicromapData opacityMicromap;
    private final CompiledClusterLights lights;
    private final List<CpuVoxelMesh> voxelMeshes;
    private final CpuVoxelInstances voxelInstances;
    private final List<MediumKey> mediumCatalog;
    private final Set<StaticCompatibilityIssue> compatibilityIssues;

    private CpuClusterMesh(
            List<Segment> segments,
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
            List<Segment> segments,
            OpacityMicromapData opacityMicromap,
            CompiledClusterLights lights,
            List<CpuVoxelMesh> voxelMeshes,
            CpuVoxelInstances voxelInstances,
            List<MediumKey> mediumCatalog,
        Set<StaticCompatibilityIssue> compatibilityIssues) {
        this.segments = List.copyOf(segments);
        TriangleLayout combined = TriangleLayout.triangles(0L, 0L, 0L);
        for (Segment segment : this.segments) {
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

    static CpuClusterMesh fromSegments(
            List<CpuSectionMesh> meshes,
            List<CpuVoxelMesh> voxelMeshes,
            CpuVoxelInstances voxelInstances) {
        ArrayList<Segment> segments = new ArrayList<>(meshes.size());
        ArrayList<CpuSectionLights.Translated> lightSources = new ArrayList<>();
        OpacityMicromapData.Builder opacityMicromap = new OpacityMicromapData.Builder();
        for (CpuSectionMesh mesh : meshes) {
            if (mesh.isEmpty()) {
                continue;
            }
            segments.add(new Segment(
                    mesh.positions(),
                    mesh.primitiveRecords(),
                    mesh.surfaceRelationRecords(),
                    mesh.triangleLayout()));
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

    public List<Segment> segments() {
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

    public boolean hasSurfaceRelations() {
        for (Segment segment : this.segments) {
            if (segment.surfaceRelationRecords().length != 0) {
                return true;
            }
        }
        return false;
    }

    public long surfaceRelationBytes() {
        long tailWords = 0L;
        boolean any = false;
        for (Segment segment : this.segments) {
            int[] records = segment.surfaceRelationRecords();
            if (records.length == 0) {
                continue;
            }
            any = true;
            int primitiveCount = segment.opaquePrimitiveCount()
                    + segment.cutoutPrimitiveCount()
                    + segment.transmissivePrimitiveCount();
            tailWords = Math.addExact(
                    tailWords, (long) records.length - primitiveCount);
        }
        if (!any) {
            return 0L;
        }
        return Math.multiplyExact(
                Math.addExact(this.triangleLayout.primitiveCount(), tailWords), Integer.BYTES);
    }

    /** Global primitive-order relation table used by the single cluster BLAS section record. */
    public int[] surfaceRelationRecords() {
        ArrayList<int[]> opaque = new ArrayList<>();
        ArrayList<int[]> cutout = new ArrayList<>();
        ArrayList<int[]> transmissive = new ArrayList<>();
        for (Segment segment : this.segments) {
            int primitiveCount = segment.opaquePrimitiveCount()
                    + segment.cutoutPrimitiveCount()
                    + segment.transmissivePrimitiveCount();
            SurfaceRelationTable.appendRange(
                    opaque,
                    segment.surfaceRelationRecords(),
                    primitiveCount,
                    0,
                    segment.opaquePrimitiveCount());
            SurfaceRelationTable.appendRange(
                    cutout,
                    segment.surfaceRelationRecords(),
                    primitiveCount,
                    segment.opaquePrimitiveCount(),
                    segment.cutoutPrimitiveCount());
            SurfaceRelationTable.appendRange(
                    transmissive,
                    segment.surfaceRelationRecords(),
                    primitiveCount,
                    segment.opaquePrimitiveCount() + segment.cutoutPrimitiveCount(),
                    segment.transmissivePrimitiveCount());
        }
        ArrayList<int[]> records = new ArrayList<>(
                opaque.size() + cutout.size() + transmissive.size());
        records.addAll(opaque);
        records.addAll(cutout);
        records.addAll(transmissive);
        return SurfaceRelationTable.encode(records);
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
        return result;
    }

    /**
     * An ownership-transferred CPU storage segment; segmentation does not create another BLAS or
     * TLAS instance.
     */
    public record Segment(
            float[] positions,
            int[] primitiveRecords,
            int[] surfaceRelationRecords,
            TriangleLayout triangleLayout) {
        public Segment {
            positions = Objects.requireNonNull(positions, "positions");
            primitiveRecords = Objects.requireNonNull(
                    primitiveRecords, "primitiveRecords");
            surfaceRelationRecords = Objects.requireNonNull(
                    surfaceRelationRecords, "surfaceRelationRecords");
            triangleLayout = Objects.requireNonNull(triangleLayout, "triangleLayout");
            if (positions.length
                            != Math.multiplyExact(
                                    Math.toIntExact(triangleLayout.triangleCount()), 9)
                    || primitiveRecords.length
                            != Math.multiplyExact(
                                    Math.toIntExact(triangleLayout.primitiveCount()),
                                    CpuSectionMesh.PRIMITIVE_WORDS)) {
                throw new IllegalArgumentException("Invalid cluster mesh segment");
            }
            SurfaceRelationTable.validate(
                    surfaceRelationRecords,
                    Math.toIntExact(triangleLayout.primitiveCount()));
        }

        public int triangleCount() {
            return Math.toIntExact(this.triangleLayout.triangleCount());
        }

        public int opaqueTriangleCount() {
            return Math.toIntExact(this.triangleLayout.opaqueTriangleCount());
        }

        public int cutoutTriangleCount() {
            return Math.toIntExact(this.triangleLayout.cutoutTriangleCount());
        }

        public int transmissiveTriangleCount() {
            return Math.toIntExact(this.triangleLayout.transmissiveTriangleCount());
        }

        public int opaqueMacroTriangleCount() {
            return Math.toIntExact(this.triangleLayout.opaqueMacroTriangleCount());
        }

        public int cutoutMacroTriangleCount() {
            return Math.toIntExact(this.triangleLayout.cutoutMacroTriangleCount());
        }

        public int transmissiveMacroTriangleCount() {
            return Math.toIntExact(this.triangleLayout.transmissiveMacroTriangleCount());
        }

        public int opaquePrimitiveCount() {
            return Math.toIntExact(this.triangleLayout.opaquePrimitiveCount());
        }

        public int cutoutPrimitiveCount() {
            return Math.toIntExact(this.triangleLayout.cutoutPrimitiveCount());
        }

        public int transmissivePrimitiveCount() {
            return Math.toIntExact(this.triangleLayout.transmissivePrimitiveCount());
        }

    }

    private static void requireMacroTail(List<Segment> segments, int category) {
        boolean macroStarted = false;
        for (Segment segment : segments) {
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
