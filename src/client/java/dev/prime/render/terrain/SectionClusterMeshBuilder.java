package dev.prime.render.terrain;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Merges Section-local output into bounded CPU segments for one cluster BLAS. */
final class SectionClusterMeshBuilder {
    private static final int POSITION_WORDS_PER_TRIANGLE = 9;
    private static final int PRIMITIVE_WORDS_PER_TRIANGLE = CpuSectionMesh.PRIMITIVE_WORDS;
    private static final int FLAGS_EMITTER_WORD = 5;

    private final int clusterX;
    private final int clusterY;
    private final int clusterZ;
    private final int segmentTriangleTarget;
    private final int maxOpacity2StateSubdivisionLevel;
    private final int maxOpacity4StateSubdivisionLevel;
    private final boolean voxelSurfacesEnabled;
    private final float voxelSurfaceMaximumHeight;
    private final ClusterTranslationWork work;
    private final List<Entry> entries = new ArrayList<>(SectionCluster.SECTION_COUNT);
    private final ArrayList<MergeFace> mergeFaces = new ArrayList<>();
    private final ArrayList<CpuSectionMesh> segments = new ArrayList<>();
    private final boolean[] populatedSections = new boolean[SectionCluster.SECTION_COUNT];
    private int opaqueTriangleCount;
    private int cutoutTriangleCount;
    private int transmissiveTriangleCount;
    private int opaqueMacroTriangleCount;
    private int cutoutMacroTriangleCount;
    private int transmissiveMacroTriangleCount;
    private int emitterCount;
    private int totalEmitterCount;
    private long inputBytes;
    private boolean built;

    SectionClusterMeshBuilder(int clusterX, int clusterY, int clusterZ) {
        this(
                clusterX,
                clusterY,
                clusterZ,
                TerrainMemoryBudget.TARGET_SEGMENT_TRIANGLES,
                OpacityMicromapData.SUBDIVISION_LEVEL + 2,
                false,
                VoxelSurfaceSettings.BASE_HEIGHT);
    }

    SectionClusterMeshBuilder(
            int clusterX, int clusterY, int clusterZ, int segmentTriangleTarget) {
        this(
                clusterX,
                clusterY,
                clusterZ,
                segmentTriangleTarget,
                OpacityMicromapData.SUBDIVISION_LEVEL + 2,
                false,
                VoxelSurfaceSettings.BASE_HEIGHT);
    }

    SectionClusterMeshBuilder(
            int clusterX,
            int clusterY,
            int clusterZ,
            int segmentTriangleTarget,
            int maxOpacityMicromapSubdivisionLevel) {
        this(
                clusterX,
                clusterY,
                clusterZ,
                segmentTriangleTarget,
                maxOpacityMicromapSubdivisionLevel,
                false,
                VoxelSurfaceSettings.BASE_HEIGHT);
    }

    SectionClusterMeshBuilder(
            int clusterX,
            int clusterY,
            int clusterZ,
            int segmentTriangleTarget,
            int maxOpacityMicromapSubdivisionLevel,
            boolean voxelSurfacesEnabled,
            float voxelSurfaceMaximumHeight) {
        this(
                clusterX,
                clusterY,
                clusterZ,
                segmentTriangleTarget,
                maxOpacityMicromapSubdivisionLevel,
                maxOpacityMicromapSubdivisionLevel,
                voxelSurfacesEnabled,
                voxelSurfaceMaximumHeight);
    }

    SectionClusterMeshBuilder(
            int clusterX,
            int clusterY,
            int clusterZ,
            int segmentTriangleTarget,
            int maxOpacity2StateSubdivisionLevel,
            int maxOpacity4StateSubdivisionLevel,
            boolean voxelSurfacesEnabled,
            float voxelSurfaceMaximumHeight) {
        this(
                clusterX,
                clusterY,
                clusterZ,
                segmentTriangleTarget,
                maxOpacity2StateSubdivisionLevel,
                maxOpacity4StateSubdivisionLevel,
                voxelSurfacesEnabled,
                voxelSurfaceMaximumHeight,
                new ClusterTranslationWork(ClusterTranslationControl.UNINTERRUPTIBLE));
    }

