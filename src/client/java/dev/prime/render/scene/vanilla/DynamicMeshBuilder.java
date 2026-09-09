// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

package dev.prime.render.scene.vanilla;

import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.prime.render.terrain.CpuClusterMesh;
import dev.prime.render.terrain.CpuSectionLights;
import dev.prime.render.terrain.CpuSectionMesh;
import dev.prime.render.terrain.TriangleLayout;
import dev.prime.render.terrain.OpacityMicromapData;
import dev.prime.render.terrain.PrimitivePacking;
import it.unimi.dsi.fastutil.floats.FloatArrayList;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.util.LightCoordsUtil;
import org.joml.Matrix4fc;

/**
 * Converts vertices already accepted by Minecraft's entity renderer into Prime triangle records.
 *
 * <p>All dynamic triangles are alpha tested and deliberately receive no {@link CpuSectionLights}.
 * Full-bright input is encoded only as hit-visible emission.
 */
final class DynamicMeshBuilder {
    private final double offsetX;
    private final double offsetY;
    private final double offsetZ;
    private final FloatArrayList positions = new FloatArrayList(1024);
    private final IntArrayList primitives = new IntArrayList(1024);
    private final ArrayList<DynamicSceneFrame.MotionSegment> motionSegments =
            new ArrayList<>();
    private final ArrayList<DynamicSceneFrame.GeometrySpan> geometrySpans =
            new ArrayList<>();
    private final EnumSet<DynamicSceneFrame.CompatibilityIssue> compatibilityIssues =
            EnumSet.noneOf(DynamicSceneFrame.CompatibilityIssue.class);
    private OpenMotionObject openMotionObject;
    private VanillaSceneBoundary.Element captureElement =
            VanillaSceneBoundary.Element.FEATURE;
    private int unscopedSubmission;

    DynamicMeshBuilder(double offsetX, double offsetY, double offsetZ) {
        this.offsetX = offsetX;
        this.offsetY = offsetY;
        this.offsetZ = offsetZ;
    }

    void beginMotionObject(VanillaSceneBoundary.Element element, long key) {
        if (this.openMotionObject != null) {
            throw new IllegalStateException("Nested dynamic motion object capture");
        }
        this.openMotionObject = new OpenMotionObject(
                element, key, this.positions.size() / 9);
    }

    void endMotionObject(VanillaSceneBoundary.Element element, long key) {
        OpenMotionObject object = this.openMotionObject;
        if (object == null || object.element != element || object.key != key) {
            throw new IllegalStateException(
                    "Dynamic motion object capture closed out of order");
        }
        this.openMotionObject = null;
        int triangleCount = this.positions.size() / 9 - object.firstTriangle;
        if (triangleCount > 0) {
            this.motionSegments.add(new DynamicSceneFrame.MotionSegment(
                    element, key, object.firstTriangle, triangleCount));
        }
    }

    void markMotionObjectUnique() {
        if (this.openMotionObject != null) {
            this.openMotionObject.unique = true;
        }
    }

    void captureElement(VanillaSceneBoundary.Element element) {
        this.captureElement = element;
    }

    int beginModelSubmission() {
        OpenMotionObject object = this.openMotionObject;
        return object == null
                ? this.unscopedSubmission++
                : object.nextSubmission++;
    }

    DynamicSceneFrame.InstanceTransform instanceTransform(Matrix4fc matrix) {
        DynamicSceneFrame.InstanceTransform transform = DynamicSceneFrame.InstanceTransform.tryFrom(
                matrix, this.offsetX, this.offsetY, this.offsetZ);
        if (transform == null) {
            this.compatibilityIssues.add(
                    DynamicSceneFrame.CompatibilityIssue.SINGULAR_INSTANCE_TRANSFORM);
        }
        return transform;
    }

    VertexSink open(
            PrimitiveTopology topology,
            int textureIndex,
            int fallbackLight) {
        return this.open(topology, textureIndex, fallbackLight, false, CaptureMode.DEFAULT, null);
    }

