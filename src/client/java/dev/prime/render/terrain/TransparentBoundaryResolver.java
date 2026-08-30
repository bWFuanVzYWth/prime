package dev.prime.render.terrain;

import dev.prime.render.scene.CapturedSectionGeometry;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Resolves exact block-owned contact faces into one physical, locally clipped boundary. */
final class TransparentBoundaryResolver {
    private static final float POSITION_EPSILON = 1.0E-5F;
    private static final float NORMAL_EPSILON = 1.0E-4F;
    private static final float ATTACHED_SURFACE_EPSILON = 1.0E-3F;
    private static final float FLUID_SIDE_INSET = 0.001F;

    private TransparentBoundaryResolver() {
    }

    static Result resolve(
            CapturedCluster cluster,
            boolean resolveStaticOverlays) {
        return resolve(
                cluster,
                resolveStaticOverlays,
                new ClusterTranslationWork(ClusterTranslationControl.UNINTERRUPTIBLE));
    }

    static Result resolve(
            CapturedCluster cluster,
            boolean resolveStaticOverlays,
            ClusterTranslationWork work) {
        Objects.requireNonNull(cluster, "cluster");
        Objects.requireNonNull(work, "work");
        work.checkpoint();
        TransmissiveTopologyResolver.Result topologies =
                TransmissiveTopologyResolver.resolve(cluster, work);
        @SuppressWarnings("unchecked")
        ArrayList<ResolvedQuad>[] sections =
                (ArrayList<ResolvedQuad>[]) new ArrayList<?>[SectionCluster.SECTION_COUNT];
        Map<BoundaryKey, BoundaryGroup> groups = new HashMap<>();
        int clusterWorldX = cluster.clusterX() << 4;
        int clusterWorldY = cluster.clusterY() << 4;
        int clusterWorldZ = cluster.clusterZ() << 4;
        for (int localIndex = 0;
                localIndex < SectionCluster.SECTION_COUNT;
                localIndex++) {
            work.checkpoint();
            CapturedSectionGeometry section = cluster.section(localIndex);
            if (section == null) {
                continue;
            }
            sections[localIndex] = new ArrayList<>();
            int originX = CapturedCluster.sectionX(localIndex) * 16;
            int originY = CapturedCluster.sectionY(localIndex) * 16;
            int originZ = CapturedCluster.sectionZ(localIndex) * 16;
            List<TwoSidedQuadReducer.ResolvedQuad> reduced =
                    TwoSidedQuadReducer.resolve(
                            section.quads(), topologies.topology(), work);
            reduced = resolveExactOverlays(reduced, resolveStaticOverlays, work);
            for (TwoSidedQuadReducer.ResolvedQuad resolved : reduced) {
                work.step();
                Candidate candidate = Candidate.tryCreate(
                        localIndex,
                        originX,
                        originY,
                        originZ,
                        clusterWorldX,
                        clusterWorldY,
                        clusterWorldZ,
                        resolved);
                if (candidate == null) {
                    if (!resolved.quad().peerOnly()) {
                        sections[localIndex].add(ResolvedQuad.full(resolved));
                    }
                    continue;
                }
                groups.computeIfAbsent(
                                candidate.key,
                                ignored -> new BoundaryGroup())
                        .add(candidate);
            }
        }
        for (BoundaryGroup group : groups.values()) {
            work.checkpoint();
            group.resolve(sections, work);
        }
        @SuppressWarnings("unchecked")
        List<ResolvedQuad>[] immutable =
                (List<ResolvedQuad>[]) new List<?>[sections.length];
        for (int index = 0; index < sections.length; index++) {
            immutable[index] = sections[index] == null
                    ? List.of()
                    : coalesce(sections[index], work);
        }
        work.checkpoint();
        return new Result(immutable, topologies.issues());
    }

    private static List<TwoSidedQuadReducer.ResolvedQuad> resolveExactOverlays(
            List<TwoSidedQuadReducer.ResolvedQuad> quads,
            boolean resolveRasterOverlays,
            ClusterTranslationWork work) {
        boolean[] removed = new boolean[quads.size()];
        OverlaySpatialIndex index = new OverlaySpatialIndex(quads, work);
        ArrayList<TwoSidedQuadReducer.ResolvedQuad> result =
                new ArrayList<>(quads.size());
        for (int first = 0; first < quads.size(); first++) {
            work.step();
            if (removed[first]) {
                continue;
            }
            TwoSidedQuadReducer.ResolvedQuad a = quads.get(first);
            if (!layerable(a.definition())) {
                result.add(a);
                continue;
            }
            OverlayMatch match = index.firstMatch(
                    first, a, removed, resolveRasterOverlays, work);
            if (match == null) {
                result.add(a);
                continue;
            }
            removed[match.index] = true;
            result.add(new TwoSidedQuadReducer.ResolvedQuad(
                    match.geometry,
                    SurfaceDefinition.overlay(
                            match.overlay, match.substrate, false)));
        }
        return List.copyOf(result);
    }

    private static boolean layerable(SurfaceDefinition definition) {
        return definition instanceof SurfaceDefinition.Single
                || definition instanceof SurfaceDefinition.Bilateral bilateral
                        && bilateral.primary().surface().equals(
                                bilateral.secondary().surface())
                        && sameUvDomain(
                                bilateral.primary().uv(), bilateral.secondary().uv());
    }