    SectionClusterMeshBuilder(
            int clusterX,
            int clusterY,
            int clusterZ,
            int segmentTriangleTarget,
            int maxOpacity2StateSubdivisionLevel,
            int maxOpacity4StateSubdivisionLevel,
            boolean voxelSurfacesEnabled,
            float voxelSurfaceMaximumHeight,
            ClusterTranslationWork work) {
        if (SectionCluster.origin(clusterX) != clusterX
                || SectionCluster.origin(clusterY) != clusterY
                || SectionCluster.origin(clusterZ) != clusterZ) {
            throw new IllegalArgumentException("Cluster origin must be aligned to four Sections");
        }
        this.clusterX = clusterX;
        this.clusterY = clusterY;
        this.clusterZ = clusterZ;
        if (segmentTriangleTarget <= 0) {
            throw new IllegalArgumentException("Cluster segment triangle target must be positive");
        }
        this.segmentTriangleTarget = segmentTriangleTarget;
        this.maxOpacity2StateSubdivisionLevel = maxOpacity2StateSubdivisionLevel;
        this.maxOpacity4StateSubdivisionLevel = maxOpacity4StateSubdivisionLevel;
        this.voxelSurfacesEnabled = voxelSurfacesEnabled;
        if (!Float.isFinite(voxelSurfaceMaximumHeight)
                || voxelSurfaceMaximumHeight < 0.0F) {
            throw new IllegalArgumentException(
                    "Voxel-surface maximum height must be finite and nonnegative");
        }
        this.voxelSurfaceMaximumHeight = voxelSurfaceMaximumHeight;
        this.work = Objects.requireNonNull(work, "work");
    }

    void add(int sectionX, int sectionY, int sectionZ, List<CpuSectionMesh> meshes) {
        this.add(sectionX, sectionY, sectionZ, new CpuSectionGeometry(meshes, List.of()));
    }

    void add(
            int sectionX,
            int sectionY,
            int sectionZ,
            CpuSectionGeometry geometry) {
        if (this.built) {
            throw new IllegalStateException("Cluster mesh was already built");
        }
        Objects.requireNonNull(geometry, "geometry");
        for (CpuSectionMesh mesh : geometry.meshes()) {
            Objects.requireNonNull(mesh, "mesh");
        }
        long clusterKey = net.minecraft.core.SectionPos.asLong(
                this.clusterX, this.clusterY, this.clusterZ);
        if (!SectionCluster.contains(clusterKey, sectionX, sectionY, sectionZ)) {
            throw new IllegalArgumentException("Section does not belong to this cluster");
        }
        int localIndex = (sectionX - this.clusterX)
                + (sectionY - this.clusterY) * SectionCluster.SECTION_SIZE
                + (sectionZ - this.clusterZ)
                        * SectionCluster.SECTION_SIZE
                        * SectionCluster.SECTION_SIZE;
        if (this.populatedSections[localIndex]) {
            throw new IllegalArgumentException("Section was added to its cluster more than once");
        }
        this.populatedSections[localIndex] = true;
        int translateX = (sectionX - this.clusterX) * 16;
        int translateY = (sectionY - this.clusterY) * 16;
        int translateZ = (sectionZ - this.clusterZ) * 16;
        for (MergeFace face : geometry.mergeFaces()) {
            this.work.step();
            this.mergeFaces.add(face.translated(translateX, translateY, translateZ));
        }
        for (CpuSectionMesh mesh : geometry.meshes()) {
            this.work.step();
            if (!mesh.isEmpty()) {
                this.addPart(sectionX, sectionY, sectionZ, mesh);
            }
        }
    }

