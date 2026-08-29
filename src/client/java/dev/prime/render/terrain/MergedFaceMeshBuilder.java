package dev.prime.render.terrain;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Covers compatible unit faces with conservative cluster-local macro faces or experimental
 * per-texture voxel-surface instances.
 *
 * <p>Opaque and transmissive groups use the mechanically ported 64x64 optimal
 * rectangle decomposition. Cutout groups retain their bounded square templates
 * because those sizes are part of the opacity-micromap contract.
 */
final class MergedFaceMeshBuilder {
    private static final int GRID_SIZE = SectionCluster.SECTION_SIZE * 16;
    private static final int[] CUTOUT_SIZES = {4, 2, 1};

    private final int segmentTriangleTarget;
    private final int maxOpacity2StateSubdivisionLevel;
    private final int maxOpacity4StateSubdivisionLevel;
    private final boolean voxelSurfacesEnabled;
    private final float voxelSurfaceMaximumHeight;
    private final ClusterTranslationWork work;
    private final ArrayList<CpuSectionMesh> segments = new ArrayList<>();
    private Segment segment;
    private OptimalCover optimalCover;
    private List<CpuVoxelMesh> voxelMeshes = List.of();
    private CpuVoxelInstances voxelInstances = CpuVoxelInstances.EMPTY;

    MergedFaceMeshBuilder(
            int segmentTriangleTarget, int maxOpacityMicromapSubdivisionLevel) {
        this(
                segmentTriangleTarget,
                maxOpacityMicromapSubdivisionLevel,
                false,
                VoxelSurfaceSettings.BASE_HEIGHT);
    }

    MergedFaceMeshBuilder(
            int segmentTriangleTarget,
            int maxOpacityMicromapSubdivisionLevel,
            boolean voxelSurfacesEnabled,
            float voxelSurfaceMaximumHeight) {
        this(
                segmentTriangleTarget,
                maxOpacityMicromapSubdivisionLevel,
                maxOpacityMicromapSubdivisionLevel,
                voxelSurfacesEnabled,
                voxelSurfaceMaximumHeight);
    }

    MergedFaceMeshBuilder(
            int segmentTriangleTarget,
            int maxOpacity2StateSubdivisionLevel,
            int maxOpacity4StateSubdivisionLevel,
            boolean voxelSurfacesEnabled,
            float voxelSurfaceMaximumHeight) {
        this(
                segmentTriangleTarget,
                maxOpacity2StateSubdivisionLevel,
                maxOpacity4StateSubdivisionLevel,
                voxelSurfacesEnabled,
                voxelSurfaceMaximumHeight,
                new ClusterTranslationWork(ClusterTranslationControl.UNINTERRUPTIBLE));
    }

    MergedFaceMeshBuilder(
            int segmentTriangleTarget,
            int maxOpacity2StateSubdivisionLevel,
            int maxOpacity4StateSubdivisionLevel,
            boolean voxelSurfacesEnabled,
            float voxelSurfaceMaximumHeight,
            ClusterTranslationWork work) {
        this.segmentTriangleTarget = segmentTriangleTarget;
        this.maxOpacity2StateSubdivisionLevel = maxOpacity2StateSubdivisionLevel;
        this.maxOpacity4StateSubdivisionLevel = maxOpacity4StateSubdivisionLevel;
        this.voxelSurfacesEnabled = voxelSurfacesEnabled;
        this.voxelSurfaceMaximumHeight = voxelSurfaceMaximumHeight;
        this.work = java.util.Objects.requireNonNull(work, "work");
        this.segment = new Segment(
                maxOpacity2StateSubdivisionLevel,
                maxOpacity4StateSubdivisionLevel);
    }

