package dev.prime.render.terrain;

import dev.prime.render.scene.CapturedSectionGeometry;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Collapses raster front/back quad pairs into one physical ray-tracing sheet.
 *
 * <p>Minecraft's cross models and fluid renderer author coincident reverse faces because
 * rasterization culls back faces. Equal material mappings collapse to one physical two-sided
 * sheet. Distinct front/back mappings, such as a sunflower disc, become one bilateral surface
 * definition with independent material mappings. In both cases the retained face's authored
 * outward direction is lowered into canonical BLAS winding before publication.
 */
final class TwoSidedQuadReducer {
    // Vanilla's cube_all_inner_faces model authors its reverse shell at [0.002, 15.998].
    // Recognize that exact model-space contract after block/Section translation; this is not a
    // general near-plane tolerance.
    private static final float INNER_FACE_MODEL_OFFSET = 0.002F / 16.0F;

    private TwoSidedQuadReducer() {
    }

    static List<CapturedSectionGeometry.Quad> reduce(
            List<CapturedSectionGeometry.Quad> quads) {
        List<ResolvedQuad> resolved = resolve(quads);
        if (resolved.size() == quads.size()) {
            return quads;
        }
        ArrayList<CapturedSectionGeometry.Quad> result =
                new ArrayList<>(resolved.size());
        for (ResolvedQuad quad : resolved) {
            result.add(quad.quad());
        }
        return List.copyOf(result);
    }

    static List<ResolvedQuad> resolve(
            List<CapturedSectionGeometry.Quad> quads) {
        ClusterTranslationWork work = new ClusterTranslationWork(
                ClusterTranslationControl.UNINTERRUPTIBLE);
        java.util.IdentityHashMap<CapturedSectionGeometry.Quad, TransmissiveTopology> topology =
                new java.util.IdentityHashMap<>();
        for (CapturedSectionGeometry.Quad quad : quads) {
            work.step();
            topology.put(
                    quad,
                    ClusterSceneTranslator.isTransmissive(quad.surface())
                            ? TransmissiveTopology.SOLID
                            : TransmissiveTopology.NONE);
        }
        return resolve(quads, topology, work);
    }

    static List<ResolvedQuad> resolve(
            List<CapturedSectionGeometry.Quad> quads,
            java.util.IdentityHashMap<CapturedSectionGeometry.Quad, TransmissiveTopology> topology) {
        return resolve(
                quads,
                topology,
                new ClusterTranslationWork(ClusterTranslationControl.UNINTERRUPTIBLE));
    }