    private void addPart(int sectionX, int sectionY, int sectionZ, CpuSectionMesh mesh) {
        int triangleCount = Math.addExact(
                Math.addExact(this.opaqueTriangleCount, this.cutoutTriangleCount),
                this.transmissiveTriangleCount);
        if (TerrainMemoryBudget.startsNewSegment(
                this.inputBytes, triangleCount, mesh, this.segmentTriangleTarget)) {
            this.finishSegment();
        }
        requireMacroTail(
                this.opaqueMacroTriangleCount,
                mesh.opaqueTriangleCount(),
                mesh.opaqueMacroTriangleCount());
        requireMacroTail(
                this.cutoutMacroTriangleCount,
                mesh.cutoutTriangleCount(),
                mesh.cutoutMacroTriangleCount());
        requireMacroTail(
                this.transmissiveMacroTriangleCount,
                mesh.transmissiveTriangleCount(),
                mesh.transmissiveMacroTriangleCount());
        int lightOffset = this.totalEmitterCount;
        this.entries.add(new Entry(
                sectionX,
                sectionY,
                sectionZ,
                mesh,
                lightOffset));
        this.opaqueTriangleCount = Math.addExact(
                this.opaqueTriangleCount, mesh.opaqueTriangleCount());
        this.cutoutTriangleCount = Math.addExact(
                this.cutoutTriangleCount, mesh.cutoutTriangleCount());
        this.transmissiveTriangleCount = Math.addExact(
                this.transmissiveTriangleCount, mesh.transmissiveTriangleCount());
        this.opaqueMacroTriangleCount = Math.addExact(
                this.opaqueMacroTriangleCount, mesh.opaqueMacroTriangleCount());
        this.cutoutMacroTriangleCount = Math.addExact(
                this.cutoutMacroTriangleCount, mesh.cutoutMacroTriangleCount());
        this.transmissiveMacroTriangleCount = Math.addExact(
                this.transmissiveMacroTriangleCount,
                mesh.transmissiveMacroTriangleCount());
        this.emitterCount = Math.addExact(this.emitterCount, mesh.lights().emitterCount());
        this.totalEmitterCount = Math.addExact(
                this.totalEmitterCount, mesh.lights().emitterCount());
        this.inputBytes = Math.addExact(this.inputBytes, mesh.byteSize());
    }

    CpuClusterMesh build() {
        if (this.built) {
            throw new IllegalStateException("Cluster mesh was already built");
        }
        this.work.checkpoint();
        MergedFaceMeshBuilder mergedFaces = new MergedFaceMeshBuilder(
                        this.segmentTriangleTarget,
                        this.maxOpacity2StateSubdivisionLevel,
                        this.maxOpacity4StateSubdivisionLevel,
                        this.voxelSurfacesEnabled,
                        this.voxelSurfaceMaximumHeight,
                        this.work);
        for (CpuSectionMesh mesh : mergedFaces.build(this.mergeFaces)) {
            this.work.step();
            this.addPart(this.clusterX, this.clusterY, this.clusterZ, mesh);
        }
        this.built = true;
        this.finishSegment();
        CpuClusterMesh result = CpuClusterMesh.fromSegments(
                this.segments,
                mergedFaces.voxelMeshes(),
                mergedFaces.voxelInstances());
        if (result.lights().emitterCount() != this.totalEmitterCount) {
            throw new IllegalStateException(
                    "Merged cluster light indices disagree with its light tree");
        }
        this.work.checkpoint();
        return result;
    }

    private void finishSegment() {
        if (this.entries.isEmpty()) {
            return;
        }
        this.segments.add(this.buildSegment());
        this.entries.clear();
        this.opaqueTriangleCount = 0;
        this.cutoutTriangleCount = 0;
        this.transmissiveTriangleCount = 0;
        this.opaqueMacroTriangleCount = 0;
        this.cutoutMacroTriangleCount = 0;
        this.transmissiveMacroTriangleCount = 0;
        this.emitterCount = 0;
        this.inputBytes = 0L;
    }