    List<CpuSectionMesh> build(List<MergeFace> faces) {
        this.work.checkpoint();
        TextureVoxelMeshBuilder detailBuilder = this.voxelSurfacesEnabled
                ? new TextureVoxelMeshBuilder(
                        faces.stream().anyMatch(MergeFace::buildOpacityMicromap),
                        this.voxelSurfaceMaximumHeight,
                        this.work)
                : null;
        Set<MergeFace> composited = Collections.newSetFromMap(new IdentityHashMap<>());
        Map<FaceLocation, MergeFace> opaqueFaces = new HashMap<>();
        Map<FaceLocation, MergeFace> cutoutFaces = new HashMap<>();
        Set<FaceLocation> ambiguous = new HashSet<>();
        for (MergeFace face : faces) {
            this.work.step();
            requireInGrid(face);
            if (usesVoxelSurface(face) && !face.cutout() && !face.transmissive()) {
                putUnique(opaqueFaces, ambiguous, face);
            } else if (usesVoxelSurface(face)
                    && face.cutout()
                    && !face.transmissive()
                    && face.rasterOverlay()) {
                putUnique(cutoutFaces, ambiguous, face);
            }
        }
        for (Map.Entry<FaceLocation, MergeFace> entry : opaqueFaces.entrySet()) {
            this.work.step();
            if (ambiguous.contains(entry.getKey())) {
                continue;
            }
            MergeFace overlay = cutoutFaces.get(entry.getKey());
            if (overlay == null) {
                continue;
            }
            MergeFace base = entry.getValue();
            boolean resolved = detailBuilder != null
                    && detailBuilder.addComposite(base, overlay);
            if (resolved) {
                composited.add(base);
                composited.add(overlay);
            }
        }
        ArrayList<MergeFace> ordinaryFaces = new ArrayList<>(faces.size());
        ArrayList<MergeFace> unmergedFallbacks = new ArrayList<>();
        for (MergeFace face : faces) {
            this.work.step();
            if (composited.contains(face)) {
                continue;
            }
            if (detailBuilder != null && usesVoxelSurface(face)) {
                if (!detailBuilder.add(face)) {
                    unmergedFallbacks.add(face);
                }
            } else {
                ordinaryFaces.add(face);
            }
        }
        Map<GroupKey, ArrayList<MergeFace>> groups = new LinkedHashMap<>();
        for (MergeFace face : ordinaryFaces) {
            this.work.step();
            groups.computeIfAbsent(new GroupKey(face), ignored -> new ArrayList<>())
                    .add(face);
        }
        for (ArrayList<MergeFace> group : groups.values()) {
            this.work.checkpoint();
            FaceGrid grid = new FaceGrid();
            for (MergeFace face : group) {
                this.work.step();
                grid.add(face);
            }
            MergeFace first = grid.first();
            if (first.cutout() && !first.transmissive()) {
                this.coverCutout(grid);
            } else {
                this.coverOpaque(grid);
            }
            for (MergeFace duplicate : grid.duplicates) {
                this.work.step();
                this.emit(duplicate, duplicate.cellU(), duplicate.cellV(), 1, 1);
            }
        }
        for (MergeFace fallback : unmergedFallbacks) {
            this.work.step();
            this.emit(fallback, fallback.cellU(), fallback.cellV(), 1, 1);
        }
        this.finishSegment();
        if (detailBuilder != null) {
            this.work.checkpoint();
            TextureVoxelMeshBuilder.ListResult result = detailBuilder.build();
            this.voxelMeshes = result.meshes();
            this.voxelInstances = result.instances();
        }
        this.work.checkpoint();
        return List.copyOf(this.segments);
    }

    List<CpuVoxelMesh> voxelMeshes() {
        return this.voxelMeshes;
    }

    CpuVoxelInstances voxelInstances() {
        return this.voxelInstances;
    }

    private static boolean usesVoxelSurface(MergeFace face) {
        int flags = PrimitivePacking.unpackControl(
                face.primitive()[3], face.primitive()[5]);
        boolean transmissive = PrimitivePacking.isTransmissive(flags);
        boolean cutout = PrimitivePacking.isCutout(flags);
        boolean thinWalled = PrimitivePacking.isThinWalled(flags);
        if (transmissive && !cutout && !thinWalled) {
            return false;
        }
        return true;
    }

    private static void putUnique(
            Map<FaceLocation, MergeFace> faces,
            Set<FaceLocation> ambiguous,
            MergeFace face) {
        FaceLocation location = new FaceLocation(face);
        if (faces.putIfAbsent(location, face) != null) {
            ambiguous.add(location);
        }
    }

    private static void requireInGrid(MergeFace face) {
        if (face.cellU() < 0
                || face.cellU() >= GRID_SIZE
                || face.cellV() < 0
                || face.cellV() >= GRID_SIZE) {
            throw new IllegalArgumentException(
                    "Merge face lies outside its logical cluster");
        }
    }