    VertexSink open(
            PrimitiveTopology topology,
            int textureIndex,
            int fallbackLight,
            boolean redAlpha) {
        return this.open(
                topology, textureIndex, fallbackLight, redAlpha, CaptureMode.DEFAULT, null);
    }

    VertexSink openParticle(
            PrimitiveTopology topology,
            int textureIndex,
            int fallbackLight) {
        // Slot zero signals a capture fallback. It must not sample the block atlas with UVs
        // authored for a different atlas; keep the particle instance and its submitted color.
        return new VertexSink(this, topology, textureIndex, fallbackLight,
                textureIndex == 0, false, CaptureMode.PARTICLE, null);
    }

    VertexSink openModelPart(
            PrimitiveTopology topology,
            int textureIndex,
            int fallbackLight,
            boolean bakedMaterial,
            boolean redAlpha,
            int submission,
            int part,
            DynamicSceneFrame.InstanceTransform transform) {
        return new VertexSink(
                this,
                topology,
                textureIndex,
                fallbackLight,
                bakedMaterial,
                redAlpha,
                CaptureMode.MODEL_PART,
                new GeometryPlacement(submission, part, transform));
    }

    private VertexSink open(
            PrimitiveTopology topology,
            int textureIndex,
            int fallbackLight,
            boolean redAlpha,
            CaptureMode captureMode,
            GeometryPlacement placement) {
        return new VertexSink(
                this,
                topology,
                textureIndex,
                fallbackLight,
                false,
                redAlpha,
                captureMode,
                placement);
    }

    VertexSink openUntextured(
            PrimitiveTopology topology,
            int fallbackLight) {
        return new VertexSink(
                this, topology, 0, fallbackLight, true, false, CaptureMode.DEFAULT, null);
    }

    void report(DynamicSceneFrame.CompatibilityIssue issue) {
        this.compatibilityIssues.add(issue);
    }

    DynamicSceneFrame build(
            int clusterX,
            int clusterY,
            int clusterZ,
            List<DynamicSceneFrame.SceneTexture> textures) {
        if (this.openMotionObject != null) {
            throw new IllegalStateException(
                    "Dynamic mesh was built inside a motion object");
        }
        int triangleCount = this.positions.size() / 9;
        CpuSectionMesh section = new CpuSectionMesh(
                this.positions.toFloatArray(),
                this.primitives.toIntArray(),
                new int[0],
                TriangleLayout.triangles(0, triangleCount, 0),
                OpacityMicromapData.fullyUnknown(triangleCount),
                CpuSectionLights.EMPTY);
        return new DynamicSceneFrame(
                clusterX,
                clusterY,
                clusterZ,
                CpuClusterMesh.fromSegments(List.of(section)),
                textures,
                this.motionSegments,
                this.geometrySpans,
                this.compatibilityIssues);
    }

    private void recordGeometry(
            CaptureMode mode,
            GeometryPlacement placement,
            int firstTriangle,
            int triangleCount) {
        if (triangleCount == 0) {
            return;
        }
        OpenMotionObject object = this.openMotionObject;
        int submission;
        int part;
        DynamicSceneFrame.InstanceTransform transform;
        if (placement != null) {
            submission = placement.submission();
            part = placement.part();
            transform = placement.transform();
        } else if (object != null) {
            submission = object.nextSubmission++;
            part = 0;
            transform = null;
        } else {
            submission = -1;
            part = -1;
            transform = null;
        }
        this.geometrySpans.add(new DynamicSceneFrame.GeometrySpan(
                mode == CaptureMode.PARTICLE
                        ? DynamicSceneFrame.GeometryKind.INSTANCED
                        : mode == CaptureMode.MODEL_PART && transform == null
                                ? DynamicSceneFrame.GeometryKind.UNIQUE
                        : object != null && !object.unique
                                ? DynamicSceneFrame.GeometryKind.INSTANCED
                                : DynamicSceneFrame.GeometryKind.UNIQUE,
                object != null
                        ? object.element
                        : mode == CaptureMode.PARTICLE
                                ? VanillaSceneBoundary.Element.PARTICLE
                                : this.captureElement,
                object == null ? 0L : object.key,
                submission,
                part,
                object != null,
                transform,
                firstTriangle,
                triangleCount));
    }