    private CpuSectionMesh buildSegment() {
        int triangleCount = Math.addExact(
                Math.addExact(this.opaqueTriangleCount, this.cutoutTriangleCount),
                this.transmissiveTriangleCount);
        float[] positions = new float[Math.multiplyExact(
                triangleCount, POSITION_WORDS_PER_TRIANGLE)];
        int opaquePrimitiveCount = CpuSectionMesh.primitiveCount(
                this.opaqueTriangleCount, this.opaqueMacroTriangleCount);
        int cutoutPrimitiveCount = CpuSectionMesh.primitiveCount(
                this.cutoutTriangleCount, this.cutoutMacroTriangleCount);
        int transmissivePrimitiveCount = CpuSectionMesh.primitiveCount(
                this.transmissiveTriangleCount, this.transmissiveMacroTriangleCount);
        int primitiveCount = Math.addExact(
                Math.addExact(opaquePrimitiveCount, cutoutPrimitiveCount),
                transmissivePrimitiveCount);
        int[] primitives = new int[Math.multiplyExact(
                primitiveCount, PRIMITIVE_WORDS_PER_TRIANGLE)];
        ArrayList<int[]> opaqueRelations = new ArrayList<>(opaquePrimitiveCount);
        ArrayList<int[]> cutoutRelations = new ArrayList<>(cutoutPrimitiveCount);
        ArrayList<int[]> transmissiveRelations =
                new ArrayList<>(transmissivePrimitiveCount);
        int opaquePositionCursor = 0;
        int opaquePrimitiveCursor = 0;
        int cutoutPositionCursor = Math.multiplyExact(
                this.opaqueTriangleCount, POSITION_WORDS_PER_TRIANGLE);
        int cutoutPrimitiveCursor = Math.multiplyExact(
                opaquePrimitiveCount, PRIMITIVE_WORDS_PER_TRIANGLE);
        int transmissivePositionCursor = Math.multiplyExact(
                Math.addExact(this.opaqueTriangleCount, this.cutoutTriangleCount),
                POSITION_WORDS_PER_TRIANGLE);
        int transmissivePrimitiveCursor = Math.multiplyExact(
                Math.addExact(opaquePrimitiveCount, cutoutPrimitiveCount),
                PRIMITIVE_WORDS_PER_TRIANGLE);
        ArrayList<CpuSectionLights.Translated> lightSources = new ArrayList<>();
        OpacityMicromapData.Builder opacityMicromap = new OpacityMicromapData.Builder();

        for (Entry entry : this.entries) {
            this.work.step();
            CpuSectionMesh mesh = entry.mesh;
            float translateX = (entry.sectionX - this.clusterX) * 16.0F;
            float translateY = (entry.sectionY - this.clusterY) * 16.0F;
            float translateZ = (entry.sectionZ - this.clusterZ) * 16.0F;
            int opaquePositionWords = Math.multiplyExact(
                    mesh.opaqueTriangleCount(), POSITION_WORDS_PER_TRIANGLE);
            int cutoutPositionWords = Math.multiplyExact(
                    mesh.cutoutTriangleCount(), POSITION_WORDS_PER_TRIANGLE);
            int transmissivePositionWords = Math.multiplyExact(
                    mesh.transmissiveTriangleCount(), POSITION_WORDS_PER_TRIANGLE);
            int opaquePrimitiveWords = Math.multiplyExact(
                    mesh.opaquePrimitiveCount(), PRIMITIVE_WORDS_PER_TRIANGLE);
            int cutoutPrimitiveWords = Math.multiplyExact(
                    mesh.cutoutPrimitiveCount(), PRIMITIVE_WORDS_PER_TRIANGLE);
            int transmissivePrimitiveWords = Math.multiplyExact(
                    mesh.transmissivePrimitiveCount(), PRIMITIVE_WORDS_PER_TRIANGLE);

            copyTranslatedPositions(
                    mesh.positions(),
                    0,
                    positions,
                    opaquePositionCursor,
                    opaquePositionWords,
                    translateX,
                    translateY,
                    translateZ,
                    this.work);
            copyTranslatedPositions(
                    mesh.positions(),
                    opaquePositionWords,
                    positions,
                    cutoutPositionCursor,
                    cutoutPositionWords,
                    translateX,
                    translateY,
                    translateZ,
                    this.work);
            copyPrimitives(
                    mesh.primitiveRecords(),
                    0,
                    primitives,
                    opaquePrimitiveCursor,
                    opaquePrimitiveWords,
                    entry.lightOffset,
                    this.work);
            copyPrimitives(
                    mesh.primitiveRecords(),
                    opaquePrimitiveWords,
                    primitives,
                    cutoutPrimitiveCursor,
                    cutoutPrimitiveWords,
                    entry.lightOffset,
                    this.work);
            copyTranslatedPositions(
                    mesh.positions(),
                    opaquePositionWords + cutoutPositionWords,
                    positions,
                    transmissivePositionCursor,
                    transmissivePositionWords,
                    translateX,
                    translateY,
                    translateZ,
                    this.work);
            copyPrimitives(
                    mesh.primitiveRecords(),
                    opaquePrimitiveWords + cutoutPrimitiveWords,
                    primitives,
                    transmissivePrimitiveCursor,
                    transmissivePrimitiveWords,
                    entry.lightOffset,
                    this.work);
            int sourcePrimitiveCount = mesh.primitiveCount();
            SurfaceRelationTable.appendRange(
                    opaqueRelations,
                    mesh.surfaceRelationRecords(),
                    sourcePrimitiveCount,
                    0,
                    mesh.opaquePrimitiveCount());
            SurfaceRelationTable.appendRange(
                    cutoutRelations,
                    mesh.surfaceRelationRecords(),
                    sourcePrimitiveCount,
                    mesh.opaquePrimitiveCount(),
                    mesh.cutoutPrimitiveCount());
            SurfaceRelationTable.appendRange(
                    transmissiveRelations,
                    mesh.surfaceRelationRecords(),
                    sourcePrimitiveCount,
                    mesh.opaquePrimitiveCount() + mesh.cutoutPrimitiveCount(),
                    mesh.transmissivePrimitiveCount());

            opaquePositionCursor += opaquePositionWords;
            opaquePrimitiveCursor += opaquePrimitiveWords;
            cutoutPositionCursor += cutoutPositionWords;
            cutoutPrimitiveCursor += cutoutPrimitiveWords;
            transmissivePositionCursor += transmissivePositionWords;
            transmissivePrimitiveCursor += transmissivePrimitiveWords;
            opacityMicromap.append(mesh.opacityMicromap());
            if (!mesh.lights().isEmpty()) {
                lightSources.add(new CpuSectionLights.Translated(
                        mesh.lights(), translateX, translateY, translateZ));
            }
        }

        CpuSectionLights lights = CpuSectionLights.merge(lightSources);
        if (lights.emitterCount() != this.emitterCount) {
            throw new IllegalStateException("Merged cluster light indices disagree with its light tree");
        }
        ArrayList<int[]> relations = new ArrayList<>(primitiveCount);
        relations.addAll(opaqueRelations);
        relations.addAll(cutoutRelations);
        relations.addAll(transmissiveRelations);
        CpuSectionMesh result = new CpuSectionMesh(
                positions,
                primitives,
                SurfaceRelationTable.encode(relations),
                this.opaqueTriangleCount,
                this.cutoutTriangleCount,
                this.transmissiveTriangleCount,
                this.opaqueMacroTriangleCount,
                this.cutoutMacroTriangleCount,
                this.transmissiveMacroTriangleCount,
                opacityMicromap.build(),
                lights);
        return result;
    }