    private record FaceLocation(
            int planeAxis, int normalSign, int planeCell, int cellU, int cellV) {
        FaceLocation(MergeFace face) {
            this(
                    face.planeAxis(),
                    face.normalSign(),
                    face.planeCell(),
                    face.cellU(),
                    face.cellV());
        }
    }

    private void coverOpaque(FaceGrid grid) {
        OptimalCover cover = this.optimalCover();
        cover.layer.clear();
        for (int v = 0; v < GRID_SIZE; v++) {
            long occupied = grid.occupied(v);
            while (occupied != 0L) {
                int u = Long.numberOfTrailingZeros(occupied);
                occupied &= occupied - 1L;
                this.work.step();
                cover.layer.pushSquare(u, v, 0, 1);
            }
        }
        RectangleDecomposition64.Result rectangles =
                cover.layer.finish(cover.scratch);
        for (int index = 0; index < rectangles.size(); index++) {
            this.work.step();
            if (rectangles.value(index) != 1) {
                throw new IllegalStateException(
                        "Merged-face decomposition changed the occupancy label");
            }
            int u = rectangles.xStart(index);
            int v = rectangles.yStart(index);
            MergeFace face = grid.get(u, v);
            if (face == null) {
                throw new IllegalStateException(
                        "Merged-face decomposition emitted an empty rectangle");
            }
            this.emit(
                    face,
                    u,
                    v,
                    rectangles.xEnd(index) - u,
                    rectangles.yEnd(index) - v);
        }
    }

    private OptimalCover optimalCover() {
        if (this.optimalCover == null) {
            this.optimalCover = new OptimalCover();
        }
        return this.optimalCover;
    }

    private void coverCutout(FaceGrid grid) {
        MergeFace first = grid.first();
        int maximumSize = first.buildOpacityMicromap()
                ? OpacityMicromapData.maximumRepeatedSize(
                        first.sprite(),
                        first.primitive()[0],
                        first.primitive()[1],
                        first.primitive()[2],
                        this.maxOpacity2StateSubdivisionLevel,
                        this.maxOpacity4StateSubdivisionLevel)
                : 4;
        for (int size : CUTOUT_SIZES) {
            if (size > maximumSize) {
                continue;
            }
            long validStarts = lowBits(GRID_SIZE - size + 1);
            for (int v = 0; v <= GRID_SIZE - size; v++) {
                long candidates = grid.occupied(v) & validStarts;
                while (candidates != 0L) {
                    int u = Long.numberOfTrailingZeros(candidates);
                    candidates &= candidates - 1L;
                    this.work.step();
                    MergeFace face = grid.get(u, v);
                    if (face == null || !grid.full(u, v, size)) {
                        continue;
                    }
                    grid.clear(u, v, size, size);
                    this.emit(face, u, v, size, size);
                }
            }
        }
    }

    private static long lowBits(int count) {
        return count == Long.SIZE ? -1L : (1L << count) - 1L;
    }

    private void emit(MergeFace face, int u, int v, int width, int height) {
        if (this.segment.triangleCount() + 2 > this.segmentTriangleTarget) {
            this.finishSegment();
        }
        this.segment.add(face, u, v, width, height);
    }

    private void finishSegment() {
        if (this.segment.triangleCount() == 0) {
            return;
        }
        this.segments.add(this.segment.build());
        this.segment = new Segment(
                this.maxOpacity2StateSubdivisionLevel,
                this.maxOpacity4StateSubdivisionLevel);
    }

    private static final class Segment {
        private final FloatBuilder opaquePositions = new FloatBuilder();
        private final IntBuilder opaquePrimitives = new IntBuilder();
        private final FloatBuilder cutoutPositions = new FloatBuilder();
        private final IntBuilder cutoutPrimitives = new IntBuilder();
        private final FloatBuilder transmissivePositions = new FloatBuilder();
        private final IntBuilder transmissivePrimitives = new IntBuilder();
        private final OpacityMicromapData.Builder opacityMicromap;
        private int opaqueTriangles;
        private int cutoutTriangles;
        private int transmissiveTriangles;

        private Segment(
                int maxOpacity2StateSubdivisionLevel,
                int maxOpacity4StateSubdivisionLevel) {
            this.opacityMicromap = new OpacityMicromapData.Builder(
                    maxOpacity2StateSubdivisionLevel,
                    maxOpacity4StateSubdivisionLevel);
        }