    private void addTriangle(
            Vertex first,
            Vertex second,
            Vertex third,
            int textureIndex,
            boolean bakedMaterial,
            boolean redAlpha) {
        double xOffset = first.local ? 0.0 : this.offsetX;
        double yOffset = first.local ? 0.0 : this.offsetY;
        double zOffset = first.local ? 0.0 : this.offsetZ;
        float firstX = (float) (first.x + xOffset);
        float firstY = (float) (first.y + yOffset);
        float firstZ = (float) (first.z + zOffset);
        float secondX = (float) (second.x + xOffset);
        float secondY = (float) (second.y + yOffset);
        float secondZ = (float) (second.z + zOffset);
        float thirdX = (float) (third.x + xOffset);
        float thirdY = (float) (third.y + yOffset);
        float thirdZ = (float) (third.z + zOffset);
        if (!finite(
                firstX, firstY, firstZ,
                secondX, secondY, secondZ,
                thirdX, thirdY, thirdZ,
                first.u, first.v, second.u, second.v, third.u, third.v)
                || !unit(first.u)
                || !unit(first.v)
                || !unit(second.u)
                || !unit(second.v)
                || !unit(third.u)
                || !unit(third.v)) {
            return;
        }

        float edgeOneX = secondX - firstX;
        float edgeOneY = secondY - firstY;
        float edgeOneZ = secondZ - firstZ;
        float edgeTwoX = thirdX - firstX;
        float edgeTwoY = thirdY - firstY;
        float edgeTwoZ = thirdZ - firstZ;
        float crossX = edgeOneY * edgeTwoZ - edgeOneZ * edgeTwoY;
        float crossY = edgeOneZ * edgeTwoX - edgeOneX * edgeTwoZ;
        float crossZ = edgeOneX * edgeTwoY - edgeOneY * edgeTwoX;
        double twiceArea = Math.sqrt(
                (double) crossX * crossX
                        + (double) crossY * crossY
                        + (double) crossZ * crossZ);
        if (!(twiceArea > 0.0) || !Double.isFinite(twiceArea)) {
            return;
        }
        float authoredX = first.normalX + second.normalX + third.normalX;
        float authoredY = first.normalY + second.normalY + third.normalY;
        float authoredZ = first.normalZ + second.normalZ + third.normalZ;
        double orientation = (double) crossX * authoredX
                + (double) crossY * authoredY
                + (double) crossZ * authoredZ;
        if (orientation < 0.0) {
            Vertex swapVertex = second;
            second = third;
            third = swapVertex;
            float swap = secondX;
            secondX = thirdX;
            thirdX = swap;
            swap = secondY;
            secondY = thirdY;
            thirdY = swap;
            swap = secondZ;
            secondZ = thirdZ;
            thirdZ = swap;
            edgeOneX = secondX - firstX;
            edgeOneY = secondY - firstY;
            edgeOneZ = secondZ - firstZ;
            edgeTwoX = thirdX - firstX;
            edgeTwoY = thirdY - firstY;
            edgeTwoZ = thirdZ - firstZ;
            crossX = -crossX;
            crossY = -crossY;
            crossZ = -crossZ;
        }

        this.positions.add(firstX);
        this.positions.add(firstY);
        this.positions.add(firstZ);
        this.positions.add(secondX);
        this.positions.add(secondY);
        this.positions.add(secondZ);
        this.positions.add(thirdX);
        this.positions.add(thirdY);
        this.positions.add(thirdZ);
        int uv0 = bakedMaterial
                ? PrimitivePacking.packConstantUv(0.0F)
                : PrimitivePacking.packUv(first.u, first.v);
        int uv1 = bakedMaterial
                ? PrimitivePacking.packConstantUv(0.0F)
                : PrimitivePacking.packUv(second.u, second.v);
        int uv2 = bakedMaterial
                ? PrimitivePacking.CONSTANT_UV_OWN_TINT
                        | PrimitivePacking.CONSTANT_UV_BAKED_MATERIAL
                : PrimitivePacking.packUv(third.u, third.v);
        long tangent = PrimitivePacking.packTriangleTangent(
                edgeOneX,
                edgeOneY,
                edgeOneZ,
                edgeTwoX,
                edgeTwoY,
                edgeTwoZ,
                second.u - first.u,
                second.v - first.v,
                third.u - first.u,
                third.v - first.v,
                crossX,
                crossY,
                crossZ);
        int flags = PrimitivePacking.encodeLegacySemantics(
                true, false, false, false, false, false);
        int tint = PrimitivePacking.packTintControl(
                PrimitivePacking.packTint(first.color), flags);
        boolean visibleEmission = fullBright(first.light)
                && fullBright(second.light)
                && fullBright(third.light);
        this.primitives.add(uv0);
        this.primitives.add(uv1);
        this.primitives.add(uv2);
        this.primitives.add(tint);
        this.primitives.add(0);
        this.primitives.add(PrimitivePacking.packDynamicControl(
                flags, textureIndex, visibleEmission, redAlpha));
        this.primitives.add(bakedMaterial
                ? PrimitivePacking.CONSTANT_UV_DENSITY
                : PrimitivePacking.packUvDensity(
                        edgeOneX,
                        edgeOneY,
                        edgeOneZ,
                        edgeTwoX,
                        edgeTwoY,
                        edgeTwoZ,
                        second.u - first.u,
                        second.v - first.v,
                        third.u - first.u,
                        third.v - first.v));
        this.primitives.add((int) tangent);
    }