    static List<ResolvedQuad> resolve(
            List<CapturedSectionGeometry.Quad> quads,
            java.util.IdentityHashMap<CapturedSectionGeometry.Quad, TransmissiveTopology> topology,
            ClusterTranslationWork work) {
        Objects.requireNonNull(quads, "quads");
        Objects.requireNonNull(topology, "topology");
        Objects.requireNonNull(work, "work");
        work.checkpoint();
        boolean[] removed = new boolean[quads.size()];
        int[] exactPeer = new int[quads.size()];
        int[] exactOffset = new int[quads.size()];
        Arrays.fill(exactPeer, -1);
        Map<PositionSet, ArrayList<Integer>> pending = new HashMap<>();
        for (int index = 0; index < quads.size(); index++) {
            work.step();
            CapturedSectionGeometry.Quad quad =
                    Objects.requireNonNull(quads.get(index), "quad");
            if (topology.get(quad) == null) {
                continue;
            }
            PositionSet key = PositionSet.of(quad);
            ArrayList<Integer> candidates =
                    pending.computeIfAbsent(key, ignored -> new ArrayList<>());
            int match = -1;
            for (int candidate = candidates.size() - 1;
                    candidate >= 0;
                    candidate--) {
                work.step();
                CapturedSectionGeometry.Quad previous = quads.get(candidates.get(candidate));
                if (formsIdenticalRasterDuplicate(previous, quad)) {
                    match = candidate;
                    break;
                }
            }
            if (match < 0) {
                candidates.add(index);
                continue;
            }
            removed[index] = true;
        }

        pending.clear();
        for (int index = 0; index < quads.size(); index++) {
            work.step();
            if (removed[index]) {
                continue;
            }
            CapturedSectionGeometry.Quad quad = quads.get(index);
            if (topology.get(quad) == null) {
                continue;
            }
            PositionSet key = PositionSet.of(quad);
            ArrayList<Integer> candidates =
                    pending.computeIfAbsent(key, ignored -> new ArrayList<>());
            int match = -1;
            for (int candidate = candidates.size() - 1;
                    candidate >= 0;
                    candidate--) {
                work.step();
                if (formsRasterPair(quads.get(candidates.get(candidate)), quad)) {
                    match = candidate;
                    break;
                }
            }
            if (match < 0) {
                candidates.add(index);
                continue;
            }
            int pairedIndex = candidates.remove(match);
            removed[index] = true;
            if (quad.surface().lightEmission() != 0) {
                exactPeer[pairedIndex] = index;
                exactOffset[pairedIndex] = reverseOffset(quads.get(pairedIndex), quad);
            }
        }

        Map<CapturedSectionGeometry.BlockFacts, ArrayList<Integer>> innerShells =
                new HashMap<>();
        for (int index = 0; index < quads.size(); index++) {
            work.step();
            if (removed[index]) {
                continue;
            }
            CapturedSectionGeometry.Quad quad = quads.get(index);
            if (!innerShellEligible(quad)) {
                continue;
            }
            ArrayList<Integer> candidates = innerShells.computeIfAbsent(
                    quad.surface().block(), ignored -> new ArrayList<>());
            int match = -1;
            boolean currentIsOuter = false;
            for (int candidate = candidates.size() - 1; candidate >= 0; candidate--) {
                work.step();
                CapturedSectionGeometry.Quad previous = quads.get(candidates.get(candidate));
                int relation = authoredInnerShellRelation(previous, quad);
                if (relation != 0) {
                    match = candidate;
                    currentIsOuter = relation < 0;
                    break;
                }
            }
            if (match < 0) {
                candidates.add(index);
                continue;
            }
            int previousIndex = candidates.remove(match);
            int retainedIndex;
            int removedIndex;
            if (currentIsOuter) {
                removed[previousIndex] = true;
                candidates.add(index);
                retainedIndex = index;
                removedIndex = previousIndex;
            } else {
                removed[index] = true;
                retainedIndex = previousIndex;
                removedIndex = index;
            }
            if (quad.surface().lightEmission() != 0) {
                CapturedSectionGeometry.Quad retained = quads.get(retainedIndex);
                CapturedSectionGeometry.Quad peer = quads.get(removedIndex);
                exactPeer[retainedIndex] = removedIndex;
                exactOffset[retainedIndex] = authoredInnerShellReverseOffset(retained, peer);
            }
        }

        int[] bilateralPeer = new int[quads.size()];
        int[] bilateralOffset = new int[quads.size()];
        Arrays.fill(bilateralPeer, -1);
        pending.clear();
        for (int index = 0; index < quads.size(); index++) {
            work.step();
            if (removed[index]) {
                continue;
            }
            CapturedSectionGeometry.Quad quad = quads.get(index);
            if (topology.get(quad) == null) {
                continue;
            }
            if (!directionalEligible(quad)) {
                continue;
            }
            PositionSet key = PositionSet.of(quad);
            ArrayList<Integer> candidates =
                    pending.computeIfAbsent(key, ignored -> new ArrayList<>());
            int match = -1;
            for (int candidate = candidates.size() - 1;
                    candidate >= 0;
                    candidate--) {
                work.step();
                if (formsDirectionalPair(
                        quads.get(candidates.get(candidate)), quad)) {
                    match = candidate;
                    break;
                }
            }
            if (match < 0) {
                candidates.add(index);
                continue;
            }
            int pairedIndex = candidates.remove(match);
            int reverseOffset = reverseOffset(quads.get(pairedIndex), quad);
            removed[index] = true;
            bilateralPeer[pairedIndex] = index;
            bilateralOffset[pairedIndex] = reverseOffset;
        }

        ArrayList<ResolvedQuad> result =
                new ArrayList<>(quads.size());
        for (int index = 0; index < quads.size(); index++) {
            work.step();
            if (!removed[index]) {
                CapturedSectionGeometry.Quad quad = quads.get(index);
                TransmissiveTopology primaryTopology = topology.get(quad);
                if (primaryTopology == null) {
                    continue;
                }
                SurfaceDefinition.MaterialBinding primary =
                        SurfaceDefinition.MaterialBinding.of(quad, primaryTopology);
                int peer = bilateralPeer[index];
                if (peer < 0) {
                    peer = exactPeer[index];
                    if (peer >= 0) {
                        bilateralOffset[index] = exactOffset[index];
                    }
                }
                SurfaceDefinition definition = peer < 0
                        ? SurfaceDefinition.single(primary)
                        : SurfaceDefinition.bilateral(
                                primary,
                                bindingInPrimaryOrder(
                                        quads.get(peer),
                                        bilateralOffset[index],
                                        topology.get(quads.get(peer))));
                result.add(new ResolvedQuad(quad, definition));
            }
        }
        work.checkpoint();
        return List.copyOf(result);
    }

