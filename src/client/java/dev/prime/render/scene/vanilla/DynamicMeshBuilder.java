package dev.prime.render.scene.vanilla;

import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.prime.render.terrain.CpuClusterMesh;
import dev.prime.render.terrain.CpuSectionLights;
import dev.prime.render.terrain.CpuSectionMesh;
import dev.prime.render.terrain.OpacityMicromapData;
import dev.prime.render.terrain.PrimitivePacking;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.util.LightCoordsUtil;

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
    private final FloatWords positions = new FloatWords();
    private final IntWords primitives = new IntWords();
    private final ArrayList<DynamicSceneFrame.MotionSegment> motionSegments =
            new ArrayList<>();
    private final int[] trianglesByElement =
            new int[VanillaSceneBoundary.Element.values().length];
    private final EnumSet<DynamicSceneFrame.CompatibilityIssue> compatibilityIssues =
            EnumSet.noneOf(DynamicSceneFrame.CompatibilityIssue.class);
    private OpenMotionObject openMotionObject;

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
                element, key, this.positions.size / 9);
    }

    void endMotionObject(VanillaSceneBoundary.Element element, long key) {
        OpenMotionObject object = this.openMotionObject;
        if (object == null || object.element != element || object.key != key) {
            throw new IllegalStateException(
                    "Dynamic motion object capture closed out of order");
        }
        this.openMotionObject = null;
        int triangleCount = this.positions.size / 9 - object.firstTriangle;
        if (triangleCount > 0) {
            this.motionSegments.add(new DynamicSceneFrame.MotionSegment(
                    element, key, object.firstTriangle, triangleCount));
        }
    }

    VertexSink open(
            VanillaSceneBoundary.Element element,
            PrimitiveTopology topology,
            int textureIndex,
            int fallbackLight) {
        return this.open(element, topology, textureIndex, fallbackLight, false);
    }

    VertexSink open(
            VanillaSceneBoundary.Element element,
            PrimitiveTopology topology,
            int textureIndex,
            int fallbackLight,
            boolean redAlpha) {
        return new VertexSink(
                this,
                element,
                topology,
                textureIndex,
                fallbackLight,
                false,
                redAlpha);
    }

    VertexSink openUntextured(
            VanillaSceneBoundary.Element element,
            PrimitiveTopology topology,
            int fallbackLight) {
        return new VertexSink(
                this, element, topology, 0, fallbackLight, true, false);
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
        int triangleCount = this.positions.size / 9;
        CpuSectionMesh section = new CpuSectionMesh(
                this.positions.toArray(),
                this.primitives.toArray(),
                0,
                triangleCount,
                0,
                OpacityMicromapData.fullyUnknown(triangleCount),
                CpuSectionLights.EMPTY);
        return new DynamicSceneFrame(
                clusterX,
                clusterY,
                clusterZ,
                CpuClusterMesh.fromSegments(List.of(section)),
                textures,
                this.motionSegments,
                this.trianglesByElement[VanillaSceneBoundary.Element.ENTITY.ordinal()],
                this.trianglesByElement[VanillaSceneBoundary.Element.BLOCK_ENTITY.ordinal()],
                this.trianglesByElement[VanillaSceneBoundary.Element.PARTICLE.ordinal()],
                this.trianglesByElement[VanillaSceneBoundary.Element.FEATURE.ordinal()],
                this.compatibilityIssues);
    }

    private void addTriangle(
            VanillaSceneBoundary.Element element,
            Vertex first,
            Vertex second,
            Vertex third,
            int textureIndex,
            boolean bakedMaterial,
            boolean redAlpha) {
        float firstX = (float) (first.x + this.offsetX);
        float firstY = (float) (first.y + this.offsetY);
        float firstZ = (float) (first.z + this.offsetZ);
        float secondX = (float) (second.x + this.offsetX);
        float secondY = (float) (second.y + this.offsetY);
        float secondZ = (float) (second.z + this.offsetZ);
        float thirdX = (float) (third.x + this.offsetX);
        float thirdY = (float) (third.y + this.offsetY);
        float thirdZ = (float) (third.z + this.offsetZ);
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

        this.positions.add(firstX, firstY, firstZ);
        this.positions.add(secondX, secondY, secondZ);
        this.positions.add(thirdX, thirdY, thirdZ);
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
        this.primitives.add(uv0, uv1, uv2, tint);
        this.primitives.add(
                0,
                PrimitivePacking.packDynamicControl(
                        flags, textureIndex, visibleEmission, redAlpha),
                bakedMaterial
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
                                third.v - first.v),
                (int) tangent);
        this.trianglesByElement[element.ordinal()]++;
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
        private final VanillaSceneBoundary.Element element;
        private final PrimitiveTopology topology;
        private final int textureIndex;
        private final int fallbackLight;
        private final boolean bakedMaterial;
        private final boolean redAlpha;
        private final ArrayList<Vertex> vertices = new ArrayList<>();
        private Vertex current;
        private boolean finished;

        private VertexSink(
                DynamicMeshBuilder owner,
                VanillaSceneBoundary.Element element,
                PrimitiveTopology topology,
                int textureIndex,
                int fallbackLight,
                boolean bakedMaterial,
                boolean redAlpha) {
            this.owner = owner;
            this.element = element;
            this.topology = topology;
            this.textureIndex = textureIndex;
            this.fallbackLight = fallbackLight;
            this.bakedMaterial = bakedMaterial;
            this.redAlpha = redAlpha;
        }

        @Override
        public VertexConsumer addVertex(float x, float y, float z) {
            this.commitCurrent();
            this.current = new Vertex(
                    x,
                    y,
                    z,
                    this.fallbackLight,
                    this.owner.openMotionObject);
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
                boolean[] removed = this.resolveReverseQuads(count);
                for (int index = 0; index + 3 < count; index += 4) {
                    if (removed[index / 4]) {
                        continue;
                    }
                    this.emit(index, index + 1, index + 2);
                    this.emit(index, index + 2, index + 3);
                }
            } else if (this.topology == PrimitiveTopology.TRIANGLES) {
                for (int index = 0; index + 2 < count; index += 3) {
                    this.emit(index, index + 1, index + 2);
                }
            } else if (this.topology == PrimitiveTopology.TRIANGLE_STRIP) {
                for (int index = 0; index + 2 < count; index++) {
                    if ((index & 1) == 0) {
                        this.emit(index, index + 1, index + 2);
                    } else {
                        this.emit(index + 1, index, index + 2);
                    }
                }
            } else if (this.topology == PrimitiveTopology.TRIANGLE_FAN) {
                for (int index = 1; index + 1 < count; index++) {
                    this.emit(0, index, index + 1);
                }
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
                    this.element,
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

    private record OpenMotionObject(
            VanillaSceneBoundary.Element element, long key, int firstTriangle) {}

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

        private Vertex(
                float x,
                float y,
                float z,
                int light,
                OpenMotionObject motionObject) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.light = light;
            this.motionObject = motionObject;
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

    private static final class FloatWords {
        private float[] values = new float[1024];
        private int size;

        private void add(float first, float second, float third) {
            this.ensure(3);
            this.values[this.size++] = first;
            this.values[this.size++] = second;
            this.values[this.size++] = third;
        }

        private void ensure(int count) {
            int required = Math.addExact(this.size, count);
            if (required > this.values.length) {
                this.values = java.util.Arrays.copyOf(
                        this.values, Math.max(required, this.values.length * 2));
            }
        }

        private float[] toArray() {
            return java.util.Arrays.copyOf(this.values, this.size);
        }
    }

    private static final class IntWords {
        private int[] values = new int[1024];
        private int size;

        private void add(int first, int second, int third, int fourth) {
            this.ensure(4);
            this.values[this.size++] = first;
            this.values[this.size++] = second;
            this.values[this.size++] = third;
            this.values[this.size++] = fourth;
        }

        private void ensure(int count) {
            int required = Math.addExact(this.size, count);
            if (required > this.values.length) {
                this.values = java.util.Arrays.copyOf(
                        this.values, Math.max(required, this.values.length * 2));
            }
        }

        private int[] toArray() {
            return java.util.Arrays.copyOf(this.values, this.size);
        }
    }
}