    private static boolean fullBright(int light) {
        return LightCoordsUtil.block(light) >= 15;
    }

    private static boolean finite(float... values) {
        for (float value : values) {
            if (!Float.isFinite(value)) {
                return false;
            }
        }
        return true;
    }

    private static boolean unit(float value) {
        return value >= 0.0F && value <= 1.0F;
    }

    static final class VertexSink implements VertexConsumer {
        private final DynamicMeshBuilder owner;
        private final PrimitiveTopology topology;
        private final int textureIndex;
        private final int fallbackLight;
        private final boolean bakedMaterial;
        private final boolean redAlpha;
        private final CaptureMode captureMode;
        private final GeometryPlacement placement;
        private final ArrayList<Vertex> vertices = new ArrayList<>();
        private Vertex current;
        private boolean finished;

        private VertexSink(
                DynamicMeshBuilder owner,
                PrimitiveTopology topology,
                int textureIndex,
                int fallbackLight,
                boolean bakedMaterial,
                boolean redAlpha,
                CaptureMode captureMode,
                GeometryPlacement placement) {
            this.owner = owner;
            this.topology = topology;
            this.textureIndex = textureIndex;
            this.fallbackLight = fallbackLight;
            this.bakedMaterial = bakedMaterial;
            this.redAlpha = redAlpha;
            this.captureMode = captureMode;
            this.placement = placement;
        }

        @Override
        public VertexConsumer addVertex(float x, float y, float z) {
            this.commitCurrent();
            this.current = new Vertex(
                    x,
                    y,
                    z,
                    this.fallbackLight,
                    this.owner.openMotionObject,
                    this.captureMode == CaptureMode.MODEL_PART
                            && this.placement.transform() != null);
            return this;
        }

        @Override
        public VertexConsumer setColor(int red, int green, int blue, int alpha) {
            this.requireCurrent().color =
                    alpha << 24 | red << 16 | green << 8 | blue;
            return this;
        }

        @Override
        public VertexConsumer setColor(int color) {
            this.requireCurrent().color = color;
            return this;
        }

        @Override
        public VertexConsumer setUv(float u, float v) {
            Vertex vertex = this.requireCurrent();
            vertex.u = u;
            vertex.v = v;
            return this;
        }

        @Override
        public VertexConsumer setUv1(int u, int v) {
            return this;
        }

        @Override
        public VertexConsumer setUv2(int u, int v) {
            this.requireCurrent().light = u & 0xffff | v << 16;
            return this;
        }

        @Override
        public VertexConsumer setNormal(float x, float y, float z) {
            Vertex vertex = this.requireCurrent();
            vertex.normalX = x;
            vertex.normalY = y;
            vertex.normalZ = z;
            return this;
        }

