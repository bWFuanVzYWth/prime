package dev.prime.render.terrain;

import dev.prime.render.scene.CapturedSectionGeometry;
import java.math.BigInteger;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Exact topology proof for collision-empty transmissive model components. */
final class TransmissiveTopologyResolver {
    private TransmissiveTopologyResolver() {
    }

    static Result resolve(CapturedCluster cluster) {
        return resolve(
                cluster,
                new ClusterTranslationWork(ClusterTranslationControl.UNINTERRUPTIBLE));
    }

    static Result resolve(CapturedCluster cluster, ClusterTranslationWork work) {
        work.checkpoint();
        IdentityHashMap<CapturedSectionGeometry.Quad, TransmissiveTopology> topology =
                new IdentityHashMap<>();
        Map<CapturedSectionGeometry.BlockFacts, ArrayList<Face>> pending = new HashMap<>();
        Set<StaticCompatibilityIssue> issues = new HashSet<>();
        for (int localIndex = 0; localIndex < SectionCluster.SECTION_COUNT; localIndex++) {
            work.checkpoint();
            CapturedSectionGeometry section = cluster.section(localIndex);
            if (section == null) {
                continue;
            }
            int originX = CapturedCluster.sectionX(localIndex) * 16;
            int originY = CapturedCluster.sectionY(localIndex) * 16;
            int originZ = CapturedCluster.sectionZ(localIndex) * 16;
            for (CapturedSectionGeometry.Quad quad : section.quads()) {
                work.step();
                ClusterSceneTranslator.requireValidAttributes(quad);
                CapturedSectionGeometry.Surface surface = quad.surface();
                if (!ClusterSceneTranslator.isTransmissive(surface)) {
                    topology.put(quad, TransmissiveTopology.NONE);
                } else if (surface.water() || !surface.collisionEmpty()) {
                    topology.put(quad, TransmissiveTopology.SOLID);
                } else if (surface.block() == null) {
                    reject(quad, issues);
                } else {
                    Face face = Face.of(quad, originX, originY, originZ);
                    if (face == null) {
                        reject(quad, issues);
                    } else {
                        pending.computeIfAbsent(surface.block(), ignored -> new ArrayList<>())
                                .add(face);
                    }
                }
            }
        }
        for (List<Face> faces : pending.values()) {
            work.checkpoint();
            classifyComponents(faces, topology, issues, work);
        }
        work.checkpoint();
        return new Result(topology, Set.copyOf(issues));
    }

    private static void classifyComponents(
            List<Face> faces,
            IdentityHashMap<CapturedSectionGeometry.Quad, TransmissiveTopology> topology,
            Set<StaticCompatibilityIssue> issues,
            ClusterTranslationWork work) {
        Map<Edge, ArrayList<Integer>> incidence = new HashMap<>();
        for (int face = 0; face < faces.size(); face++) {
            for (int edge = 0; edge < 4; edge++) {
                work.step();
                incidence.computeIfAbsent(
                                Edge.of(
                                        faces.get(face).positions[edge],
                                        faces.get(face).positions[(edge + 1) & 3]),
                                ignored -> new ArrayList<>())
                        .add(face);
            }
        }
        boolean[] visited = new boolean[faces.size()];
        for (int start = 0; start < faces.size(); start++) {
            work.step();
            if (visited[start]) {
                continue;
            }
            ArrayList<Face> component = new ArrayList<>();
            ArrayDeque<Integer> queue = new ArrayDeque<>();
            visited[start] = true;
            queue.add(start);
            while (!queue.isEmpty()) {
                work.step();
                int faceIndex = queue.removeFirst();
                Face face = faces.get(faceIndex);
                component.add(face);
                for (int edge = 0; edge < 4; edge++) {
                    for (int neighbor : incidence.get(Edge.of(
                            face.positions[edge], face.positions[(edge + 1) & 3]))) {
                        work.step();
                        if (!visited[neighbor]) {
                            visited[neighbor] = true;
                            queue.addLast(neighbor);
                        }
                    }
                }
            }
            TransmissiveTopology resolved = exactReverseSheet(component, work)
                    && coplanar(component, work)
                    ? TransmissiveTopology.THIN_SHEET
                    : closedOrientedManifold(component, work) && !coplanar(component, work)
                            ? TransmissiveTopology.SOLID
                            : null;
            for (Face face : component) {
                work.step();
                if (resolved == null) {
                    reject(face.quad, issues);
                } else {
                    topology.put(face.quad, resolved);
                }
            }
        }
    }