        int triangleCount() {
            return Math.addExact(
                    Math.addExact(this.opaqueTriangles, this.cutoutTriangles),
                    this.transmissiveTriangles);
        }

        void add(MergeFace face, int u, int v, int width, int height) {
            float[][] corners = corners(face, u, v, width, height);
            FloatBuilder positions = face.transmissive()
                    ? this.transmissivePositions
                    : (face.cutout() ? this.cutoutPositions : this.opaquePositions);
            IntBuilder primitives = face.transmissive()
                    ? this.transmissivePrimitives
                    : (face.cutout() ? this.cutoutPrimitives : this.opaquePrimitives);
            addTriangle(positions, corners[0], corners[1], corners[2]);
            addTriangle(positions, corners[0], corners[2], corners[3]);
            // Repeated/projected UVs make both triangles of the rectangle semantically identical.
            // The shader maps this adjacent pair back to this single record.
            primitives.add(face.primitive());
            if (face.transmissive()) {
                this.transmissiveTriangles += 2;
            } else if (face.cutout()) {
                this.cutoutTriangles += 2;
                if (face.buildOpacityMicromap() && !face.frontFaceOnly()) {
                    this.addMicromapTriangle(face, u, v, width, corners, 0, 1, 2);
                    this.addMicromapTriangle(face, u, v, width, corners, 0, 2, 3);
                } else {
                    this.opacityMicromap.addFullyUnknownTriangle();
                    this.opacityMicromap.addFullyUnknownTriangle();
                }
            } else {
                this.opaqueTriangles += 2;
            }
        }

        private void addMicromapTriangle(
                MergeFace face,
                int originU,
                int originV,
                int size,
                float[][] corners,
                int first,
                int second,
                int third) {
            int axisU = MergeFace.projectedAxisU(face.planeAxis());
            int axisV = MergeFace.projectedAxisV(face.planeAxis());
            this.opacityMicromap.addRepeatedTriangle(
                    face.sprite(),
                    face.primitive()[0],
                    face.primitive()[1],
                    face.primitive()[2],
                    size,
                    corners[first][axisU] - originU,
                    corners[first][axisV] - originV,
                    corners[second][axisU] - originU,
                    corners[second][axisV] - originV,
                    corners[third][axisU] - originU,
                    corners[third][axisV] - originV);
        }

        CpuSectionMesh build() {
            float[] positions = concatenate(
                    this.opaquePositions,
                    this.cutoutPositions,
                    this.transmissivePositions);
            int[] primitives = concatenate(
                    this.opaquePrimitives,
                    this.cutoutPrimitives,
                    this.transmissivePrimitives);
            return new CpuSectionMesh(
                    positions,
                    primitives,
                    this.opaqueTriangles,
                    this.cutoutTriangles,
                    this.transmissiveTriangles,
                    this.opaqueTriangles,
                    this.cutoutTriangles,
                    this.transmissiveTriangles,
                    this.opacityMicromap.build(),
                    CpuSectionLights.EMPTY);
        }

        private static float[][] corners(
                MergeFace face, int u, int v, int width, int height) {
            int axisU = MergeFace.projectedAxisU(face.planeAxis());
            int axisV = MergeFace.projectedAxisV(face.planeAxis());
            float[][] canonical = new float[4][3];
            set(canonical[0], face.planeAxis(), face.plane(), axisU, u, axisV, v);
            set(canonical[1], face.planeAxis(), face.plane(), axisU, u + width, axisV, v);
            set(canonical[2], face.planeAxis(), face.plane(), axisU, u + width, axisV, v + height);
            set(canonical[3], face.planeAxis(), face.plane(), axisU, u, axisV, v + height);
            int baseSign = face.planeAxis() == 1 ? -1 : 1;
            if (face.normalSign() == baseSign) {
                return canonical;
            }
            return new float[][] {canonical[0], canonical[3], canonical[2], canonical[1]};
        }

        private static void set(
                float[] target,
                int planeAxis,
                float plane,
                int axisU,
                float u,
                int axisV,
                float v) {
            target[planeAxis] = plane;
            target[axisU] = u;
            target[axisV] = v;
        }

