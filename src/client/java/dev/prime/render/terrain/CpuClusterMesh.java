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
    private final long opaqueTriangleCount;
    private final long cutoutTriangleCount;
    private final long transmissiveTriangleCount;
    private final long opaqueMacroTriangleCount;
    private final long cutoutMacroTriangleCount;
    private final long transmissiveMacroTriangleCount;
    private final OpacityMicromapData opacityMicromap;
    private final CompiledClusterLights lights;
    private final List<CpuVoxelMesh> voxelMeshes;
    private final CpuVoxelInstances voxelInstances;
    private final List<MediumKey> mediumCatalog;
    private final Set<StaticCompatibilityIssue> compatibilityIssues;

    private CpuClusterMesh(
            List<Segment> segments,
            long opaqueTriangleCount,
            long cutoutTriangleCount,
            long transmissiveTriangleCount,
            OpacityMicromapData opacityMicromap,
            CompiledClusterLights lights,
            List<CpuVoxelMesh> voxelMeshes,
            CpuVoxelInstances voxelInstances) {
        this(
                segments,
                opaqueTriangleCount,
                cutoutTriangleCount,
                transmissiveTriangleCount,
                opacityMicromap,
                lights,
                voxelMeshes,
                voxelInstances,
                Set.of());
    }

    private CpuClusterMesh(
            List<Segment> segments,
            long opaqueTriangleCount,
            long cutoutTriangleCount,
            long transmissiveTriangleCount,
            OpacityMicromapData opacityMicromap,
            CompiledClusterLights lights,
            List<CpuVoxelMesh> voxelMeshes,
            CpuVoxelInstances voxelInstances,
            Set<StaticCompatibilityIssue> compatibilityIssues) {
        this(
                segments,
                opaqueTriangleCount,
                cutoutTriangleCount,
                transmissiveTriangleCount,
                opacityMicromap,
                lights,
                voxelMeshes,
                voxelInstances,
                List.of(),
                compatibilityIssues);
    }

    private CpuClusterMesh(
            List<Segment> segments,
            long opaqueTriangleCount,
            long cutoutTriangleCount,
            long transmissiveTriangleCount,
            OpacityMicromapData opacityMicromap,
            CompiledClusterLights lights,
            List<CpuVoxelMesh> voxelMeshes,
            CpuVoxelInstances voxelInstances,
            List<MediumKey> mediumCatalog,
            Set<StaticCompatibilityIssue> compatibilityIssues) {
        this.segments = List.copyOf(segments);
        if (opaqueTriangleCount < 0L
                || cutoutTriangleCount < 0L
                || transmissiveTriangleCount < 0L) {
            throw new IllegalArgumentException("Cluster triangle counts must not be negative");
        }
        long segmentOpaque = 0L;
        long segmentCutout = 0L;
        long segmentTransmissive = 0L;
        long segmentOpaqueMacro = 0L;
        long segmentCutoutMacro = 0L;
        long segmentTransmissiveMacro = 0L;
        for (Segment segment : this.segments) {
            segmentOpaque = Math.addExact(
                    segmentOpaque, segment.opaqueTriangleCount());
            segmentCutout = Math.addExact(
                    segmentCutout, segment.cutoutTriangleCount());
            segmentTransmissive = Math.addExact(
                    segmentTransmissive, segment.transmissiveTriangleCount());
            segmentOpaqueMacro = Math.addExact(
                    segmentOpaqueMacro, segment.opaqueMacroTriangleCount());
            segmentCutoutMacro = Math.addExact(
                    segmentCutoutMacro, segment.cutoutMacroTriangleCount());
            segmentTransmissiveMacro = Math.addExact(
                    segmentTransmissiveMacro, segment.transmissiveMacroTriangleCount());
        }
        if (segmentOpaque != opaqueTriangleCount
                || segmentCutout != cutoutTriangleCount
                || segmentTransmissive != transmissiveTriangleCount) {
            throw new IllegalArgumentException(
                    "Cluster segments disagree with aggregate triangle counts");
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
        if (opacityMicromap.triangleCount() != cutoutTriangleCount) {
            throw new IllegalArgumentException(
                    "Cluster opacity micromap does not match cutout geometry");
        }
        this.opaqueTriangleCount = opaqueTriangleCount;
        this.cutoutTriangleCount = cutoutTriangleCount;
        this.transmissiveTriangleCount = transmissiveTriangleCount;
        this.opaqueMacroTriangleCount = segmentOpaqueMacro;
        this.cutoutMacroTriangleCount = segmentCutoutMacro;
        this.transmissiveMacroTriangleCount = segmentTransmissiveMacro;
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

    static CpuClusterMesh fromEncoded(
            List<Segment> segments,
            long opaqueTriangleCount,
            long cutoutTriangleCount,
            long transmissiveTriangleCount,
            OpacityMicromapData opacityMicromap,
            CompiledClusterLights lights) {
        return fromEncoded(
                segments,
                opaqueTriangleCount,
                cutoutTriangleCount,
                transmissiveTriangleCount,
                opacityMicromap,
                lights,
                List.of(),
                CpuVoxelInstances.EMPTY);
    }

    static CpuClusterMesh fromEncoded(
            List<Segment> segments,
            long opaqueTriangleCount,
            long cutoutTriangleCount,
            long transmissiveTriangleCount,
            OpacityMicromapData opacityMicromap,
            CompiledClusterLights lights,
            List<CpuVoxelMesh> voxelMeshes,
            CpuVoxelInstances voxelInstances) {
        return new CpuClusterMesh(
                segments,
                opaqueTriangleCount,
                cutoutTriangleCount,
                transmissiveTriangleCount,
                opacityMicromap,
                lights,
                voxelMeshes,
                voxelInstances);
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
        long opaque = 0L;
        long cutout = 0L;
        long transmissive = 0L;
        for (CpuSectionMesh mesh : meshes) {
            if (mesh.isEmpty()) {
                continue;
            }
            segments.add(new Segment(
                    mesh.positions(),
                    mesh.primitiveRecords(),
                    mesh.surfaceRelationRecords(),
                    mesh.opaqueTriangleCount(),
                    mesh.cutoutTriangleCount(),
                    mesh.transmissiveTriangleCount(),
                    mesh.opaqueMacroTriangleCount(),
                    mesh.cutoutMacroTriangleCount(),
                    mesh.transmissiveMacroTriangleCount()));
            opaque = Math.addExact(opaque, mesh.opaqueTriangleCount());
            cutout = Math.addExact(cutout, mesh.cutoutTriangleCount());
            transmissive = Math.addExact(transmissive, mesh.transmissiveTriangleCount());
            opacityMicromap.append(mesh.opacityMicromap());
            if (!mesh.lights().isEmpty()) {
                lightSources.add(new CpuSectionLights.Translated(
                        mesh.lights(), 0.0F, 0.0F, 0.0F));
            }
        }
        return new CpuClusterMesh(
                segments,
                opaque,
                cutout,
                transmissive,
                opacityMicromap.build(),
                CompiledClusterLights.compile(CpuSectionLights.merge(lightSources)),
                voxelMeshes,
                voxelInstances);
    }

    public static CpuClusterMesh empty() {
        return new CpuClusterMesh(
                List.of(),
                0L,
                0L,
                0L,
                OpacityMicromapData.EMPTY,
                CompiledClusterLights.EMPTY,
                List.of(),
                CpuVoxelInstances.EMPTY);
    }

    public List<Segment> segments() {
        return this.segments;
    }

    public long opaqueTriangleCount() {
        return this.opaqueTriangleCount;
    }

    public long cutoutTriangleCount() {
        return this.cutoutTriangleCount;
    }

    public long transmissiveTriangleCount() {
        return this.transmissiveTriangleCount;
    }

    public long opaqueMacroTriangleCount() {
        return this.opaqueMacroTriangleCount;
    }

    public long cutoutMacroTriangleCount() {
        return this.cutoutMacroTriangleCount;
    }

    public long transmissiveMacroTriangleCount() {
        return this.transmissiveMacroTriangleCount;
    }

    public long opaquePrimitiveCount() {
        return primitiveCount(this.opaqueTriangleCount, this.opaqueMacroTriangleCount);
    }

    public long cutoutPrimitiveCount() {
        return primitiveCount(this.cutoutTriangleCount, this.cutoutMacroTriangleCount);
    }

    public long transmissivePrimitiveCount() {
        return primitiveCount(
                this.transmissiveTriangleCount, this.transmissiveMacroTriangleCount);
    }

    public long primitiveCount() {
        return Math.addExact(
                Math.addExact(this.opaquePrimitiveCount(), this.cutoutPrimitiveCount()),
                this.transmissivePrimitiveCount());
    }

    public long cutoutPrimitiveBase() {
        return this.opaquePrimitiveCount();
    }

    public long transmissivePrimitiveBase() {
        return Math.addExact(this.opaquePrimitiveCount(), this.cutoutPrimitiveCount());
    }

    public long opaqueMacroTriangleBase() {
        return this.opaqueTriangleCount - this.opaqueMacroTriangleCount;
    }

    public long cutoutMacroTriangleBase() {
        return this.cutoutTriangleCount - this.cutoutMacroTriangleCount;
    }

    public long transmissiveMacroTriangleBase() {
        return this.transmissiveTriangleCount - this.transmissiveMacroTriangleCount;
    }

    public long triangleCount() {
        return Math.addExact(
                Math.addExact(this.opaqueTriangleCount, this.cutoutTriangleCount),
                this.transmissiveTriangleCount);
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
                this.opaqueTriangleCount,
                this.cutoutTriangleCount,
                this.transmissiveTriangleCount,
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
                this.opaqueTriangleCount,
                this.cutoutTriangleCount,
                this.transmissiveTriangleCount,
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
                this.triangleCount(), 9L * Float.BYTES);
    }

    public long primitiveBytes() {
        return Math.multiplyExact(
                this.primitiveCount(), (long) CpuSectionMesh.PRIMITIVE_WORDS * Integer.BYTES);
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
                Math.addExact(this.primitiveCount(), tailWords), Integer.BYTES);
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
            int opaqueTriangleCount,
            int cutoutTriangleCount,
            int transmissiveTriangleCount,
            int opaqueMacroTriangleCount,
            int cutoutMacroTriangleCount,
            int transmissiveMacroTriangleCount) {
        public Segment {
            positions = Objects.requireNonNull(positions, "positions");
            primitiveRecords = Objects.requireNonNull(
                    primitiveRecords, "primitiveRecords");
            surfaceRelationRecords = Objects.requireNonNull(
                    surfaceRelationRecords, "surfaceRelationRecords");
            int triangles = Math.addExact(
                    Math.addExact(opaqueTriangleCount, cutoutTriangleCount),
                    transmissiveTriangleCount);
            if (opaqueTriangleCount < 0
                    || cutoutTriangleCount < 0
                    || transmissiveTriangleCount < 0
                    || !validMacroCount(opaqueTriangleCount, opaqueMacroTriangleCount)
                    || !validMacroCount(cutoutTriangleCount, cutoutMacroTriangleCount)
                    || !validMacroCount(
                            transmissiveTriangleCount, transmissiveMacroTriangleCount)
                    || positions.length != Math.multiplyExact(triangles, 9)
                    || primitiveRecords.length
                            != Math.multiplyExact(primitiveCount(
                                            triangles,
                                            Math.addExact(
                                                    Math.addExact(
                                                            opaqueMacroTriangleCount,
                                                            cutoutMacroTriangleCount),
                                                    transmissiveMacroTriangleCount)),
                                    CpuSectionMesh.PRIMITIVE_WORDS)) {
                throw new IllegalArgumentException("Invalid cluster mesh segment");
            }
            SurfaceRelationTable.validate(
                    surfaceRelationRecords,
                    Math.addExact(
                            Math.addExact(
                                    CpuSectionMesh.primitiveCount(
                                            opaqueTriangleCount,
                                            opaqueMacroTriangleCount),
                                    CpuSectionMesh.primitiveCount(
                                            cutoutTriangleCount,
                                            cutoutMacroTriangleCount)),
                            CpuSectionMesh.primitiveCount(
                                    transmissiveTriangleCount,
                                    transmissiveMacroTriangleCount)));
        }

        public Segment(
                float[] positions,
                int[] primitiveRecords,
                int opaqueTriangleCount,
                int cutoutTriangleCount,
                int transmissiveTriangleCount,
                int opaqueMacroTriangleCount,
                int cutoutMacroTriangleCount,
                int transmissiveMacroTriangleCount) {
            this(
                    positions,
                    primitiveRecords,
                    new int[0],
                    opaqueTriangleCount,
                    cutoutTriangleCount,
                    transmissiveTriangleCount,
                    opaqueMacroTriangleCount,
                    cutoutMacroTriangleCount,
                    transmissiveMacroTriangleCount);
        }

        public Segment(
                float[] positions,
                int[] primitiveRecords,
                int opaqueTriangleCount,
                int cutoutTriangleCount,
                int transmissiveTriangleCount) {
            this(
                    positions,
                    primitiveRecords,
                    new int[0],
                    opaqueTriangleCount,
                    cutoutTriangleCount,
                    transmissiveTriangleCount,
                    0,
                    0,
                    0);
        }

        /** Borrowed read-only backing storage; ownership remains with this segment. */
        @Override
        public float[] positions() {
            return this.positions;
        }

        /** Borrowed read-only backing storage; ownership remains with this segment. */
        @Override
        public int[] primitiveRecords() {
            return this.primitiveRecords;
        }

        /** Borrowed read-only backing storage; empty means every primitive is SINGLE. */
        @Override
        public int[] surfaceRelationRecords() {
            return this.surfaceRelationRecords;
        }

        public int triangleCount() {
            return Math.addExact(
                    Math.addExact(this.opaqueTriangleCount, this.cutoutTriangleCount),
                    this.transmissiveTriangleCount);
        }

        public int opaquePrimitiveCount() {
            return CpuSectionMesh.primitiveCount(
                    this.opaqueTriangleCount, this.opaqueMacroTriangleCount);
        }

        public int cutoutPrimitiveCount() {
            return CpuSectionMesh.primitiveCount(
                    this.cutoutTriangleCount, this.cutoutMacroTriangleCount);
        }

        public int transmissivePrimitiveCount() {
            return CpuSectionMesh.primitiveCount(
                    this.transmissiveTriangleCount, this.transmissiveMacroTriangleCount);
        }
    }

    private static long primitiveCount(long triangleCount, long macroTriangleCount) {
        return Math.subtractExact(triangleCount, macroTriangleCount / 2L);
    }

    private static boolean validMacroCount(int triangleCount, int macroTriangleCount) {
        return macroTriangleCount >= 0
                && macroTriangleCount <= triangleCount
                && (macroTriangleCount & 1) == 0;
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