    private static void requireMacroTail(
            int accumulatedMacroTriangles,
            int triangleCount,
            int macroTriangleCount) {
        if (accumulatedMacroTriangles != 0 && triangleCount != macroTriangleCount) {
            throw new IllegalArgumentException(
                    "Macro triangles must remain at the tail of each geometry partition");
        }
    }

    private static void copyTranslatedPositions(
            float[] source,
            int sourceOffset,
            float[] destination,
            int destinationOffset,
            int wordCount,
            float translateX,
            float translateY,
            float translateZ,
            ClusterTranslationWork work) {
        int sourceEnd = sourceOffset + wordCount;
        while (sourceOffset < sourceEnd) {
            work.step();
            destination[destinationOffset++] = source[sourceOffset++] + translateX;
            destination[destinationOffset++] = source[sourceOffset++] + translateY;
            destination[destinationOffset++] = source[sourceOffset++] + translateZ;
        }
    }

    private static void copyPrimitives(
            int[] source,
            int sourceOffset,
            int[] destination,
            int destinationOffset,
            int wordCount,
            int lightOffset,
            ClusterTranslationWork work) {
        System.arraycopy(source, sourceOffset, destination, destinationOffset, wordCount);
        int destinationEnd = destinationOffset + wordCount;
        for (int record = destinationOffset; record < destinationEnd;
                record += PRIMITIVE_WORDS_PER_TRIANGLE) {
            work.step();
            int packed = destination[record + FLAGS_EMITTER_WORD];
            int emitterIndex = PrimitivePacking.unpackEmitterIndex(packed);
            if (emitterIndex != PrimitivePacking.NO_EMITTER_INDEX) {
                destination[record + FLAGS_EMITTER_WORD] = PrimitivePacking.withEmitterIndex(
                        packed,
                        Math.addExact(emitterIndex, lightOffset));
            }
        }
    }

    private record Entry(
            int sectionX,
            int sectionY,
            int sectionZ,
            CpuSectionMesh mesh,
            int lightOffset) {
    }
}