    private static boolean exactReverseSheet(
            List<Face> faces, ClusterTranslationWork work) {
        if (faces.isEmpty() || (faces.size() & 1) != 0) {
            return false;
        }
        boolean[] paired = new boolean[faces.size()];
        for (int first = 0; first < faces.size(); first++) {
            work.step();
            if (paired[first]) {
                continue;
            }
            int peer = -1;
            for (int second = first + 1; second < faces.size(); second++) {
                work.step();
                if (!paired[second]
                        && reverseOffset(faces.get(first), faces.get(second)) >= 0) {
                    peer = second;
                    break;
                }
            }
            if (peer < 0) {
                return false;
            }
            paired[first] = true;
            paired[peer] = true;
        }
        return true;
    }

    private static int reverseOffset(Face first, Face second) {
        for (int offset = 0; offset < 4; offset++) {
            boolean equal = true;
            for (int vertex = 0; vertex < 4; vertex++) {
                if (!first.positions[vertex].equals(
                        second.positions[offset - vertex & 3])) {
                    equal = false;
                    break;
                }
            }
            if (equal) {
                return offset;
            }
        }
        return -1;
    }

    private static boolean closedOrientedManifold(
            List<Face> faces, ClusterTranslationWork work) {
        Map<Edge, ArrayList<DirectedEdge>> edges = new HashMap<>();
        for (Face face : faces) {
            for (int index = 0; index < 4; index++) {
                work.step();
                Position from = face.positions[index];
                Position to = face.positions[(index + 1) & 3];
                if (from.equals(to)) {
                    return false;
                }
                edges.computeIfAbsent(Edge.of(from, to), ignored -> new ArrayList<>())
                        .add(new DirectedEdge(from, to));
            }
        }
        for (List<DirectedEdge> pair : edges.values()) {
            work.step();
            if (pair.size() != 2
                    || !pair.get(0).from.equals(pair.get(1).to)
                    || !pair.get(0).to.equals(pair.get(1).from)) {
                return false;
            }
        }
        return true;
    }

    private static boolean coplanar(
            List<Face> faces, ClusterTranslationWork work) {
        ArrayList<ExactPosition> points = new ArrayList<>(faces.size() * 4);
        for (Face face : faces) {
            for (Position position : face.positions) {
                work.step();
                points.add(ExactPosition.of(position));
            }
        }
        if (points.size() < 3) {
            return true;
        }
        ExactPosition origin = points.getFirst();
        ExactPosition normal = ExactPosition.ZERO;
        boolean foundPlane = false;
        for (int first = 1; first < points.size() && !foundPlane; first++) {
            work.step();
            ExactPosition a = points.get(first).subtract(origin);
            for (int second = first + 1; second < points.size(); second++) {
                work.step();
                normal = a.cross(points.get(second).subtract(origin));
                foundPlane = !normal.zero();
                if (foundPlane) {
                    break;
                }
            }
        }
        if (!foundPlane) {
            return true;
        }
        for (ExactPosition point : points) {
            work.step();
            if (normal.dot(point.subtract(origin)).signum() != 0) {
                return false;
            }
        }
        return true;
    }

    private static void reject(
            CapturedSectionGeometry.Quad quad,
            Set<StaticCompatibilityIssue> issues) {
        if (!quad.peerOnly()) {
            issues.add(new StaticCompatibilityIssue(
                    StaticCompatibilityIssue.Type.AMBIGUOUS_TRANSMISSIVE_TOPOLOGY,
                    quad.surface().sprite().textureId()));
        }
    }

