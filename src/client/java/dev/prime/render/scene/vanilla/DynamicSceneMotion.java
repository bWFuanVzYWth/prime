package dev.prime.render.scene.vanilla;

import dev.prime.render.terrain.CpuClusterMesh;
import dev.prime.render.terrain.CpuMeshSegment;
import dev.prime.render.terrain.CpuSectionMesh;
import dev.prime.render.terrain.CpuVoxelInstances;
import dev.prime.render.terrain.CpuVoxelMesh;
import dev.prime.render.terrain.OpacityMicromapData;
import dev.prime.render.terrain.TriangleLayout;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Builds one exact instance stream from a captured dynamic frame. */
public record DynamicSceneMotion(
        DynamicSceneFrame frame,
        CpuClusterMesh mesh,
        Statistics statistics) {
    public DynamicSceneMotion {
        Objects.requireNonNull(frame, "frame");
        Objects.requireNonNull(mesh, "mesh");
        Objects.requireNonNull(statistics, "statistics");
        if (!mesh.lights().isEmpty() || mesh.triangleLayout().triangleCount() != 0L) {
            throw new IllegalArgumentException(
                    "Dynamic instance geometry must not use the cluster base BLAS");
        }
    }

    public static DynamicSceneMotion prepare(
            DynamicSceneFrame current, DynamicSceneFrame previous) {
        Objects.requireNonNull(current, "current");
        CpuMeshSegment source = geometry(current.mesh());
        List<BuiltGeometry> currentGeometry = buildGeometry(current, source);
        Map<MotionKey, Integer> currentIdentityCounts = identityCounts(currentGeometry);
        Map<MotionKey, BuiltGeometry> previousByKey = previous == null
                ? Map.of()
                : uniqueStableGeometry(buildGeometry(previous, geometry(previous.mesh())));
        boolean sameCluster = previous != null
                && current.clusterX() == previous.clusterX()
                && current.clusterY() == previous.clusterY()
                && current.clusterZ() == previous.clusterZ();

        ArrayList<CpuVoxelMesh> prototypes = new ArrayList<>();
        Map<PrototypeKey, Integer> reusable = new HashMap<>();
        int instanceCount = currentGeometry.size();
        int[] meshIndices = new int[instanceCount];
        int[] packedTints = new int[instanceCount];
        float[] transforms = new float[Math.multiplyExact(
                instanceCount, CpuVoxelInstances.TRANSFORM_WORDS)];
        float[] previousTransforms = new float[transforms.length];
        boolean[] motion = new boolean[instanceCount];
        int uniqueCount = 0;
        long uniqueTriangles = 0L;
        EnumMap<VanillaSceneBoundary.Element, Integer> uniqueByElement =
                new EnumMap<>(VanillaSceneBoundary.Element.class);

        for (int index = 0; index < currentGeometry.size(); index++) {
            BuiltGeometry geometry = currentGeometry.get(index);
            boolean share = geometry.span().kind()
                    == DynamicSceneFrame.GeometryKind.INSTANCED;
            int meshIndex;
            if (share) {
                PrototypeKey key = new PrototypeKey(geometry.mesh());
                Integer existing = reusable.get(key);
                if (existing == null) {
                    meshIndex = prototypes.size();
                    prototypes.add(geometry.mesh());
                    reusable.put(key, meshIndex);
                } else {
                    meshIndex = existing;
                }
            } else {
                meshIndex = prototypes.size();
                CpuMeshSegment local = geometry.mesh().geometry();
                prototypes.add(CpuVoxelMesh.unique(
                        local.positions().clone(),
                        local.primitiveRecords().clone(),
                        local.triangleLayout(),
                        geometry.mesh().opacityMicromap()));
                uniqueCount++;
                uniqueTriangles = Math.addExact(
                        uniqueTriangles, geometry.span().triangleCount());
                uniqueByElement.merge(
                        geometry.span().element(), 1, Math::addExact);
            }
            meshIndices[index] = meshIndex;
            int transform = index * CpuVoxelInstances.TRANSFORM_WORDS;
            float[] currentTransform = geometry.transform().copyRows();
            System.arraycopy(
                    currentTransform,
                    0,
                    transforms,
                    transform,
                    CpuVoxelInstances.TRANSFORM_WORDS);
            System.arraycopy(
                    currentTransform,
                    0,
                    previousTransforms,
                    transform,
                    CpuVoxelInstances.TRANSFORM_WORDS);

            if (sameCluster
                    && geometry.span().stableIdentity()
                    && currentIdentityCounts.get(MotionKey.of(geometry.span())) == 1) {
                BuiltGeometry old = previousByKey.get(MotionKey.of(geometry.span()));
                if (old != null && samePrototype(geometry.mesh(), old.mesh())) {
                    float[] previousTransform = old.transform().copyRows();
                    System.arraycopy(
                            previousTransform,
                            0,
                            previousTransforms,
                            transform,
                            CpuVoxelInstances.TRANSFORM_WORDS);
                    motion[index] = !geometry.transform().rawEquals(old.transform());
                }
            }
        }

        CpuVoxelInstances instances = instanceCount == 0
                ? CpuVoxelInstances.EMPTY
                : CpuVoxelInstances.transformed(
                        meshIndices,
                        packedTints,
                        transforms,
                        previousTransforms,
                        motion);
        CpuClusterMesh mesh = instanceCount == 0
                ? CpuClusterMesh.empty()
                : CpuClusterMesh.fromInstances(prototypes, instances);
        return new DynamicSceneMotion(
                current,
                mesh,
                new Statistics(
                        instanceCount,
                        prototypes.size() - uniqueCount,
                        uniqueCount,
                        uniqueTriangles,
                        uniqueByElement));
    }

    private static List<BuiltGeometry> buildGeometry(
            DynamicSceneFrame frame, CpuMeshSegment source) {
        ArrayList<BuiltGeometry> result = new ArrayList<>(frame.geometrySpans().size());
        for (DynamicSceneFrame.GeometrySpan span : frame.geometrySpans()) {
            int firstPosition = Math.multiplyExact(span.firstTriangle(), 9);
            int positionCount = Math.multiplyExact(span.triangleCount(), 9);
            int firstPrimitive = Math.multiplyExact(
                    span.firstTriangle(), CpuSectionMesh.PRIMITIVE_WORDS);
            int primitiveCount = Math.multiplyExact(
                    span.triangleCount(), CpuSectionMesh.PRIMITIVE_WORDS);
            float[] positions = Arrays.copyOfRange(
                    source.positions(), firstPosition, firstPosition + positionCount);
            int[] primitives = Arrays.copyOfRange(
                    source.primitiveRecords(),
                    firstPrimitive,
                    firstPrimitive + primitiveCount);
            float[] local = positions;
            DynamicSceneFrame.InstanceTransform transform = span.transform();
            if (transform == null) {
                float x = positions[0];
                float y = positions[1];
                float z = positions[2];
                local = positions.clone();
                boolean exact = true;
                for (int vertex = 0; vertex < local.length; vertex += 3) {
                    local[vertex] -= x;
                    local[vertex + 1] -= y;
                    local[vertex + 2] -= z;
                    exact &= Float.floatToRawIntBits(local[vertex] + x)
                                    == Float.floatToRawIntBits(positions[vertex])
                            && Float.floatToRawIntBits(local[vertex + 1] + y)
                                    == Float.floatToRawIntBits(positions[vertex + 1])
                            && Float.floatToRawIntBits(local[vertex + 2] + z)
                                    == Float.floatToRawIntBits(positions[vertex + 2]);
                }
                if (exact) {
                    transform = DynamicSceneFrame.InstanceTransform.translation(x, y, z);
                } else {
                    local = positions;
                    transform = DynamicSceneFrame.InstanceTransform.translation(
                            0.0F, 0.0F, 0.0F);
                }
            }
            CpuVoxelMesh mesh = new CpuVoxelMesh(
                    local,
                    primitives,
                    TriangleLayout.triangles(0, span.triangleCount(), 0),
                    OpacityMicromapData.fullyUnknown(span.triangleCount()));
            result.add(new BuiltGeometry(span, mesh, transform));
        }
        return List.copyOf(result);
    }

    private static Map<MotionKey, BuiltGeometry> uniqueStableGeometry(
            List<BuiltGeometry> geometry) {
        Map<MotionKey, Integer> counts = identityCounts(geometry);
        Map<MotionKey, BuiltGeometry> result = new HashMap<>();
        for (BuiltGeometry item : geometry) {
            if (item.span().stableIdentity()) {
                MotionKey key = MotionKey.of(item.span());
                if (counts.get(key) == 1) {
                    result.put(key, item);
                }
            }
        }
        return result;
    }

    private static Map<MotionKey, Integer> identityCounts(
            List<BuiltGeometry> geometry) {
        Map<MotionKey, Integer> counts = new HashMap<>();
        for (BuiltGeometry item : geometry) {
            if (item.span().stableIdentity()) {
                counts.merge(MotionKey.of(item.span()), 1, Math::addExact);
            }
        }
        return counts;
    }

    private static boolean samePrototype(CpuVoxelMesh first, CpuVoxelMesh second) {
        CpuMeshSegment a = first.geometry();
        CpuMeshSegment b = second.geometry();
        return a.triangleLayout().equals(b.triangleLayout())
                && rawFloatEquals(a.positions(), b.positions())
                && Arrays.equals(a.primitiveRecords(), b.primitiveRecords());
    }

    public boolean sameGpuState(DynamicSceneMotion other) {
        if (other == null) {
            return false;
        }
        boolean bothEmpty = this.mesh.voxelMeshes().isEmpty()
                && other.mesh.voxelMeshes().isEmpty()
                && this.mesh.voxelInstances().count() == 0
                && other.mesh.voxelInstances().count() == 0;
        if (!bothEmpty
                && (this.frame.clusterX() != other.frame.clusterX()
                || this.frame.clusterY() != other.frame.clusterY()
                || this.frame.clusterZ() != other.frame.clusterZ())) {
            return false;
        }
        List<CpuVoxelMesh> firstMeshes = this.mesh.voxelMeshes();
        List<CpuVoxelMesh> secondMeshes = other.mesh.voxelMeshes();
        if (firstMeshes.size() != secondMeshes.size()) {
            return false;
        }
        for (int index = 0; index < firstMeshes.size(); index++) {
            CpuVoxelMesh first = firstMeshes.get(index);
            CpuVoxelMesh second = secondMeshes.get(index);
            if (first.reusable() != second.reusable() || !samePrototype(first, second)) {
                return false;
            }
        }
        CpuVoxelInstances first = this.mesh.voxelInstances();
        CpuVoxelInstances second = other.mesh.voxelInstances();
        if (!Arrays.equals(first.meshIndices(), second.meshIndices())
                || !Arrays.equals(first.packedTints(), second.packedTints())
                || !rawFloatEquals(first.transforms(), second.transforms())
                || !rawFloatEquals(first.previousTransforms(), second.previousTransforms())) {
            return false;
        }
        for (int index = 0; index < first.count(); index++) {
            if (first.hasMotion(index) != second.hasMotion(index)) {
                return false;
            }
        }
        return true;
    }

    private static boolean rawFloatEquals(float[] first, float[] second) {
        if (first.length != second.length) {
            return false;
        }
        for (int index = 0; index < first.length; index++) {
            if (Float.floatToRawIntBits(first[index])
                    != Float.floatToRawIntBits(second[index])) {
                return false;
            }
        }
        return true;
    }

    private static CpuMeshSegment geometry(CpuClusterMesh mesh) {
        if (mesh.isEmpty()) {
            return new CpuMeshSegment(
                    new float[0],
                    new int[0],
                    new int[0],
                    TriangleLayout.triangles(0, 0, 0));
        }
        if (mesh.segments().size() != 1) {
            throw new IllegalArgumentException(
                    "Dynamic capture requires one source mesh segment");
        }
        return mesh.segments().getFirst();
    }

    public record Statistics(
            int instanceCount,
            int reusablePrototypeCount,
            int uniqueFallbackCount,
            long uniqueFallbackTriangles,
            Map<VanillaSceneBoundary.Element, Integer> uniqueFallbackByElement) {
        public Statistics {
            uniqueFallbackByElement = Map.copyOf(uniqueFallbackByElement);
            if (instanceCount < 0
                    || reusablePrototypeCount < 0
                    || uniqueFallbackCount < 0
                    || uniqueFallbackTriangles < 0L) {
                throw new IllegalArgumentException(
                        "Dynamic instance statistics must not be negative");
            }
            int categorized = 0;
            for (Map.Entry<VanillaSceneBoundary.Element, Integer> entry
                    : uniqueFallbackByElement.entrySet()) {
                if (entry.getValue() <= 0) {
                    throw new IllegalArgumentException(
                            "Fallback category counts must be positive");
                }
                categorized = Math.addExact(categorized, entry.getValue());
            }
            if (categorized != uniqueFallbackCount) {
                throw new IllegalArgumentException(
                        "Fallback category counts do not match the total");
            }
        }
    }

    private record BuiltGeometry(
            DynamicSceneFrame.GeometrySpan span,
            CpuVoxelMesh mesh,
            DynamicSceneFrame.InstanceTransform transform) {}

    private record MotionKey(
            VanillaSceneBoundary.Element element,
            long key,
            int submission,
            int part) {
        private static MotionKey of(DynamicSceneFrame.GeometrySpan span) {
            return new MotionKey(
                    span.element(), span.key(), span.submission(), span.part());
        }
    }

    private static final class PrototypeKey {
        private final float[] positions;
        private final int[] primitives;
        private final int hash;

        private PrototypeKey(CpuVoxelMesh mesh) {
            CpuMeshSegment geometry = mesh.geometry();
            this.positions = geometry.positions();
            this.primitives = geometry.primitiveRecords();
            this.hash = 31 * rawFloatHash(this.positions) + Arrays.hashCode(this.primitives);
        }

        @Override
        public int hashCode() {
            return this.hash;
        }

        @Override
        public boolean equals(Object other) {
            return this == other
                    || other instanceof PrototypeKey key
                            && rawFloatEquals(this.positions, key.positions)
                            && Arrays.equals(this.primitives, key.primitives);
        }

        private static int rawFloatHash(float[] values) {
            int result = 1;
            for (float value : values) {
                result = 31 * result + Float.floatToRawIntBits(value);
            }
            return result;
        }
    }
}