    private static boolean sameUvDomain(
            SurfaceDefinition.UvMapping first,
            SurfaceDefinition.UvMapping second) {
        boolean[] matched = new boolean[4];
        for (int vertex = 0; vertex < 4; vertex++) {
            int match = -1;
            for (int other = 0; other < 4; other++) {
                if (!matched[other]
                        && first.u(vertex) == second.u(other)
                        && first.v(vertex) == second.v(other)) {
                    match = other;
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

    /**
     * Coarse three-dimensional bins are only a rejection index; exact epsilon and material
     * predicates remain authoritative. Corresponding quad bounds differ by at most the position
     * epsilon, so a two-epsilon cell and its 26 neighbors contain every legal match.
     */
    private static final class OverlaySpatialIndex {
        private static final double CELL_SIZE = 2.0 * POSITION_EPSILON;

        private final List<TwoSidedQuadReducer.ResolvedQuad> quads;
        private final Map<OverlayBin, ArrayList<Integer>> bins = new HashMap<>();

        OverlaySpatialIndex(
                List<TwoSidedQuadReducer.ResolvedQuad> quads,
                ClusterTranslationWork work) {
            this.quads = quads;
            for (int index = 0; index < quads.size(); index++) {
                work.step();
                this.bins.computeIfAbsent(
                                OverlayBin.of(quads.get(index).quad()),
                                ignored -> new ArrayList<>())
                        .add(index);
            }
        }

        OverlayMatch firstMatch(
                int first,
                TwoSidedQuadReducer.ResolvedQuad source,
                boolean[] removed,
                boolean resolveRasterOverlays,
                ClusterTranslationWork work) {
            OverlayBin center = OverlayBin.of(source.quad());
            OverlayMatch best = null;
            for (int deltaX = -1; deltaX <= 1; deltaX++) {
                for (int deltaY = -1; deltaY <= 1; deltaY++) {
                    for (int deltaZ = -1; deltaZ <= 1; deltaZ++) {
                        ArrayList<Integer> candidates = this.bins.get(center.offset(
                                deltaX, deltaY, deltaZ));
                        if (candidates == null) {
                            continue;
                        }
                        for (int second : candidates) {
                            if (second <= first) {
                                continue;
                            }
                            if (best != null && second >= best.index) {
                                break;
                            }
                            work.step();
                            if (removed[second]) {
                                continue;
                            }
                            TwoSidedQuadReducer.ResolvedQuad target = this.quads.get(second);
                            SurfaceDefinition.MaterialBinding[] pair =
                                    exactOverlayPair(
                                            source, target, resolveRasterOverlays);
                            if (pair != null) {
                                best = new OverlayMatch(
                                        second,
                                        pair[0],
                                        pair[1],
                                        source.quad().surface().rasterOverlay()
                                                ? target.quad()
                                                : source.quad());
                            }
                        }
                    }
                }
            }
            return best;
        }

        private static long bin(float value) {
            double coordinate = Math.floor(value / CELL_SIZE);
            if (coordinate <= Long.MIN_VALUE) {
                return Long.MIN_VALUE;
            }
            if (coordinate >= Long.MAX_VALUE) {
                return Long.MAX_VALUE;
            }
            return (long) coordinate;
        }
    }

    private record OverlayBin(long x, long y, long z) {
        static OverlayBin of(CapturedSectionGeometry.Quad quad) {
            float minimumX = Float.POSITIVE_INFINITY;
            float minimumY = Float.POSITIVE_INFINITY;
            float minimumZ = Float.POSITIVE_INFINITY;
            for (int vertex = 0; vertex < 4; vertex++) {
                minimumX = Math.min(minimumX, quad.x(vertex));
                minimumY = Math.min(minimumY, quad.y(vertex));
                minimumZ = Math.min(minimumZ, quad.z(vertex));
            }
            return new OverlayBin(
                    OverlaySpatialIndex.bin(minimumX),
                    OverlaySpatialIndex.bin(minimumY),
                    OverlaySpatialIndex.bin(minimumZ));
        }

        OverlayBin offset(int deltaX, int deltaY, int deltaZ) {
            return new OverlayBin(
                    offset(this.x, deltaX),
                    offset(this.y, deltaY),
                    offset(this.z, deltaZ));
        }

        private static long offset(long value, int delta) {
            if (delta < 0 && value == Long.MIN_VALUE
                    || delta > 0 && value == Long.MAX_VALUE) {
                return value;
            }
            return value + delta;
        }
    }

    private record OverlayMatch(
            int index,
            SurfaceDefinition.MaterialBinding overlay,
            SurfaceDefinition.MaterialBinding substrate,
            CapturedSectionGeometry.Quad geometry) {
    }

    private static SurfaceDefinition.MaterialBinding[] exactOverlayPair(
            TwoSidedQuadReducer.ResolvedQuad first,
            TwoSidedQuadReducer.ResolvedQuad second,
            boolean resolveRasterOverlays) {
        CapturedSectionGeometry.Quad a = first.quad();
        CapturedSectionGeometry.Quad b = second.quad();
        boolean aOverlay = a.surface().rasterOverlay();
        boolean bOverlay = b.surface().rasterOverlay();
        boolean rasterPair = aOverlay != bOverlay;
        boolean emissivePair = !aOverlay
                && !bOverlay
                && Objects.equals(a.surface().block(), b.surface().block())
                && ClusterSceneTranslator.isCutout(a.surface())
                && ClusterSceneTranslator.isCutout(b.surface())
                && a.surface().lightEmission() != b.surface().lightEmission();
        if ((!rasterPair && !emissivePair)
                || rasterPair && !resolveRasterOverlays
                || a.normalX() * b.normalX()
                                + a.normalY() * b.normalY()
                                + a.normalZ() * b.normalZ()
                        <= 0.0F) {
            return null;
        }
        if (emissivePair) {
            aOverlay = a.surface().lightEmission() > b.surface().lightEmission();
            bOverlay = !aOverlay;
        }
        TwoSidedQuadReducer.ResolvedQuad substrateResolved = aOverlay ? second : first;
        FaceKind substrateKind = FaceKind.of(
                (aOverlay ? b : a).surface(),
                substrateResolved.definition().primary().transmissiveTopology());
        if (substrateKind.transmissive) {
            return null;
        }
        CapturedSectionGeometry.Quad geometry = aOverlay ? b : a;
        CapturedSectionGeometry.Quad layer = aOverlay ? a : b;
        TwoSidedQuadReducer.ResolvedQuad layerResolved = aOverlay ? first : second;
        TwoSidedQuadReducer.ResolvedQuad geometryResolved = aOverlay ? second : first;
        int[] mapping = sameWindingMapping(geometry, layer);
        if (mapping == null) {
            return null;
        }
        SurfaceDefinition.UvMapping overlayUv = new SurfaceDefinition.UvMapping(
                layer.u(mapping[0]), layer.v(mapping[0]),
                layer.u(mapping[1]), layer.v(mapping[1]),
                layer.u(mapping[2]), layer.v(mapping[2]),
                layer.u(mapping[3]), layer.v(mapping[3]));
        return new SurfaceDefinition.MaterialBinding[] {
            new SurfaceDefinition.MaterialBinding(
                    layer.surface(),
                    overlayUv,
                    layerResolved.definition().primary().transmissiveTopology()),
            new SurfaceDefinition.MaterialBinding(
                    geometry.surface(),
                    SurfaceDefinition.UvMapping.of(geometry),
                    geometryResolved.definition().primary().transmissiveTopology())
        };
    }

    private static int[] sameWindingMapping(
            CapturedSectionGeometry.Quad geometry,
            CapturedSectionGeometry.Quad layer) {
        for (int offset = 0; offset < 4; offset++) {
            int[] mapping = new int[4];
            boolean matches = true;
            for (int vertex = 0; vertex < 4; vertex++) {
                int other = vertex + offset & 3;
                mapping[vertex] = other;
                if (!near(geometry.x(vertex), layer.x(other))
                        || !near(geometry.y(vertex), layer.y(other))
                        || !near(geometry.z(vertex), layer.z(other))) {
                    matches = false;
                    break;
                }
            }
            if (matches) {
                return mapping;
            }
        }
        return null;
    }

    private static List<ResolvedQuad> coalesce(
            List<ResolvedQuad> source, ClusterTranslationWork work) {
        ResolvedQuad[] output = new ResolvedQuad[source.size()];
        // merge() requires both identities to match. Those groups are independent, and retaining
        // each survivor's first source index preserves the original greedy result and output order.
        java.util.IdentityHashMap<
                        Candidate,
                        java.util.IdentityHashMap<
                                SurfaceDefinition, ArrayList<IndexedResolvedQuad>>>
                groups = new java.util.IdentityHashMap<>();
        for (int index = 0; index < source.size(); index++) {
            work.step();
            ResolvedQuad quad = source.get(index);
            if (quad.candidate == null) {
                output[index] = quad;
                continue;
            }
            groups.computeIfAbsent(
                            quad.candidate, ignored -> new java.util.IdentityHashMap<>())
                    .computeIfAbsent(quad.definition, ignored -> new ArrayList<>())
                    .add(new IndexedResolvedQuad(index, quad));
        }
        for (java.util.IdentityHashMap<SurfaceDefinition, ArrayList<IndexedResolvedQuad>>
                definitions : groups.values()) {
            for (ArrayList<IndexedResolvedQuad> group : definitions.values()) {
                coalesceGroup(group, work);
                for (IndexedResolvedQuad quad : group) {
                    output[quad.index] = quad.quad;
                }
            }
        }
        ArrayList<ResolvedQuad> result = new ArrayList<>(source.size());
        for (ResolvedQuad quad : output) {
            if (quad != null) {
                result.add(quad);
            }
        }
        return List.copyOf(result);
    }

    private static void coalesceGroup(
            ArrayList<IndexedResolvedQuad> result,
            ClusterTranslationWork work) {
        boolean changed;
        do {
            changed = false;
            outer:
            for (int first = 0; first < result.size(); first++) {
                work.step();
                IndexedResolvedQuad a = result.get(first);
                for (int second = first + 1; second < result.size(); second++) {
                    work.step();
                    IndexedResolvedQuad b = result.get(second);
                    ResolvedQuad merged = a.quad.merge(b.quad);
                    if (merged != null) {
                        result.set(first, new IndexedResolvedQuad(a.index, merged));
                        result.remove(second);
                        changed = true;
                        break outer;
                    }
                }
            }
        } while (changed);
    }

    private record IndexedResolvedQuad(int index, ResolvedQuad quad) {
    }

    record Result(
            List<ResolvedQuad>[] sections,
            java.util.Set<StaticCompatibilityIssue> issues) {
        Result {
            issues = java.util.Set.copyOf(issues);
        }

        List<ResolvedQuad> section(int localIndex) {
            return this.sections[localIndex];
        }
    }

    static final class ResolvedQuad {
        private final Candidate candidate;
        private final CapturedSectionGeometry.Quad direct;
        private final SurfaceDefinition definition;
        private final float minimumU;
        private final float maximumU;
        private final float minimumV;
        private final float maximumV;

        private ResolvedQuad(
                Candidate candidate,
                CapturedSectionGeometry.Quad direct,
                SurfaceDefinition definition,
                float minimumU,
                float maximumU,
                float minimumV,
                float maximumV) {
            this.candidate = candidate;
            this.direct = direct;
            this.definition = Objects.requireNonNull(definition, "definition");
            this.minimumU = minimumU;
            this.maximumU = maximumU;
            this.minimumV = minimumV;
            this.maximumV = maximumV;
        }

        static ResolvedQuad full(TwoSidedQuadReducer.ResolvedQuad resolved) {
            return new ResolvedQuad(
                    null,
                    resolved.quad(),
                    resolved.definition(),
                    0.0F,
                    0.0F,
                    0.0F,
                    0.0F);
        }

        CapturedSectionGeometry.Surface surface() {
            return this.definition.primary().surface();
        }

        SurfaceDefinition definition() {
            return this.definition;
        }

        void write(SectionMeshAccumulator.Quad target) {
            if (this.candidate == null) {
                copy(this.direct, target);
            } else {
                this.candidate.writeGeometry(
                        target,
                        this.minimumU,
                        this.maximumU,
                        this.minimumV,
                        this.maximumV);
            }
            SurfaceDefinition.UvMapping uv = this.definition.primary().uv();
            for (int vertex = 0; vertex < 4; vertex++) {
                target.u[vertex] = uv.u(vertex);
                target.v[vertex] = uv.v(vertex);
            }
        }

        ResolvedQuad merge(ResolvedQuad other) {
            if (this.candidate != other.candidate
                    || this.definition != other.definition) {
                return null;
            }
            boolean sameV = near(this.minimumV, other.minimumV)
                    && near(this.maximumV, other.maximumV);
            boolean touchingU = near(this.maximumU, other.minimumU)
                    || near(other.maximumU, this.minimumU);
            boolean sameU = near(this.minimumU, other.minimumU)
                    && near(this.maximumU, other.maximumU);
            boolean touchingV = near(this.maximumV, other.minimumV)
                    || near(other.maximumV, this.minimumV);
            if (!(sameV && touchingU || sameU && touchingV)) {
                return null;
            }
            return new ResolvedQuad(
                    this.candidate,
                    null,
                    this.definition,
                    Math.min(this.minimumU, other.minimumU),
                    Math.max(this.maximumU, other.maximumU),
                    Math.min(this.minimumV, other.minimumV),
                    Math.max(this.maximumV, other.maximumV));
        }

        private static void copy(
                CapturedSectionGeometry.Quad source,
                SectionMeshAccumulator.Quad target) {
            for (int vertex = 0; vertex < 4; vertex++) {
                target.x[vertex] = source.x(vertex);
                target.y[vertex] = source.y(vertex);
                target.z[vertex] = source.z(vertex);
                target.u[vertex] = source.u(vertex);
                target.v[vertex] = source.v(vertex);
            }
            target.normalX = source.normalX();
            target.normalY = source.normalY();
            target.normalZ = source.normalZ();
        }
    }

    private static final class BoundaryGroup {
        private final ArrayList<Candidate> negative = new ArrayList<>();
        private final ArrayList<Candidate> positive = new ArrayList<>();

        void add(Candidate candidate) {
            (candidate.normalSign > 0 ? this.negative : this.positive).add(candidate);
        }

        void resolve(
                ArrayList<ResolvedQuad>[] output,
                ClusterTranslationWork work) {
            if (this.negative.isEmpty() || this.positive.isEmpty()) {
                this.emitWholeActual(this.negative, output);
                this.emitWholeActual(this.positive, output);
                return;
            }
            float[] uEdges = edges(this.negative, this.positive, true);
            float[] vEdges = edges(this.negative, this.positive, false);
            ArrayList<Candidate> negativeCover = new ArrayList<>();
            ArrayList<Candidate> positiveCover = new ArrayList<>();
            for (int v = 0; v + 1 < vEdges.length; v++) {
                work.step();
                float minimumV = vEdges[v];
                float maximumV = vEdges[v + 1];
                if (!(maximumV - minimumV > POSITION_EPSILON)) {
                    continue;
                }
                float centerV = 0.5F * (minimumV + maximumV);
                for (int u = 0; u + 1 < uEdges.length; u++) {
                    work.step();
                    float minimumU = uEdges[u];
                    float maximumU = uEdges[u + 1];
                    if (!(maximumU - minimumU > POSITION_EPSILON)) {
                        continue;
                    }
                    float centerU = 0.5F * (minimumU + maximumU);
                    covering(this.negative, centerU, centerV, negativeCover, work);
                    covering(this.positive, centerU, centerV, positiveCover, work);
                    this.emitCell(
                            negativeCover,
                            positiveCover,
                            minimumU,
                            maximumU,
                            minimumV,
                            maximumV,
                            output);
                }
            }
        }

        private void emitCell(
                List<Candidate> negativeCover,
                List<Candidate> positiveCover,
                float minimumU,
                float maximumU,
                float minimumV,
                float maximumV,
                ArrayList<ResolvedQuad>[] output) {
            if (tryEmitAttachedOverlay(
                    negativeCover,
                    positiveCover,
                    minimumU,
                    maximumU,
                    minimumV,
                    maximumV,
                    output)) {
                return;
            }
            if (negativeCover.isEmpty() || positiveCover.isEmpty()) {
                emitActual(
                        negativeCover.isEmpty() ? positiveCover : negativeCover,
                        minimumU,
                        maximumU,
                        minimumV,
                        maximumV,
                        null,
                        output);
                return;
            }
            if (allTransmissive(negativeCover)
                    && hasOpaque(positiveCover)
                    && noneTransmissive(positiveCover)) {
                emitActual(
                        positiveCover,
                        minimumU,
                        maximumU,
                        minimumV,
                        maximumV,
                        null,
                        output);
                return;
            }
            if (allTransmissive(positiveCover)
                    && hasOpaque(negativeCover)
                    && noneTransmissive(negativeCover)) {
                emitActual(
                        negativeCover,
                        minimumU,
                        maximumU,
                        minimumV,
                        maximumV,
                        null,
                        output);
                return;
            }
            if (negativeCover.size() != 1 || positiveCover.size() != 1) {
                emitActual(
                        negativeCover,
                        minimumU,
                        maximumU,
                        minimumV,
                        maximumV,
                        null,
                        output);
                emitActual(
                        positiveCover,
                        minimumU,
                        maximumU,
                        minimumV,
                        maximumV,
                        null,
                        output);
                return;
            }
            Candidate negativeFace = negativeCover.getFirst();
            Candidate positiveFace = positiveCover.getFirst();
            if (negativeFace.quad.surface().fluid() != null
                    && positiveFace.quad.surface().fluid() != null
                    && sameMedium(negativeFace, positiveFace)) {
                return;
            }
            FaceKind negativeKind = negativeFace.kind();
            FaceKind positiveKind = positiveFace.kind();
            if (negativeKind == FaceKind.SOLID_TRANSMISSIVE
                    && positiveKind == FaceKind.SOLID_TRANSMISSIVE) {
                if (!sameMedium(negativeFace, positiveFace)) {
                    emitActual(
                            List.of(negativeFace),
                            minimumU,
                            maximumU,
                            minimumV,
                            maximumV,
                            positiveFace.mediumEndpoint(),
                            output);
                }
                return;
            }
            if (negativeKind.transmissive && positiveKind.transmissive) {
                Candidate solid = negativeKind == FaceKind.SOLID_TRANSMISSIVE
                        ? negativeFace
                        : (positiveKind == FaceKind.SOLID_TRANSMISSIVE
                                ? positiveFace
                                : negativeFace);
                emitActual(
                        List.of(solid),
                        minimumU,
                        maximumU,
                        minimumV,
                        maximumV,
                        null,
                        output);
                return;
            }
            if (negativeKind == FaceKind.OPAQUE && positiveKind.transmissive) {
                emitActual(
                        List.of(negativeFace),
                        minimumU,
                        maximumU,
                        minimumV,
                        maximumV,
                        null,
                        output);
                return;
            }
            if (positiveKind == FaceKind.OPAQUE && negativeKind.transmissive) {
                emitActual(
                        List.of(positiveFace),
                        minimumU,
                        maximumU,
                        minimumV,
                        maximumV,
                        null,
                        output);
                return;
            }
            if ((negativeKind == FaceKind.OPAQUE)
                    != (positiveKind == FaceKind.OPAQUE)) {
                Candidate opaque = negativeKind == FaceKind.OPAQUE
                        ? negativeFace
                        : positiveFace;
                emitActual(
                        List.of(opaque),
                        minimumU,
                        maximumU,
                        minimumV,
                        maximumV,
                        null,
                        output);
                return;
            }
            if (negativeKind.transmissive || positiveKind.transmissive) {
                Candidate transmissive = negativeKind.transmissive
                        ? negativeFace
                        : positiveFace;
                Candidate cutout = transmissive == negativeFace
                        ? positiveFace
                        : negativeFace;
                output[transmissive.sectionIndex].add(transmissiveCutoutSlice(
                        transmissive,
                        cutout,
                        minimumU,
                        maximumU,
                        minimumV,
                        maximumV));
                return;
            }
            Candidate geometry = ownedGeometry(negativeFace, positiveFace);
            if (geometry == null) {
                return;
            }
            Candidate secondary = geometry == negativeFace ? positiveFace : negativeFace;
            output[geometry.sectionIndex].add(bilateralSlice(
                    geometry,
                    secondary,
                    minimumU,
                    maximumU,
                    minimumV,
                    maximumV));
        }

        private static Candidate ownedGeometry(Candidate first, Candidate second) {
            if (first.quad.peerOnly() && second.quad.peerOnly()) {
                return null;
            }
            if (first.quad.peerOnly() != second.quad.peerOnly()) {
                return first.quad.peerOnly() ? second : first;
            }
            return first;
        }

        private static ResolvedQuad bilateralSlice(
                Candidate geometry,
                Candidate secondary,
                float minimumU,
                float maximumU,
                float minimumV,
                float maximumV) {
            return new ResolvedQuad(
                    geometry,
                    null,
                    SurfaceDefinition.bilateral(
                            geometry.bindingForSlice(
                                    geometry.quad.surface(),
                                    minimumU,
                                    maximumU,
                                    minimumV,
                                    maximumV),
                            secondary.bindingForGeometrySlice(
                                    geometry,
                                    secondary.quad.surface(),
                                    minimumU,
                                    maximumU,
                                    minimumV,
                                    maximumV)),
                    minimumU,
                    maximumU,
                    minimumV,
                    maximumV);
        }

        private static ResolvedQuad transmissiveCutoutSlice(
                Candidate geometry,
                Candidate cutout,
                float minimumU,
                float maximumU,
                float minimumV,
                float maximumV) {
            return new ResolvedQuad(
                    geometry,
                    null,
                    SurfaceDefinition.overlay(
                            cutout.bindingForGeometrySlice(
                                    geometry,
                                    cutout.quad.surface(),
                                    minimumU,
                                    maximumU,
                                    minimumV,
                                    maximumV),
                            geometry.bindingForSlice(
                                    geometry.quad.surface(),
                                    minimumU,
                                    maximumU,
                                    minimumV,
                                    maximumV),
                            false),
                    minimumU,
                    maximumU,
                    minimumV,
                    maximumV);
        }

        private static boolean allTransmissive(List<Candidate> candidates) {
            for (Candidate candidate : candidates) {
                if (!candidate.kind().transmissive) {
                    return false;
                }
            }
            return true;
        }

        private static boolean noneTransmissive(List<Candidate> candidates) {
            for (Candidate candidate : candidates) {
                if (candidate.kind().transmissive) {
                    return false;
                }
            }
            return true;
        }

        private static boolean hasOpaque(List<Candidate> candidates) {
            for (Candidate candidate : candidates) {
                if (candidate.kind() == FaceKind.OPAQUE) {
                    return true;
                }
            }
            return false;
        }

        private void emitWholeActual(
                List<Candidate> candidates,
                ArrayList<ResolvedQuad>[] output) {
            if (candidates.size() == 2) {
                Candidate first = candidates.get(0);
                Candidate second = candidates.get(1);
                Candidate overlay = explicitOverlay(first)
                        ? first
                        : (explicitOverlay(second) ? second : null);
                Candidate substrate = overlay == first ? second : first;
                if (overlay != null
                        && substrate.kind() == FaceKind.OPAQUE
                        && sameRectangle(first, second)
                        && !substrate.quad.peerOnly()) {
                    output[substrate.sectionIndex].add(overlaySlice(
                            substrate,
                            overlay,
                            substrate.minimumU,
                            substrate.maximumU,
                            substrate.minimumV,
                            substrate.maximumV,
                            false));
                    return;
                }
            }
            for (Candidate candidate : candidates) {
                if (!candidate.quad.peerOnly()) {
                    output[candidate.sectionIndex].add(candidate.slice(
                            candidate.minimumU,
                            candidate.maximumU,
                            candidate.minimumV,
                            candidate.maximumV,
                            null));
                }
            }
        }

        private static void emitActual(
                List<Candidate> candidates,
                float minimumU,
                float maximumU,
                float minimumV,
                float maximumV,
                SurfaceDefinition.MediumEndpoint adjacentMedium,
                ArrayList<ResolvedQuad>[] output) {
            for (Candidate candidate : candidates) {
                if (!candidate.quad.peerOnly()) {
                    output[candidate.sectionIndex].add(candidate.slice(
                            minimumU,
                            maximumU,
                            minimumV,
                            maximumV,
                            adjacentMedium));
                }
            }
        }

        private static boolean tryEmitAttachedOverlay(
                List<Candidate> negative,
                List<Candidate> positive,
                float minimumU,
                float maximumU,
                float minimumV,
                float maximumV,
                ArrayList<ResolvedQuad>[] output) {
            if (negative.size() != 1 || positive.size() != 1) {
                return false;
            }
            Candidate first = negative.getFirst();
            Candidate second = positive.getFirst();
            Candidate overlay = attachedOverlay(first)
                    ? first
                    : (attachedOverlay(second) ? second : null);
            if (overlay == null) {
                return false;
            }
            Candidate substrate = overlay == first ? second : first;
            if (substrate.kind() != FaceKind.OPAQUE
                    || substrate.quad.peerOnly()
                    || Math.abs(overlay.plane - substrate.plane)
                            > ATTACHED_SURFACE_EPSILON) {
                return false;
            }
            output[substrate.sectionIndex].add(overlaySlice(
                    substrate,
                    overlay,
                    minimumU,
                    maximumU,
                    minimumV,
                    maximumV,
                    true));
            return true;
        }

        private static ResolvedQuad overlaySlice(
                Candidate geometry,
                Candidate overlay,
                float minimumU,
                float maximumU,
                float minimumV,
                float maximumV,
                boolean positiveOnly) {
            SurfaceDefinition.MaterialBinding overlayMaterial =
                    overlay.bindingForSlice(
                            overlay.quad.surface(),
                            minimumU,
                            maximumU,
                            minimumV,
                            maximumV);
            SurfaceDefinition.MaterialBinding substrateMaterial =
                    geometry.bindingForSlice(
                            geometry.quad.surface(),
                            minimumU,
                            maximumU,
                            minimumV,
                            maximumV);
            return new ResolvedQuad(
                    geometry,
                    null,
                    SurfaceDefinition.overlay(
                            overlayMaterial, substrateMaterial, positiveOnly),
                    minimumU,
                    maximumU,
                    minimumV,
                    maximumV);
        }

        private static boolean explicitOverlay(Candidate candidate) {
            return candidate.quad.surface().rasterOverlay();
        }

        private static boolean attachedOverlay(Candidate candidate) {
            CapturedSectionGeometry.Surface surface = candidate.quad.surface();
            return surface.rasterOverlay()
                    || ClusterSceneTranslator.isCutout(surface)
                            && surface.animated()
                            && surface.lightEmission() > 0;
        }

        private static boolean sameRectangle(Candidate first, Candidate second) {
            return near(first.minimumU, second.minimumU)
                    && near(first.maximumU, second.maximumU)
                    && near(first.minimumV, second.minimumV)
                    && near(first.maximumV, second.maximumV);
        }

        private static void covering(
                List<Candidate> candidates,
                float u,
                float v,
                ArrayList<Candidate> result,
                ClusterTranslationWork work) {
            result.clear();
            for (Candidate candidate : candidates) {
                work.step();
                if (u > candidate.minimumU - POSITION_EPSILON
                        && u < candidate.maximumU + POSITION_EPSILON
                        && v > candidate.minimumV - POSITION_EPSILON
                        && v < candidate.maximumV + POSITION_EPSILON) {
                    result.add(candidate);
                }
            }
        }

        private static float[] edges(
                List<Candidate> first,
                List<Candidate> second,
                boolean u) {
            float[] values = new float[2 * (first.size() + second.size())];
            int size = 0;
            for (Candidate candidate : first) {
                values[size++] = u ? candidate.minimumU : candidate.minimumV;
                values[size++] = u ? candidate.maximumU : candidate.maximumV;
            }
            for (Candidate candidate : second) {
                values[size++] = u ? candidate.minimumU : candidate.minimumV;
                values[size++] = u ? candidate.maximumU : candidate.maximumV;
            }
            Arrays.sort(values, 0, size);
            int unique = 0;
            for (int index = 0; index < size; index++) {
                if (unique == 0
                        || Math.abs(values[index] - values[unique - 1])
                                > POSITION_EPSILON) {
                    values[unique++] = values[index];
                }
            }
            return Arrays.copyOf(values, unique);
        }

        private static boolean sameMedium(Candidate first, Candidate second) {
            CapturedSectionGeometry.Surface a = first.quad.surface();
            CapturedSectionGeometry.Surface b = second.quad.surface();
            int familyA = a.block().mediumFamily();
            int familyB = b.block().mediumFamily();
            if (a.fluid() != null && b.fluid() != null) {
                return a.water() == b.water();
            }
            boolean sameIdentity = familyA != 0 && familyA == familyB
                    || a.sprite().id().equals(b.sprite().id());
            return a.water() == b.water()
                    && ClusterSceneTranslator.averageColor(a)
                            == ClusterSceneTranslator.averageColor(b)
                    && sameIdentity;
        }
    }

    private enum FaceKind {
        OPAQUE(false),
        SOLID_TRANSMISSIVE(true),
        THIN_TRANSMISSIVE(true),
        OTHER(false);

        private final boolean transmissive;

        FaceKind(boolean transmissive) {
            this.transmissive = transmissive;
        }

        static FaceKind of(
                CapturedSectionGeometry.Surface surface,
                TransmissiveTopology topology) {
            if (ClusterSceneTranslator.isTransmissive(surface)) {
                return topology == TransmissiveTopology.THIN_SHEET
                        ? THIN_TRANSMISSIVE
                        : SOLID_TRANSMISSIVE;
            }
            return ClusterSceneTranslator.isCutout(surface) ? OTHER : OPAQUE;
        }
    }

    private static final class Candidate {
        private final int sectionIndex;
        private final int originX;
        private final int originY;
        private final int originZ;
        private final CapturedSectionGeometry.Quad quad;
        private final TransmissiveTopology transmissiveTopology;
        private final BoundaryKey key;
        private final int planeAxis;
        private final int axisU;
        private final int axisV;
        private final int normalSign;
        private final float plane;
        private final float minimumU;
        private final float maximumU;
        private final float minimumV;
        private final float maximumV;
        private final float[] cornerU;
        private final float[] cornerV;

        private Candidate(
                int sectionIndex,
                int originX,
                int originY,
                int originZ,
                CapturedSectionGeometry.Quad quad,
                TransmissiveTopology transmissiveTopology,
                BoundaryKey key,
                int planeAxis,
                int axisU,
                int axisV,
                int normalSign,
                float plane,
                float minimumU,
                float maximumU,
                float minimumV,
                float maximumV,
                float[] cornerU,
                float[] cornerV) {
            this.sectionIndex = sectionIndex;
            this.originX = originX;
            this.originY = originY;
            this.originZ = originZ;
            this.quad = quad;
            this.transmissiveTopology = transmissiveTopology;
            this.key = key;
            this.planeAxis = planeAxis;
            this.axisU = axisU;
            this.axisV = axisV;
            this.normalSign = normalSign;
            this.plane = plane;
            this.minimumU = minimumU;
            this.maximumU = maximumU;
            this.minimumV = minimumV;
            this.maximumV = maximumV;
            this.cornerU = cornerU;
            this.cornerV = cornerV;
        }

        static Candidate tryCreate(
                int sectionIndex,
                int originX,
                int originY,
                int originZ,
                int clusterWorldX,
                int clusterWorldY,
                int clusterWorldZ,
                TwoSidedQuadReducer.ResolvedQuad resolved) {
            CapturedSectionGeometry.Quad quad = resolved.quad();
            CapturedSectionGeometry.Surface surface = quad.surface();
            CapturedSectionGeometry.BlockFacts owner = surface.block();
            if (owner == null
                    || resolved.definition().interfaceMode()
                            != SurfaceDefinition.InterfaceMode.SINGLE
                    || !surface.mergeable() && surface.fluid() == null) {
                return null;
            }
            float[] normal = {quad.normalX(), quad.normalY(), quad.normalZ()};
            int planeAxis = -1;
            for (int axis = 0; axis < 3; axis++) {
                if (Math.abs(Math.abs(normal[axis]) - 1.0F) <= NORMAL_EPSILON) {
                    if (planeAxis != -1) {
                        return null;
                    }
                    planeAxis = axis;
                } else if (Math.abs(normal[axis]) > NORMAL_EPSILON) {
                    return null;
                }
            }
            if (planeAxis < 0) {
                return null;
            }
            int axisU = projectedAxisU(planeAxis);
            int axisV = projectedAxisV(planeAxis);
            int[] origin = {originX, originY, originZ};
            float plane = coordinate(quad, planeAxis, 0) + origin[planeAxis];
            float minimumU = Float.POSITIVE_INFINITY;
            float maximumU = Float.NEGATIVE_INFINITY;
            float minimumV = Float.POSITIVE_INFINITY;
            float maximumV = Float.NEGATIVE_INFINITY;
            for (int vertex = 0; vertex < 4; vertex++) {
                float vertexPlane = coordinate(quad, planeAxis, vertex) + origin[planeAxis];
                if (Math.abs(vertexPlane - plane) > POSITION_EPSILON) {
                    return null;
                }
                float u = coordinate(quad, axisU, vertex) + origin[axisU];
                float v = coordinate(quad, axisV, vertex) + origin[axisV];
                minimumU = Math.min(minimumU, u);
                maximumU = Math.max(maximumU, u);
                minimumV = Math.min(minimumV, v);
                maximumV = Math.max(maximumV, v);
            }
            if (!(maximumU - minimumU > POSITION_EPSILON)
                    || !(maximumV - minimumV > POSITION_EPSILON)) {
                return null;
            }
            int normalSign = normal[planeAxis] < 0.0F ? -1 : 1;
            int[] ownerCoordinates = {owner.x(), owner.y(), owner.z()};
            int[] clusterWorld = {clusterWorldX, clusterWorldY, clusterWorldZ};
            float expectedPlane = ownerCoordinates[planeAxis]
                    + (normalSign > 0 ? 1.0F : 0.0F)
                    - clusterWorld[planeAxis];
            float clusterPlane = plane;
            boolean attachedOverlay = ClusterSceneTranslator.isCutout(surface)
                    && surface.animated()
                    && surface.lightEmission() > 0;
            boolean fluidSideInset = planeAxis != 1
                    && surface.fluid() != null
                    && knownFluidSideInset(clusterPlane, expectedPlane, normalSign);
            float planeTolerance = attachedOverlay
                    ? ATTACHED_SURFACE_EPSILON
                    : POSITION_EPSILON;
            if (!fluidSideInset
                    && Math.abs(clusterPlane - expectedPlane) > planeTolerance) {
                return null;
            }
            float resolvedPlane = fluidSideInset ? expectedPlane : plane;
            int negativeX = owner.x() - (normalSign < 0 && planeAxis == 0 ? 1 : 0);
            int negativeY = owner.y() - (normalSign < 0 && planeAxis == 1 ? 1 : 0);
            int negativeZ = owner.z() - (normalSign < 0 && planeAxis == 2 ? 1 : 0);
            BoundaryKey key = new BoundaryKey(
                    planeAxis, negativeX, negativeY, negativeZ);
            int[] corners = {-1, -1, -1, -1};
            for (int vertex = 0; vertex < 4; vertex++) {
                float u = coordinate(quad, axisU, vertex) + origin[axisU];
                float v = coordinate(quad, axisV, vertex) + origin[axisV];
                int highU = near(u, minimumU) ? 0 : (near(u, maximumU) ? 1 : -1);
                int highV = near(v, minimumV) ? 0 : (near(v, maximumV) ? 1 : -1);
                if (highU < 0 || highV < 0) {
                    return null;
                }
                int corner = highU | highV << 1;
                if (corners[corner] != -1) {
                    return null;
                }
                corners[corner] = vertex;
            }
            float[] cornerU = new float[4];
            float[] cornerV = new float[4];
            for (int corner = 0; corner < 4; corner++) {
                if (corners[corner] < 0) {
                    return null;
                }
                cornerU[corner] = quad.u(corners[corner]);
                cornerV[corner] = quad.v(corners[corner]);
            }
            return new Candidate(
                    sectionIndex,
                    originX,
                    originY,
                    originZ,
                    quad,
                    resolved.definition().primary().transmissiveTopology(),
                    key,
                    planeAxis,
                    axisU,
                    axisV,
                    normalSign,
                    resolvedPlane,
                    minimumU,
                    maximumU,
                    minimumV,
                    maximumV,
                    cornerU,
                    cornerV);
        }

        ResolvedQuad slice(
                float minimumU,
                float maximumU,
                float minimumV,
                float maximumV,
                SurfaceDefinition.MediumEndpoint adjacentMedium) {
            SurfaceDefinition.MaterialBinding primary = this.bindingForSlice(
                    this.quad.surface(), minimumU, maximumU, minimumV, maximumV);
            SurfaceDefinition definition = adjacentMedium == null
                    ? SurfaceDefinition.single(primary)
                    : SurfaceDefinition.boundary(
                            primary, adjacentMedium, this.mediumEndpoint());
            return new ResolvedQuad(
                    this,
                    null,
                    definition,
                    minimumU,
                    maximumU,
                    minimumV,
                    maximumV);
        }

        SurfaceDefinition.MediumEndpoint mediumEndpoint() {
            float referenceU = 0.5F * (minimum(this.cornerU) + maximum(this.cornerU));
            float referenceV = 0.5F * (minimum(this.cornerV) + maximum(this.cornerV));
            return new SurfaceDefinition.MediumEndpoint(
                    this.quad.surface(),
                    referenceU,
                    referenceV,
                    this.transmissiveTopology);
        }

        FaceKind kind() {
            return FaceKind.of(this.quad.surface(), this.transmissiveTopology);
        }

        private SurfaceDefinition.MaterialBinding bindingForSlice(
                CapturedSectionGeometry.Surface surface,
                float sliceMinimumU,
                float sliceMaximumU,
                float sliceMinimumV,
                float sliceMaximumV) {
            float[] u = new float[4];
            float[] v = new float[4];
            int[] origin = {this.originX, this.originY, this.originZ};
            for (int vertex = 0; vertex < 4; vertex++) {
                float sourceU = coordinate(this.quad, this.axisU, vertex)
                        + origin[this.axisU];
                float sourceV = coordinate(this.quad, this.axisV, vertex)
                        + origin[this.axisV];
                float worldU = near(sourceU, this.minimumU)
                        ? sliceMinimumU
                        : sliceMaximumU;
                float worldV = near(sourceV, this.minimumV)
                        ? sliceMinimumV
                        : sliceMaximumV;
                float s = (worldU - this.minimumU)
                        / (this.maximumU - this.minimumU);
                float t = (worldV - this.minimumV)
                        / (this.maximumV - this.minimumV);
                u[vertex] = bilinear(this.cornerU, s, t);
                v[vertex] = bilinear(this.cornerV, s, t);
            }
            return new SurfaceDefinition.MaterialBinding(
                    surface,
                    new SurfaceDefinition.UvMapping(
                            u[0], v[0], u[1], v[1],
                            u[2], v[2], u[3], v[3]),
                    this.transmissiveTopology);
        }

        private SurfaceDefinition.MaterialBinding bindingForGeometrySlice(
                Candidate geometry,
                CapturedSectionGeometry.Surface surface,
                float sliceMinimumU,
                float sliceMaximumU,
                float sliceMinimumV,
                float sliceMaximumV) {
            float[] u = new float[4];
            float[] v = new float[4];
            int[] geometryOrigin = {geometry.originX, geometry.originY, geometry.originZ};
            for (int vertex = 0; vertex < 4; vertex++) {
                float sourceU = coordinate(geometry.quad, geometry.axisU, vertex)
                        + geometryOrigin[geometry.axisU];
                float sourceV = coordinate(geometry.quad, geometry.axisV, vertex)
                        + geometryOrigin[geometry.axisV];
                float worldU = near(sourceU, geometry.minimumU)
                        ? sliceMinimumU
                        : sliceMaximumU;
                float worldV = near(sourceV, geometry.minimumV)
                        ? sliceMinimumV
                        : sliceMaximumV;
                float s = (worldU - this.minimumU) / (this.maximumU - this.minimumU);
                float t = (worldV - this.minimumV) / (this.maximumV - this.minimumV);
                u[vertex] = bilinear(this.cornerU, s, t);
                v[vertex] = bilinear(this.cornerV, s, t);
            }
            return new SurfaceDefinition.MaterialBinding(
                    surface,
                    new SurfaceDefinition.UvMapping(
                            u[0], v[0], u[1], v[1],
                            u[2], v[2], u[3], v[3]),
                    this.transmissiveTopology);
        }

        void writeGeometry(
                SectionMeshAccumulator.Quad target,
                float sliceMinimumU,
                float sliceMaximumU,
                float sliceMinimumV,
                float sliceMaximumV) {
            int[] origin = {this.originX, this.originY, this.originZ};
            for (int vertex = 0; vertex < 4; vertex++) {
                float sourceU = coordinate(this.quad, this.axisU, vertex)
                        + origin[this.axisU];
                float sourceV = coordinate(this.quad, this.axisV, vertex)
                        + origin[this.axisV];
                float worldU = near(sourceU, this.minimumU)
                        ? sliceMinimumU
                        : sliceMaximumU;
                float worldV = near(sourceV, this.minimumV)
                        ? sliceMinimumV
                        : sliceMaximumV;
                setCoordinate(
                        target, this.planeAxis, vertex,
                        this.plane - origin[this.planeAxis]);
                setCoordinate(target, this.axisU, vertex, worldU - origin[this.axisU]);
                setCoordinate(target, this.axisV, vertex, worldV - origin[this.axisV]);
            }
            target.normalX = this.quad.normalX();
            target.normalY = this.quad.normalY();
            target.normalZ = this.quad.normalZ();
        }

        private static float bilinear(float[] corners, float s, float t) {
            return (1.0F - t) * ((1.0F - s) * corners[0] + s * corners[1])
                    + t * ((1.0F - s) * corners[2] + s * corners[3]);
        }

        private static float minimum(float[] values) {
            return Math.min(Math.min(values[0], values[1]), Math.min(values[2], values[3]));
        }

        private static float maximum(float[] values) {
            return Math.max(Math.max(values[0], values[1]), Math.max(values[2], values[3]));
        }
    }

    private static boolean knownFluidSideInset(
            float actualPlane, float expectedPlane, int normalSign) {
        float inset = normalSign * (expectedPlane - actualPlane);
        float tolerance = Math.max(
                Math.max(Math.ulp(actualPlane), Math.ulp(expectedPlane)),
                Math.ulp(FLUID_SIDE_INSET));
        return Math.abs(inset - FLUID_SIDE_INSET) <= tolerance;
    }

    private record BoundaryKey(int axis, int negativeX, int negativeY, int negativeZ) {
    }

    private static int projectedAxisU(int planeAxis) {
        return planeAxis == 0 ? 1 : 0;
    }

    private static int projectedAxisV(int planeAxis) {
        return planeAxis == 2 ? 1 : 2;
    }

    private static float coordinate(
            CapturedSectionGeometry.Quad quad, int axis, int vertex) {
        return switch (axis) {
            case 0 -> quad.x(vertex);
            case 1 -> quad.y(vertex);
            default -> quad.z(vertex);
        };
    }

    private static void setCoordinate(
            SectionMeshAccumulator.Quad quad, int axis, int vertex, float value) {
        switch (axis) {
            case 0 -> quad.x[vertex] = value;
            case 1 -> quad.y[vertex] = value;
            default -> quad.z[vertex] = value;
        }
    }

    private static boolean near(float first, float second) {
        return Math.abs(first - second) <= POSITION_EPSILON;
    }
}