    private static boolean innerShellEligible(CapturedSectionGeometry.Quad quad) {
        CapturedSectionGeometry.Surface surface = quad.surface();
        return surface.block() != null
                && surface.fluid() == null;
    }

    /** Returns 1 when first is outer, -1 when second is outer, and 0 when there is no proof. */
    private static int authoredInnerShellRelation(
            CapturedSectionGeometry.Quad first,
            CapturedSectionGeometry.Quad second) {
        if (!sameSurface(first.surface(), second.surface())
                || !opposedNormals(first, second)
                || !sameUvCorners(first, second)) {
            return 0;
        }
        int reverseOffset = authoredInnerShellReverseOffset(first, second);
        if (reverseOffset < 0) {
            return 0;
        }
        for (int vertex = 0; vertex < 4; vertex++) {
            if (first.surface().color(vertex)
                    != second.surface().color(reverseIndex(reverseOffset, vertex))) {
                return 0;
            }
        }
        float firstSpan = coordinateSpan(first);
        float secondSpan = coordinateSpan(second);
        float tolerance = Math.max(Math.ulp(firstSpan), Math.ulp(secondSpan));
        if (Math.abs(firstSpan - secondSpan) <= tolerance) {
            return 0;
        }
        return firstSpan > secondSpan ? 1 : -1;
    }

    private static int authoredInnerShellReverseOffset(
            CapturedSectionGeometry.Quad first,
            CapturedSectionGeometry.Quad second) {
        for (int offset = 0; offset < 4; offset++) {
            boolean matches = true;
            for (int vertex = 0; vertex < 4; vertex++) {
                int other = reverseIndex(offset, vertex);
                if (!innerShellCoordinate(first.x(vertex), second.x(other))
                        || !innerShellCoordinate(first.y(vertex), second.y(other))
                        || !innerShellCoordinate(first.z(vertex), second.z(other))) {
                    matches = false;
                    break;
                }
            }
            if (matches) {
                return offset;
            }
        }
        return -1;
    }

    private static boolean innerShellCoordinate(float first, float second) {
        float tolerance = Math.max(Math.ulp(first), Math.ulp(second));
        return Math.abs(Math.abs(first - second) - INNER_FACE_MODEL_OFFSET) <= tolerance;
    }

    private static float coordinateSpan(CapturedSectionGeometry.Quad quad) {
        float result = 0.0F;
        for (int axis = 0; axis < 3; axis++) {
            result += maximum(quad, axis) - minimum(quad, axis);
        }
        return result;
    }

    private static float minimum(CapturedSectionGeometry.Quad quad, int axis) {
        float result = coordinate(quad, axis, 0);
        for (int vertex = 1; vertex < 4; vertex++) {
            result = Math.min(result, coordinate(quad, axis, vertex));
        }
        return result;
    }

    private static float maximum(CapturedSectionGeometry.Quad quad, int axis) {
        float result = coordinate(quad, axis, 0);
        for (int vertex = 1; vertex < 4; vertex++) {
            result = Math.max(result, coordinate(quad, axis, vertex));
        }
        return result;
    }

    private static float coordinate(
            CapturedSectionGeometry.Quad quad, int axis, int vertex) {
        return switch (axis) {
            case 0 -> quad.x(vertex);
            case 1 -> quad.y(vertex);
            default -> quad.z(vertex);
        };
    }

