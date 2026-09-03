package dev.prime.render.scene.vanilla;

import dev.prime.render.terrain.CpuClusterMesh;
import dev.prime.render.terrain.CpuSectionMesh;
import dev.prime.render.terrain.PrimitivePacking;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Previous-frame vertex correspondence prepared for one published dynamic BLAS. */
public record DynamicSceneMotion(
        DynamicSceneFrame frame,
        float[] previousPositions) {
    public DynamicSceneMotion {
        Objects.requireNonNull(frame, "frame");
        Objects.requireNonNull(previousPositions, "previousPositions");
        if (previousPositions.length != frame.mesh().triangleLayout().triangleCount() * 9L) {
            throw new IllegalArgumentException(
                    "Previous dynamic positions do not match the current mesh");
        }
    }

    /** Pairs stable object identities; unmatched or ambiguous geometry keeps zero object motion. */
    public static DynamicSceneMotion prepare(
            DynamicSceneFrame current, DynamicSceneFrame previous) {
        Objects.requireNonNull(current, "current");
        float[] currentPositions = positions(current.mesh());
        float[] motionPositions = currentPositions.clone();
        List<DynamicSceneFrame.MotionSegment> currentSegments =
                current.motionSegments();
        if (previous == null) {
            return new DynamicSceneMotion(current, motionPositions);
        }

        Map<MotionKey, DynamicSceneFrame.MotionSegment> previousByKey =
                segmentMap(previous.motionSegments());
        boolean sameCluster = current.clusterX() == previous.clusterX()
                && current.clusterY() == previous.clusterY()
                && current.clusterZ() == previous.clusterZ();
        float[] oldPositions = positions(previous.mesh());
        for (DynamicSceneFrame.MotionSegment segment : currentSegments) {
            MotionKey key = new MotionKey(segment.element(), segment.key());
            DynamicSceneFrame.MotionSegment old = previousByKey.get(key);
            if (!sameCluster
                    || old == null
                    || !sameTopology(current, segment, previous, old)) {
                continue;
            }
            System.arraycopy(
                    oldPositions,
                    Math.multiplyExact(old.firstTriangle(), 9),
                    motionPositions,
                    Math.multiplyExact(segment.firstTriangle(), 9),
                    Math.multiplyExact(segment.triangleCount(), 9));
        }
        // Unmatched geometry keeps current positions. Depth, normal and material rejection then
        // invalidate only its changed silhouette while unrelated pixels retain temporal history.
        return new DynamicSceneMotion(current, motionPositions);
    }

    private static Map<MotionKey, DynamicSceneFrame.MotionSegment> segmentMap(
            List<DynamicSceneFrame.MotionSegment> segments) {
        Map<MotionKey, DynamicSceneFrame.MotionSegment> result =
                new HashMap<>(segments.size());
        for (DynamicSceneFrame.MotionSegment segment : segments) {
            MotionKey key = new MotionKey(segment.element(), segment.key());
            result.put(key, segment);
        }
        return result;
    }

    private static boolean sameTopology(
            DynamicSceneFrame current,
            DynamicSceneFrame.MotionSegment currentSegment,
            DynamicSceneFrame previous,
            DynamicSceneFrame.MotionSegment previousSegment) {
        if (currentSegment.triangleCount() != previousSegment.triangleCount()) {
            return false;
        }
        int[] currentPrimitives = primitives(current.mesh());
        int[] previousPrimitives = primitives(previous.mesh());
        for (int triangle = 0; triangle < currentSegment.triangleCount(); triangle++) {
            int currentBase = Math.multiplyExact(
                    currentSegment.firstTriangle() + triangle,
                    CpuSectionMesh.PRIMITIVE_WORDS);
            int previousBase = Math.multiplyExact(
                    previousSegment.firstTriangle() + triangle,
                    CpuSectionMesh.PRIMITIVE_WORDS);
            if (currentPrimitives[currentBase] != previousPrimitives[previousBase]
                    || currentPrimitives[currentBase + 1]
                            != previousPrimitives[previousBase + 1]
                    || currentPrimitives[currentBase + 2]
                            != previousPrimitives[previousBase + 2]
                    || !sameTexture(
                            current,
                            currentPrimitives[currentBase + 5],
                            previous,
                            previousPrimitives[previousBase + 5])) {
                return false;
            }
        }
        return true;
    }

    private static boolean sameTexture(
            DynamicSceneFrame current,
            int currentFlags,
            DynamicSceneFrame previous,
            int previousFlags) {
        int currentIndex = PrimitivePacking.unpackDynamicTextureIndex(currentFlags);
        int previousIndex = PrimitivePacking.unpackDynamicTextureIndex(previousFlags);
        if (currentIndex <= 0 || previousIndex <= 0) {
            return currentIndex == previousIndex;
        }
        int currentListIndex = currentIndex - 1;
        int previousListIndex = previousIndex - 1;
        if (currentListIndex >= current.textures().size()
                || previousListIndex >= previous.textures().size()) {
            return currentIndex == previousIndex;
        }
        DynamicSceneFrame.SceneTexture currentTexture =
                current.textures().get(currentListIndex);
        DynamicSceneFrame.SceneTexture previousTexture =
                previous.textures().get(previousListIndex);
        return currentTexture.view() == previousTexture.view()
                && currentTexture.sampler() == previousTexture.sampler();
    }

    private static float[] positions(CpuClusterMesh mesh) {
        if (mesh.isEmpty()) {
            return new float[0];
        }
        if (mesh.segments().size() != 1) {
            throw new IllegalArgumentException(
                    "Dynamic motion requires one captured mesh segment");
        }
        return mesh.segments().getFirst().positions();
    }

    private static int[] primitives(CpuClusterMesh mesh) {
        if (mesh.isEmpty()) {
            return new int[0];
        }
        if (mesh.segments().size() != 1) {
            throw new IllegalArgumentException(
                    "Dynamic motion requires one captured mesh segment");
        }
        return mesh.segments().getFirst().primitiveRecords();
    }

    private record MotionKey(VanillaSceneBoundary.Element element, long key) {}
}