    record Result(
            IdentityHashMap<CapturedSectionGeometry.Quad, TransmissiveTopology> topology,
            Set<StaticCompatibilityIssue> issues) {
        TransmissiveTopology topology(CapturedSectionGeometry.Quad quad) {
            return this.topology.get(quad);
        }
    }

    private record Face(
            CapturedSectionGeometry.Quad quad,
            Position[] positions) {
        static Face of(
                CapturedSectionGeometry.Quad quad,
                int originX,
                int originY,
                int originZ) {
            Position[] positions = new Position[4];
            for (int vertex = 0; vertex < 4; vertex++) {
                positions[vertex] = new Position(
                        quad.x(vertex) + originX,
                        quad.y(vertex) + originY,
                        quad.z(vertex) + originZ);
                if (!positions[vertex].finite()) {
                    return null;
                }
            }
            Face face = new Face(quad, positions);
            return face.hasArea() ? face : null;
        }

        private boolean hasArea() {
            ExactPosition origin = ExactPosition.of(this.positions[0]);
            for (int first = 1; first < this.positions.length - 1; first++) {
                ExactPosition a = ExactPosition.of(this.positions[first]).subtract(origin);
                for (int second = first + 1; second < this.positions.length; second++) {
                    ExactPosition b = ExactPosition.of(this.positions[second]).subtract(origin);
                    if (!a.cross(b).zero()) {
                        return true;
                    }
                }
            }
            return false;
        }
    }

    private record Position(float x, float y, float z) {
        Position {
            x = x == 0.0F ? 0.0F : x;
            y = y == 0.0F ? 0.0F : y;
            z = z == 0.0F ? 0.0F : z;
        }

        boolean finite() {
            return Float.isFinite(this.x)
                    && Float.isFinite(this.y)
                    && Float.isFinite(this.z);
        }
    }

    /** Exact f32 coordinates in common 2^-149 units for topology predicates. */
    private record ExactPosition(BigInteger x, BigInteger y, BigInteger z) {
        private static final ExactPosition ZERO = new ExactPosition(
                BigInteger.ZERO, BigInteger.ZERO, BigInteger.ZERO);

        static ExactPosition of(Position position) {
            return new ExactPosition(
                    exactFloat(position.x),
                    exactFloat(position.y),
                    exactFloat(position.z));
        }

        ExactPosition subtract(ExactPosition other) {
            return new ExactPosition(
                    this.x.subtract(other.x),
                    this.y.subtract(other.y),
                    this.z.subtract(other.z));
        }

        ExactPosition cross(ExactPosition other) {
            return new ExactPosition(
                    this.y.multiply(other.z).subtract(this.z.multiply(other.y)),
                    this.z.multiply(other.x).subtract(this.x.multiply(other.z)),
                    this.x.multiply(other.y).subtract(this.y.multiply(other.x)));
        }

        BigInteger dot(ExactPosition other) {
            return this.x.multiply(other.x)
                    .add(this.y.multiply(other.y))
                    .add(this.z.multiply(other.z));
        }

        boolean zero() {
            return this.x.signum() == 0
                    && this.y.signum() == 0
                    && this.z.signum() == 0;
        }

        private static BigInteger exactFloat(float value) {
            int bits = Float.floatToRawIntBits(value);
            int exponent = bits >>> 23 & 0xff;
            int fraction = bits & 0x7f_ffff;
            long significand = exponent == 0 ? fraction : 0x80_0000L | fraction;
            BigInteger exact = BigInteger.valueOf(significand);
            if (exponent > 0) {
                exact = exact.shiftLeft(exponent - 1);
            }
            return bits < 0 ? exact.negate() : exact;
        }
    }

    private record Edge(Position minimum, Position maximum) {
        static Edge of(Position first, Position second) {
            return compare(first, second) <= 0
                    ? new Edge(first, second)
                    : new Edge(second, first);
        }

        private static int compare(Position first, Position second) {
            int result = Float.compare(first.x, second.x);
            if (result == 0) {
                result = Float.compare(first.y, second.y);
            }
            return result == 0 ? Float.compare(first.z, second.z) : result;
        }
    }

    private record DirectedEdge(Position from, Position to) {
    }
}