    private static boolean directionalEligible(
            CapturedSectionGeometry.Quad quad) {
        CapturedSectionGeometry.Surface surface = quad.surface();
        return surface.fluid() == null
                && surface.lightEmission() == 0
                && (ClusterSceneTranslator.isCutout(surface)
                        || ClusterSceneTranslator.isTransmissive(surface));
    }

    private static boolean formsRasterPair(
            CapturedSectionGeometry.Quad first,
            CapturedSectionGeometry.Quad second) {
        int reverseOffset = reverseOffset(first, second);
        // Exact reversed positions already prove opposite triangle winding. A warped quad's
        // per-quad normal samples a different triangle after reversal and need not be opposed.
        if (reverseOffset < 0
                || !sameSurface(first.surface(), second.surface())
                || !sameUvCorners(first, second)) {
            return false;
        }
        for (int vertex = 0; vertex < 4; vertex++) {
            int secondVertex = reverseIndex(reverseOffset, vertex);
            if (first.surface().color(vertex)
                    != second.surface().color(secondVertex)) {
                return false;
            }
        }
        return true;
    }

    private static boolean formsIdenticalRasterDuplicate(
            CapturedSectionGeometry.Quad first,
            CapturedSectionGeometry.Quad second) {
        if (first.surface().lightEmission() != 0
                || !sameSurface(first.surface(), second.surface())) {
            return false;
        }
        int offset = sameWindingOffset(first, second);
        if (offset < 0) {
            return false;
        }
        for (int vertex = 0; vertex < 4; vertex++) {
            int other = vertex + offset & 3;
            if (!sameFloat(first.u(vertex), second.u(other))
                    || !sameFloat(first.v(vertex), second.v(other))
                    || first.surface().color(vertex) != second.surface().color(other)) {
                return false;
            }
        }
        return true;
    }

    private static int sameWindingOffset(
            CapturedSectionGeometry.Quad first,
            CapturedSectionGeometry.Quad second) {
        for (int offset = 0; offset < 4; offset++) {
            boolean matches = true;
            for (int vertex = 0; vertex < 4; vertex++) {
                if (!samePosition(first, vertex, second, vertex + offset & 3)) {
                    matches = false;
                    break;
                }
            }
            if (matches) {
                return offset;
            }
        }
        return -1;
    }

    private static boolean formsDirectionalPair(
            CapturedSectionGeometry.Quad first,
            CapturedSectionGeometry.Quad second) {
        return reverseOffset(first, second) >= 0
                && opposedNormals(first, second)
                && sameDirectionalSemantics(first.surface(), second.surface());
    }

    private static int reverseOffset(
            CapturedSectionGeometry.Quad first,
            CapturedSectionGeometry.Quad second) {
        for (int offset = 0; offset < 4; offset++) {
            boolean matches = true;
            for (int vertex = 0; vertex < 4; vertex++) {
                if (!samePosition(
                        first,
                        vertex,
                        second,
                        reverseIndex(offset, vertex))) {
                    matches = false;
                    break;
                }
            }
            if (matches) {
                return offset;
            }
        }
        return -1;
    }

    private static int reverseIndex(int offset, int vertex) {
        return offset - vertex & 3;
    }

    private static SurfaceDefinition.MaterialBinding bindingInPrimaryOrder(
            CapturedSectionGeometry.Quad peer,
            int reverseOffset,
            TransmissiveTopology topology) {
        int index0 = reverseIndex(reverseOffset, 0);
        int index1 = reverseIndex(reverseOffset, 1);
        int index2 = reverseIndex(reverseOffset, 2);
        int index3 = reverseIndex(reverseOffset, 3);
        return new SurfaceDefinition.MaterialBinding(
                peer.surface(),
                new SurfaceDefinition.UvMapping(
                        peer.u(index0), peer.v(index0),
                        peer.u(index1), peer.v(index1),
                        peer.u(index2), peer.v(index2),
                        peer.u(index3), peer.v(index3)),
                topology);
    }

    private static boolean opposedNormals(
            CapturedSectionGeometry.Quad first,
            CapturedSectionGeometry.Quad second) {
        float firstLengthSquared =
                first.normalX() * first.normalX()
                        + first.normalY() * first.normalY()
                        + first.normalZ() * first.normalZ();
        float secondLengthSquared =
                second.normalX() * second.normalX()
                        + second.normalY() * second.normalY()
                        + second.normalZ() * second.normalZ();
        float dot = first.normalX() * second.normalX()
                + first.normalY() * second.normalY()
                + first.normalZ() * second.normalZ();
        return firstLengthSquared > 1.0e-20F
                && secondLengthSquared > 1.0e-20F
                && dot < 0.0F
                && dot * dot >= 0.999F * firstLengthSquared * secondLengthSquared;
    }