        @Override
        public VertexConsumer setLineWidth(float width) {
            return this;
        }

        void finish() {
            if (this.finished) {
                throw new IllegalStateException("Dynamic vertex sink was already finished");
            }
            this.finished = true;
            this.commitCurrent();
            int count = this.vertices.size();
            if (this.topology == PrimitiveTopology.QUADS) {
                int submissionFirstTriangle = this.owner.positions.size() / 9;
                boolean[] removed = this.resolveReverseQuads(count);
                for (int index = 0; index + 3 < count; index += 4) {
                    if (removed[index / 4]) {
                        continue;
                    }
                    int firstTriangle = this.owner.positions.size() / 9;
                    this.emit(index, index + 1, index + 2);
                    this.emit(index, index + 2, index + 3);
                    if (this.captureMode == CaptureMode.PARTICLE) {
                        this.owner.recordGeometry(
                                this.captureMode,
                                this.placement,
                                firstTriangle,
                                this.owner.positions.size() / 9 - firstTriangle);
                    }
                }
                if (this.captureMode != CaptureMode.PARTICLE) {
                    this.owner.recordGeometry(
                            this.captureMode,
                            this.placement,
                            submissionFirstTriangle,
                            this.owner.positions.size() / 9 - submissionFirstTriangle);
                }
            } else if (this.topology == PrimitiveTopology.TRIANGLES) {
                int firstTriangle = this.owner.positions.size() / 9;
                for (int index = 0; index + 2 < count; index += 3) {
                    this.emit(index, index + 1, index + 2);
                }
                this.owner.recordGeometry(
                        this.captureMode,
                        this.placement,
                        firstTriangle,
                        this.owner.positions.size() / 9 - firstTriangle);
            } else if (this.topology == PrimitiveTopology.TRIANGLE_STRIP) {
                int firstTriangle = this.owner.positions.size() / 9;
                for (int index = 0; index + 2 < count; index++) {
                    if ((index & 1) == 0) {
                        this.emit(index, index + 1, index + 2);
                    } else {
                        this.emit(index + 1, index, index + 2);
                    }
                }
                this.owner.recordGeometry(
                        CaptureMode.DEFAULT,
                        null,
                        firstTriangle,
                        this.owner.positions.size() / 9 - firstTriangle);
            } else if (this.topology == PrimitiveTopology.TRIANGLE_FAN) {
                int firstTriangle = this.owner.positions.size() / 9;
                for (int index = 1; index + 1 < count; index++) {
                    this.emit(0, index, index + 1);
                }
                this.owner.recordGeometry(
                        CaptureMode.DEFAULT,
                        null,
                        firstTriangle,
                        this.owner.positions.size() / 9 - firstTriangle);
            } else {
                this.owner.report(
                        DynamicSceneFrame.CompatibilityIssue.UNSUPPORTED_TOPOLOGY);
            }
        }

        private boolean[] resolveReverseQuads(int vertexCount) {
            int quadCount = vertexCount / 4;
            boolean[] removed = new boolean[quadCount];
            Map<DynamicQuadKey, ArrayList<Integer>> pending = new HashMap<>();
            for (int quad = 0; quad < quadCount; quad++) {
                int first = quad * 4;
                OpenMotionObject motion = this.vertices.get(first).motionObject;
                if (motion == null || !sameMotionObject(first, motion)) {
                    continue;
                }
                DynamicQuadKey key = DynamicQuadKey.of(this.vertices, first, motion);
                ArrayList<Integer> candidates =
                        pending.computeIfAbsent(key, ignored -> new ArrayList<>());
                int match = -1;
                for (int candidate = candidates.size() - 1;
                        candidate >= 0;
                        candidate--) {
                    if (sameReverseQuad(candidates.get(candidate) * 4, first)) {
                        match = candidate;
                        break;
                    }
                }
                if (match < 0) {
                    candidates.add(quad);
                } else {
                    candidates.remove(match);
                    removed[quad] = true;
                }
            }
            return removed;
        }

