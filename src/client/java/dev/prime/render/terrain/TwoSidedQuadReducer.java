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
 * definition with independent material mappings. In both cases authored winding remains the
 * normal authority.
 */
final class TwoSidedQuadReducer {
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
        Map<PositionSet, ArrayList<Integer>> pending = new HashMap<>();
        for (int index = 0; index < quads.size(); index++) {
            work.step();
            CapturedSectionGeometry.Quad quad =
                    Objects.requireNonNull(quads.get(index), "quad");
            if (topology.get(quad) == null) {
                continue;
            }
            if (!eligible(quad)) {
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
            candidates.remove(match);
            removed[index] = true;
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

    private static boolean eligible(CapturedSectionGeometry.Quad quad) {
        CapturedSectionGeometry.Surface surface = quad.surface();
        return surface.fluid() != null
                || ClusterSceneTranslator.isCutout(surface)
                || ClusterSceneTranslator.isTransmissive(surface);
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
        if (reverseOffset < 0
                || !opposedNormals(first, second)
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