    private static boolean sameSurface(
            CapturedSectionGeometry.Surface first,
            CapturedSectionGeometry.Surface second) {
        return first.layer() == second.layer()
                && first.alphaCutOverride() == second.alphaCutOverride()
                && first.collisionEmpty() == second.collisionEmpty()
                && first.animated() == second.animated()
                && first.water() == second.water()
                && first.foliage() == second.foliage()
                && first.mergeable() == second.mergeable()
                && first.rasterOverlay() == second.rasterOverlay()
                && first.lightEmission() == second.lightEmission()
                && first.sprite().equals(second.sprite())
                && Objects.equals(first.fluid(), second.fluid())
                && Objects.equals(first.block(), second.block());
    }

    private static boolean sameDirectionalSemantics(
            CapturedSectionGeometry.Surface first,
            CapturedSectionGeometry.Surface second) {
        return first.layer() == second.layer()
                && first.alphaCutOverride() == second.alphaCutOverride()
                && first.collisionEmpty() == second.collisionEmpty()
                && first.animated() == second.animated()
                && first.water() == second.water()
                && first.foliage() == second.foliage()
                && first.mergeable() == second.mergeable()
                && first.rasterOverlay() == second.rasterOverlay()
                && first.lightEmission() == 0
                && second.lightEmission() == 0
                && first.fluid() == null
                && second.fluid() == null
                && Objects.equals(first.block(), second.block());
    }

    private static boolean sameUvCorners(
            CapturedSectionGeometry.Quad first,
            CapturedSectionGeometry.Quad second) {
        boolean[] matched = new boolean[4];
        for (int firstVertex = 0; firstVertex < 4; firstVertex++) {
            int match = -1;
            for (int secondVertex = 0; secondVertex < 4; secondVertex++) {
                if (!matched[secondVertex]
                        && sameFloat(first.u(firstVertex), second.u(secondVertex))
                        && sameFloat(first.v(firstVertex), second.v(secondVertex))) {
                    match = secondVertex;
                    break;
                }
            }
            if (match < 0) {
                return false;
            }
            matched[match] = true;
        }
        return true;
    }

    private static boolean samePosition(
            CapturedSectionGeometry.Quad first,
            int firstVertex,
            CapturedSectionGeometry.Quad second,
            int secondVertex) {
        return sameFloat(first.x(firstVertex), second.x(secondVertex))
                && sameFloat(first.y(firstVertex), second.y(secondVertex))
                && sameFloat(first.z(firstVertex), second.z(secondVertex));
    }

    private static boolean sameFloat(float first, float second) {
        return first == second;
    }

    record ResolvedQuad(
            CapturedSectionGeometry.Quad quad,
            SurfaceDefinition definition) {
        ResolvedQuad {
            Objects.requireNonNull(quad, "quad");
            Objects.requireNonNull(definition, "definition");
        }

    }

    private record Position(int x, int y, int z) implements Comparable<Position> {
        static Position of(CapturedSectionGeometry.Quad quad, int vertex) {
            return new Position(
                    bits(quad.x(vertex)),
                    bits(quad.y(vertex)),
                    bits(quad.z(vertex)));
        }

        private static int bits(float value) {
            return Float.floatToIntBits(value == 0.0F ? 0.0F : value);
        }

        @Override
        public int compareTo(Position other) {
            int result = Integer.compare(this.x, other.x);
            if (result == 0) {
                result = Integer.compare(this.y, other.y);
            }
            return result == 0 ? Integer.compare(this.z, other.z) : result;
        }
    }

    private record PositionSet(List<Position> positions) {
        static PositionSet of(CapturedSectionGeometry.Quad quad) {
            Position[] positions = new Position[4];
            for (int vertex = 0; vertex < 4; vertex++) {
                positions[vertex] = Position.of(quad, vertex);
            }
            Arrays.sort(positions);
            return new PositionSet(List.of(positions));
        }
    }
}