        private static void addTriangle(
                FloatBuilder positions,
                float[] first,
                float[] second,
                float[] third) {
            positions.add(first);
            positions.add(second);
            positions.add(third);
        }

        private static float[] concatenate(
                FloatBuilder first, FloatBuilder second, FloatBuilder third) {
            float[] result = Arrays.copyOf(
                    first.values, first.size + second.size + third.size);
            System.arraycopy(second.values, 0, result, first.size, second.size);
            System.arraycopy(
                    third.values, 0, result, first.size + second.size, third.size);
            return result;
        }

        private static int[] concatenate(
                IntBuilder first, IntBuilder second, IntBuilder third) {
            int[] result = Arrays.copyOf(
                    first.values, first.size + second.size + third.size);
            System.arraycopy(second.values, 0, result, first.size, second.size);
            System.arraycopy(
                    third.values, 0, result, first.size + second.size, third.size);
            return result;
        }
    }

    private static final class FaceGrid {
        private final MergeFace[] faces = new MergeFace[GRID_SIZE * GRID_SIZE];
        private final long[] occupied = new long[GRID_SIZE];
        private final ArrayList<MergeFace> duplicates = new ArrayList<>();
        private MergeFace first;

        void add(MergeFace face) {
            if (this.first == null) {
                this.first = face;
            }
            int index = face.cellU() + face.cellV() * GRID_SIZE;
            if (this.faces[index] == null) {
                this.faces[index] = face;
                this.occupied[face.cellV()] |= 1L << face.cellU();
            } else {
                this.duplicates.add(face);
            }
        }

        MergeFace first() {
            return this.first;
        }

        MergeFace get(int u, int v) {
            return this.faces[u + v * GRID_SIZE];
        }

        long occupied(int v) {
            return this.occupied[v];
        }

        boolean full(int u, int v, int size) {
            long mask = lowBits(size) << u;
            for (int y = 0; y < size; y++) {
                if ((this.occupied[v + y] & mask) != mask) {
                    return false;
                }
            }
            return true;
        }

        void clear(int u, int v, int width, int height) {
            long mask = lowBits(width) << u;
            for (int y = 0; y < height; y++) {
                Arrays.fill(
                        this.faces,
                        u + (v + y) * GRID_SIZE,
                        u + width + (v + y) * GRID_SIZE,
                        null);
                this.occupied[v + y] &= ~mask;
            }
        }
    }

    private static final class GroupKey {
        private final MergeFace face;
        private final int hash;

        GroupKey(MergeFace face) {
            this.face = face;
            int result = face.planeAxis();
            result = 31 * result + face.normalSign();
            result = 31 * result + face.planeCell();
            this.hash = 31 * result + face.materialHash();
        }

        @Override
        public boolean equals(Object other) {
            return this == other
                    || other instanceof GroupKey key
                            && this.face.planeAxis() == key.face.planeAxis()
                            && this.face.normalSign() == key.face.normalSign()
                            && this.face.planeCell() == key.face.planeCell()
                            && this.face.sameMaterial(key.face);
        }

        @Override
        public int hashCode() {
            return this.hash;
        }
    }

    /** Scratch is owned by one cluster build and reused across its material groups. */
    private static final class OptimalCover {
        private final RectangleDecomposition64.LayerBuilder layer =
                new RectangleDecomposition64.LayerBuilder();
        private final RectangleDecomposition64.Scratch scratch =
                new RectangleDecomposition64.Scratch();
    }

    private static final class FloatBuilder {
        private float[] values = new float[1024];
        private int size;

        void add(float[] vector) {
            this.ensure(3);
            this.values[this.size++] = vector[0];
            this.values[this.size++] = vector[1];
            this.values[this.size++] = vector[2];
        }

        private void ensure(int count) {
            if (this.size + count > this.values.length) {
                this.values = Arrays.copyOf(
                        this.values, Math.max(this.values.length * 2, this.size + count));
            }
        }
    }

    private static final class IntBuilder {
        private int[] values = new int[1024];
        private int size;

        void add(int[] record) {
            if (this.size + record.length > this.values.length) {
                this.values = Arrays.copyOf(
                        this.values,
                        Math.max(this.values.length * 2, this.size + record.length));
            }
            System.arraycopy(record, 0, this.values, this.size, record.length);
            this.size += record.length;
        }
    }
}