        private boolean sameMotionObject(int first, OpenMotionObject motion) {
            for (int vertex = 1; vertex < 4; vertex++) {
                if (this.vertices.get(first + vertex).motionObject != motion) {
                    return false;
                }
            }
            return true;
        }

        private boolean sameReverseQuad(int first, int second) {
            for (int offset = 0; offset < 4; offset++) {
                boolean same = true;
                for (int vertex = 0; vertex < 4; vertex++) {
                    Vertex a = this.vertices.get(first + vertex);
                    Vertex b = this.vertices.get(second + (offset - vertex & 3));
                    if (!sameVertex(a, b)) {
                        same = false;
                        break;
                    }
                }
                if (same) {
                    return true;
                }
            }
            return false;
        }

        private static boolean sameVertex(Vertex first, Vertex second) {
            return first.x == second.x
                    && first.y == second.y
                    && first.z == second.z
                    && first.u == second.u
                    && first.v == second.v
                    && first.color == second.color
                    && first.light == second.light
                    && first.normalX == -second.normalX
                    && first.normalY == -second.normalY
                    && first.normalZ == -second.normalZ;
        }

        private void emit(int first, int second, int third) {
            this.owner.addTriangle(
                    this.vertices.get(first),
                    this.vertices.get(second),
                    this.vertices.get(third),
                    this.textureIndex,
                    this.bakedMaterial,
                    this.redAlpha);
        }

        private Vertex requireCurrent() {
            if (this.current == null) {
                throw new IllegalStateException("Vertex attribute was written before a position");
            }
            return this.current;
        }

        private void commitCurrent() {
            if (this.current != null) {
                this.vertices.add(this.current);
                this.current = null;
            }
        }
    }

    private static final class OpenMotionObject {
        private final VanillaSceneBoundary.Element element;
        private final long key;
        private final int firstTriangle;
        private boolean unique;
        private int nextSubmission;

        private OpenMotionObject(
                VanillaSceneBoundary.Element element, long key, int firstTriangle) {
            this.element = element;
            this.key = key;
            this.firstTriangle = firstTriangle;
        }
    }

    private enum CaptureMode {
        DEFAULT,
        MODEL_PART,
        PARTICLE
    }

    private record GeometryPlacement(
            int submission,
            int part,
            DynamicSceneFrame.InstanceTransform transform) {}

    private static final class Vertex {
        private final float x;
        private final float y;
        private final float z;
        private float u;
        private float v;
        private int color = -1;
        private float normalX;
        private float normalY = 1.0F;
        private float normalZ;
        private int light;
        private final OpenMotionObject motionObject;
        private final boolean local;

        private Vertex(
                float x,
                float y,
                float z,
                int light,
                OpenMotionObject motionObject,
                boolean local) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.light = light;
            this.motionObject = motionObject;
            this.local = local;
        }
    }

    private record DynamicPosition(int x, int y, int z)
            implements Comparable<DynamicPosition> {
        static DynamicPosition of(Vertex vertex) {
            return new DynamicPosition(
                    Float.floatToIntBits(vertex.x == 0.0F ? 0.0F : vertex.x),
                    Float.floatToIntBits(vertex.y == 0.0F ? 0.0F : vertex.y),
                    Float.floatToIntBits(vertex.z == 0.0F ? 0.0F : vertex.z));
        }

        @Override
        public int compareTo(DynamicPosition other) {
            int result = Integer.compare(this.x, other.x);
            if (result == 0) {
                result = Integer.compare(this.y, other.y);
            }
            return result == 0 ? Integer.compare(this.z, other.z) : result;
        }
    }

    private record DynamicQuadKey(
            OpenMotionObject motion,
            List<DynamicPosition> positions) {
        static DynamicQuadKey of(
                List<Vertex> vertices,
                int first,
                OpenMotionObject motion) {
            DynamicPosition[] positions = new DynamicPosition[4];
            for (int vertex = 0; vertex < 4; vertex++) {
                positions[vertex] = DynamicPosition.of(vertices.get(first + vertex));
            }
            Arrays.sort(positions);
            return new DynamicQuadKey(motion, List.of(positions));
        }
    }

}
