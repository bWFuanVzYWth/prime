package dev.prime.render.terrain;

import dev.prime.render.material.BuiltinMaterialClass;
import dev.prime.render.scene.CapturedSectionGeometry;
import dev.prime.render.scene.CapturedSprite;
import dev.prime.render.scene.SpriteId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/** Independent axis-aligned cell model for cluster-boundary semantic tests. */
final class ClusterTranslationSemanticOracle {
    private static final CapturedSprite[] SPRITES = {
        sprite("semantic_a", 1),
        sprite("semantic_b", 2),
        sprite("semantic_c", 3),
        sprite("semantic_d", 4)
    };

    private ClusterTranslationSemanticOracle() {
    }

    enum Kind {
        OPAQUE,
        SOLID_TRANSMISSIVE
    }

    record Face(
            int owner,
            int minimumU,
            int maximumU,
            int minimumV,
            int maximumV,
            int normalSign,
            Kind kind,
            int mediumFamily,
            int sprite,
            int color,
            boolean peerOnly) {
        Face {
            if (owner < 0
                    || minimumU < 0
                    || minimumU >= maximumU
                    || minimumV < 0
                    || minimumV >= maximumV
                    || Math.abs(normalSign) != 1
                    || mediumFamily < 0
                    || sprite < 0
                    || sprite >= SPRITES.length) {
                throw new IllegalArgumentException("Invalid semantic face");
            }
        }
    }

    record Scenario(int gridSize, List<Face> faces) {
        Scenario {
            if (gridSize <= 0) {
                throw new IllegalArgumentException("Semantic grid must be positive");
            }
            faces = List.copyOf(faces);
            for (Face face : faces) {
                if (face.maximumU() > gridSize || face.maximumV() > gridSize) {
                    throw new IllegalArgumentException("Semantic face exceeds its grid");
                }
            }
        }
    }

    record Built(
            CapturedCluster cluster,
            IdentityHashMap<CapturedSectionGeometry.Surface, Face> owners) {
    }

    static Built build(Scenario scenario, List<Integer> order) {
        if (order.size() != scenario.faces().size()) {
            throw new IllegalArgumentException("Shuffle does not cover every semantic face");
        }
        CapturedSectionGeometry.Surface[] surfaces =
                new CapturedSectionGeometry.Surface[scenario.faces().size()];
        IdentityHashMap<CapturedSectionGeometry.Surface, Face> owners =
                new IdentityHashMap<>();
        for (int index = 0; index < scenario.faces().size(); index++) {
            Face face = scenario.faces().get(index);
            surfaces[index] = surface(face);
            owners.put(surfaces[index], face);
        }
        CapturedSectionGeometry.Builder section = new CapturedSectionGeometry.Builder();
        boolean[] seen = new boolean[scenario.faces().size()];
        for (int index : order) {
            if (index < 0 || index >= seen.length || seen[index]) {
                throw new IllegalArgumentException("Shuffle is not a permutation");
            }
            seen[index] = true;
            Face face = scenario.faces().get(index);
            CapturedSectionGeometry.MutableQuad quad = quad(scenario.gridSize(), face);
            if (face.peerOnly()) {
                section.addPeer(quad, surfaces[index]);
            } else {
                section.add(quad, surfaces[index]);
            }
        }
        CapturedCluster.Builder cluster = new CapturedCluster.Builder(0, 0, 0);
        if (!order.isEmpty()) {
            cluster.add(0, 0, 0, section.build());
        }
        return new Built(cluster.build(), owners);
    }

    static Canonical expected(Scenario scenario) {
        Edges edges = Edges.of(scenario);
        HashMap<Cell, ArrayList<SurfaceKey>> cells = emptyCells(edges);
        for (int v = 0; v + 1 < edges.v().size(); v++) {
            for (int u = 0; u + 1 < edges.u().size(); u++) {
                int centerU2 = edges.u().get(u) + edges.u().get(u + 1);
                int centerV2 = edges.v().get(v) + edges.v().get(v + 1);
                ArrayList<Face> normalPositive = new ArrayList<>();
                ArrayList<Face> normalNegative = new ArrayList<>();
                for (Face face : scenario.faces()) {
                    if (2 * face.minimumU() < centerU2
                            && centerU2 < 2 * face.maximumU()
                            && 2 * face.minimumV() < centerV2
                            && centerV2 < 2 * face.maximumV()) {
                        (face.normalSign() > 0 ? normalPositive : normalNegative).add(face);
                    }
                }
                Cell cell = new Cell(u, v);
                cells.get(cell).addAll(resolveCell(
                        scenario.gridSize(),
                        edges,
                        cell,
                        normalPositive,
                        normalNegative));
            }
        }
        return canonical(edges, cells);
    }

    static Canonical actual(Scenario scenario, Built built) {
        Edges edges = Edges.of(scenario);
        HashMap<Cell, ArrayList<SurfaceKey>> cells = emptyCells(edges);
        TransparentBoundaryResolver.Result result =
                TransparentBoundaryResolver.resolve(built.cluster(), true);
        SectionMeshAccumulator.Quad geometry = new SectionMeshAccumulator.Quad();
        for (int section = 0; section < SectionCluster.SECTION_COUNT; section++) {
            for (TransparentBoundaryResolver.ResolvedQuad resolved : result.section(section)) {
                resolved.write(geometry);
                float minimumU = minimum(geometry.y);
                float maximumU = maximum(geometry.y);
                float minimumV = minimum(geometry.z);
                float maximumV = maximum(geometry.z);
                for (int v = 0; v + 1 < edges.v().size(); v++) {
                    float centerV = (edges.v().get(v) + edges.v().get(v + 1))
                            / (2.0F * scenario.gridSize());
                    if (!(centerV > minimumV && centerV < maximumV)) {
                        continue;
                    }
                    for (int u = 0; u + 1 < edges.u().size(); u++) {
                        float centerU = (edges.u().get(u) + edges.u().get(u + 1))
                                / (2.0F * scenario.gridSize());
                        if (centerU > minimumU && centerU < maximumU) {
                            Cell cell = new Cell(u, v);
                            cells.get(cell).add(actualKey(
                                    scenario.gridSize(),
                                    edges,
                                    cell,
                                    geometry,
                                    resolved.definition(),
                                    built.owners()));
                        }
                    }
                }
            }
        }
        return canonical(edges, cells);
    }

    private static List<SurfaceKey> resolveCell(
            int gridSize,
            Edges edges,
            Cell cell,
            List<Face> normalPositive,
            List<Face> normalNegative) {
        ArrayList<SurfaceKey> result = new ArrayList<>();
        if (normalPositive.isEmpty() || normalNegative.isEmpty()) {
            emitSingles(gridSize, edges, cell,
                    normalPositive.isEmpty() ? normalNegative : normalPositive, result);
            return result;
        }
        if (allTransmissive(normalPositive)
                && hasOpaque(normalNegative)
                && noneTransmissive(normalNegative)) {
            emitSingles(gridSize, edges, cell, normalNegative, result);
            return result;
        }
        if (allTransmissive(normalNegative)
                && hasOpaque(normalPositive)
                && noneTransmissive(normalPositive)) {
            emitSingles(gridSize, edges, cell, normalPositive, result);
            return result;
        }
        if (normalPositive.size() != 1 || normalNegative.size() != 1) {
            emitSingles(gridSize, edges, cell, normalPositive, result);
            emitSingles(gridSize, edges, cell, normalNegative, result);
            return result;
        }
        Face negativeMediumSide = normalPositive.getFirst();
        Face positiveMediumSide = normalNegative.getFirst();
        if (negativeMediumSide.kind() == Kind.SOLID_TRANSMISSIVE
                && positiveMediumSide.kind() == Kind.SOLID_TRANSMISSIVE) {
            if (!sameMedium(negativeMediumSide, positiveMediumSide)) {
                result.add(boundary(
                        gridSize,
                        edges,
                        cell,
                        negativeMediumSide,
                        positiveMediumSide));
            }
            return result;
        }
        if (negativeMediumSide.kind() == Kind.SOLID_TRANSMISSIVE
                || positiveMediumSide.kind() == Kind.SOLID_TRANSMISSIVE) {
            Face opaque = negativeMediumSide.kind() == Kind.OPAQUE
                    ? negativeMediumSide
                    : positiveMediumSide;
            if (!opaque.peerOnly()) {
                result.add(single(gridSize, edges, cell, opaque));
            }
            return result;
        }
        Face geometry = negativeMediumSide.peerOnly()
                ? positiveMediumSide
                : negativeMediumSide;
        Face secondary = geometry == negativeMediumSide
                ? positiveMediumSide
                : negativeMediumSide;
        if (!geometry.peerOnly()) {
            result.add(bilateral(gridSize, edges, cell, geometry, secondary));
        }
        return result;
    }

    private static boolean allTransmissive(List<Face> faces) {
        for (Face face : faces) {
            if (face.kind() != Kind.SOLID_TRANSMISSIVE) {
                return false;
            }
        }
        return true;
    }

    private static boolean noneTransmissive(List<Face> faces) {
        for (Face face : faces) {
            if (face.kind() == Kind.SOLID_TRANSMISSIVE) {
                return false;
            }
        }
        return true;
    }

    private static boolean hasOpaque(List<Face> faces) {
        for (Face face : faces) {
            if (face.kind() == Kind.OPAQUE) {
                return true;
            }
        }
        return false;
    }

    private static void emitSingles(
            int gridSize,
            Edges edges,
            Cell cell,
            List<Face> faces,
            List<SurfaceKey> output) {
        for (Face face : faces) {
            if (!face.peerOnly()) {
                output.add(single(gridSize, edges, cell, face));
            }
        }
    }

    private static SurfaceKey single(
            int gridSize, Edges edges, Cell cell, Face face) {
        return new SurfaceKey(
                SurfaceDefinition.InterfaceMode.SINGLE,
                binding(gridSize, edges, cell, face),
                null,
                null,
                null,
                false);
    }

    private static SurfaceKey boundary(
            int gridSize,
            Edges edges,
            Cell cell,
            Face negativeMediumSide,
            Face positiveMediumSide) {
        if (negativeMediumSide.peerOnly()) {
            return null;
        }
        return new SurfaceKey(
                SurfaceDefinition.InterfaceMode.BOUNDARY,
                binding(gridSize, edges, cell, negativeMediumSide),
                null,
                medium(gridSize, positiveMediumSide),
                medium(gridSize, negativeMediumSide),
                false);
    }

    private static SurfaceKey bilateral(
            int gridSize, Edges edges, Cell cell, Face primary, Face secondary) {
        return new SurfaceKey(
                SurfaceDefinition.InterfaceMode.BILATERAL,
                binding(gridSize, edges, cell, primary),
                binding(gridSize, edges, cell, secondary),
                null,
                null,
                false);
    }

    private static BindingKey binding(
            int gridSize, Edges edges, Cell cell, Face face) {
        return new BindingKey(
                face.owner(),
                SPRITES[face.sprite()].id(),
                face.color(),
                BuiltinMaterialClass.DEFAULT,
                topology(face),
                cellUv(gridSize, edges, cell));
    }

    private static MediumKey medium(int gridSize, Face face) {
        return new MediumKey(
                face.owner(),
                SPRITES[face.sprite()].id(),
                face.color(),
                topology(face),
                quantize((face.minimumU() + face.maximumU()) / (2.0F * gridSize)),
                quantize((face.minimumV() + face.maximumV()) / (2.0F * gridSize)));
    }

    private static SurfaceKey actualKey(
            int gridSize,
            Edges edges,
            Cell cell,
            SectionMeshAccumulator.Quad geometry,
            SurfaceDefinition definition,
            IdentityHashMap<CapturedSectionGeometry.Surface, Face> owners) {
        BindingKey primary = actualBinding(
                gridSize, edges, cell, geometry, definition.primary(), owners);
        BindingKey secondary = null;
        MediumKey positive = null;
        MediumKey negative = null;
        boolean positiveOnly = false;
        if (definition instanceof SurfaceDefinition.Overlay overlay) {
            secondary = actualBinding(
                    gridSize, edges, cell, geometry, overlay.secondary(), owners);
            positiveOnly = overlay.positiveOnly();
        } else if (definition instanceof SurfaceDefinition.Bilateral bilateral) {
            secondary = actualBinding(
                    gridSize, edges, cell, geometry, bilateral.secondary(), owners);
        } else if (definition instanceof SurfaceDefinition.Boundary boundary) {
            positive = actualMedium(boundary.positiveMedium(), owners);
            negative = actualMedium(boundary.negativeMedium(), owners);
        }
        return new SurfaceKey(
                definition.interfaceMode(),
                primary,
                secondary,
                positive,
                negative,
                positiveOnly);
    }

    private static BindingKey actualBinding(
            int gridSize,
            Edges edges,
            Cell cell,
            SectionMeshAccumulator.Quad geometry,
            SurfaceDefinition.MaterialBinding binding,
            IdentityHashMap<CapturedSectionGeometry.Surface, Face> owners) {
        Face face = requireOwner(owners, binding.surface());
        return new BindingKey(
                face.owner(),
                binding.surface().sprite().id(),
                ClusterSceneTranslator.averageColor(binding.surface()),
                binding.surface().builtinMaterialClass(),
                binding.transmissiveTopology(),
                interpolatedUv(gridSize, edges, cell, geometry, binding.uv()));
    }

    private static MediumKey actualMedium(
            SurfaceDefinition.MediumEndpoint endpoint,
            IdentityHashMap<CapturedSectionGeometry.Surface, Face> owners) {
        Face face = requireOwner(owners, endpoint.surface());
        return new MediumKey(
                face.owner(),
                endpoint.surface().sprite().id(),
                ClusterSceneTranslator.averageColor(endpoint.surface()),
                endpoint.transmissiveTopology(),
                quantize(endpoint.referenceU()),
                quantize(endpoint.referenceV()));
    }

    private static Face requireOwner(
            IdentityHashMap<CapturedSectionGeometry.Surface, Face> owners,
            CapturedSectionGeometry.Surface surface) {
        Face face = owners.get(surface);
        if (face == null) {
            throw new AssertionError("Resolver produced an unknown surface owner");
        }
        return face;
    }

    private static List<Integer> interpolatedUv(
            int gridSize,
            Edges edges,
            Cell cell,
            SectionMeshAccumulator.Quad geometry,
            SurfaceDefinition.UvMapping uv) {
        float minimumU = minimum(geometry.y);
        float maximumU = maximum(geometry.y);
        float minimumV = minimum(geometry.z);
        float maximumV = maximum(geometry.z);
        float[] cornerU = new float[4];
        float[] cornerV = new float[4];
        boolean[] seen = new boolean[4];
        for (int vertex = 0; vertex < 4; vertex++) {
            int highU = geometry.y[vertex] == maximumU ? 1 : 0;
            int highV = geometry.z[vertex] == maximumV ? 1 : 0;
            int corner = highU | highV << 1;
            if (seen[corner]) {
                throw new AssertionError("Resolved geometry does not have four rectangle corners");
            }
            seen[corner] = true;
            cornerU[corner] = uv.u(vertex);
            cornerV[corner] = uv.v(vertex);
        }
        float lowU = edges.u().get(cell.u()) / (float) gridSize;
        float highU = edges.u().get(cell.u() + 1) / (float) gridSize;
        float lowV = edges.v().get(cell.v()) / (float) gridSize;
        float highV = edges.v().get(cell.v() + 1) / (float) gridSize;
        return List.of(
                quantize(bilinear(cornerU, minimumU, maximumU, minimumV, maximumV, lowU, lowV)),
                quantize(bilinear(cornerV, minimumU, maximumU, minimumV, maximumV, lowU, lowV)),
                quantize(bilinear(cornerU, minimumU, maximumU, minimumV, maximumV, highU, lowV)),
                quantize(bilinear(cornerV, minimumU, maximumU, minimumV, maximumV, highU, lowV)),
                quantize(bilinear(cornerU, minimumU, maximumU, minimumV, maximumV, highU, highV)),
                quantize(bilinear(cornerV, minimumU, maximumU, minimumV, maximumV, highU, highV)),
                quantize(bilinear(cornerU, minimumU, maximumU, minimumV, maximumV, lowU, highV)),
                quantize(bilinear(cornerV, minimumU, maximumU, minimumV, maximumV, lowU, highV)));
    }

    private static float bilinear(
            float[] corners,
            float minimumU,
            float maximumU,
            float minimumV,
            float maximumV,
            float u,
            float v) {
        float x = (u - minimumU) / (maximumU - minimumU);
        float y = (v - minimumV) / (maximumV - minimumV);
        float bottom = corners[0] + x * (corners[1] - corners[0]);
        float top = corners[2] + x * (corners[3] - corners[2]);
        return bottom + y * (top - bottom);
    }

    private static List<Integer> cellUv(int gridSize, Edges edges, Cell cell) {
        int minimumU = quantize(edges.u().get(cell.u()) / (float) gridSize);
        int maximumU = quantize(edges.u().get(cell.u() + 1) / (float) gridSize);
        int minimumV = quantize(edges.v().get(cell.v()) / (float) gridSize);
        int maximumV = quantize(edges.v().get(cell.v() + 1) / (float) gridSize);
        return List.of(
                minimumU, minimumV,
                maximumU, minimumV,
                maximumU, maximumV,
                minimumU, maximumV);
    }

    private static boolean sameMedium(Face first, Face second) {
        boolean sameIdentity = first.mediumFamily() != 0
                        && first.mediumFamily() == second.mediumFamily()
                || SPRITES[first.sprite()].id().equals(SPRITES[second.sprite()].id());
        return first.color() == second.color() && sameIdentity;
    }

    private static TransmissiveTopology topology(Face face) {
        return face.kind() == Kind.SOLID_TRANSMISSIVE
                ? TransmissiveTopology.SOLID
                : TransmissiveTopology.NONE;
    }

    private static CapturedSectionGeometry.Surface surface(Face face) {
        return CapturedSectionGeometry.Surface.uniform(
                face.color(),
                face.kind() == Kind.OPAQUE
                        ? CapturedSectionGeometry.Layer.OPAQUE
                        : CapturedSectionGeometry.Layer.TRANSLUCENT,
                false,
                false,
                false,
                false,
                false,
                true,
                false,
                0,
                SPRITES[face.sprite()],
                new CapturedSectionGeometry.BlockFacts(
                        face.normalSign() > 0 ? 0 : 1,
                        0,
                        0,
                        face.mediumFamily()));
    }

    private static CapturedSectionGeometry.MutableQuad quad(int gridSize, Face face) {
        float minimumU = face.minimumU() / (float) gridSize;
        float maximumU = face.maximumU() / (float) gridSize;
        float minimumV = face.minimumV() / (float) gridSize;
        float maximumV = face.maximumV() / (float) gridSize;
        float[] u = face.normalSign() > 0
                ? new float[] {minimumU, maximumU, maximumU, minimumU}
                : new float[] {minimumU, minimumU, maximumU, maximumU};
        float[] v = face.normalSign() > 0
                ? new float[] {minimumV, minimumV, maximumV, maximumV}
                : new float[] {minimumV, maximumV, maximumV, minimumV};
        CapturedSectionGeometry.MutableQuad result = new CapturedSectionGeometry.MutableQuad();
        for (int vertex = 0; vertex < 4; vertex++) {
            result.x[vertex] = 1.0F;
            result.y[vertex] = u[vertex];
            result.z[vertex] = v[vertex];
            result.u[vertex] = u[vertex];
            result.v[vertex] = v[vertex];
        }
        result.normalX = face.normalSign();
        return result;
    }

    private static CapturedSprite sprite(String path, int textureId) {
        return new CapturedSprite(
                new SpriteId("prime", path),
                textureId,
                16,
                16,
                false,
                new int[] {0},
                null);
    }

    private static HashMap<Cell, ArrayList<SurfaceKey>> emptyCells(Edges edges) {
        HashMap<Cell, ArrayList<SurfaceKey>> result = new HashMap<>();
        for (int v = 0; v + 1 < edges.v().size(); v++) {
            for (int u = 0; u + 1 < edges.u().size(); u++) {
                result.put(new Cell(u, v), new ArrayList<>());
            }
        }
        return result;
    }

    private static Canonical canonical(
            Edges edges, Map<Cell, ArrayList<SurfaceKey>> cells) {
        ArrayList<CellValue> values = new ArrayList<>(cells.size());
        Comparator<SurfaceKey> comparator = Comparator.comparing(SurfaceKey::toString);
        for (Map.Entry<Cell, ArrayList<SurfaceKey>> entry : cells.entrySet()) {
            entry.getValue().removeIf(java.util.Objects::isNull);
            entry.getValue().sort(comparator);
            values.add(new CellValue(entry.getKey(), List.copyOf(entry.getValue())));
        }
        values.sort(Comparator.comparing(CellValue::cell));
        return new Canonical(edges, List.copyOf(values));
    }

    private static int quantize(float value) {
        return Math.round(value * 1_000_000.0F);
    }

    private static float minimum(float[] values) {
        float result = Float.POSITIVE_INFINITY;
        for (float value : values) {
            result = Math.min(result, value);
        }
        return result;
    }

    private static float maximum(float[] values) {
        float result = Float.NEGATIVE_INFINITY;
        for (float value : values) {
            result = Math.max(result, value);
        }
        return result;
    }

    record Canonical(Edges edges, List<CellValue> cells) {
    }

    record Edges(List<Integer> u, List<Integer> v) {
        static Edges of(Scenario scenario) {
            TreeSet<Integer> u = new TreeSet<>();
            TreeSet<Integer> v = new TreeSet<>();
            for (Face face : scenario.faces()) {
                u.add(face.minimumU());
                u.add(face.maximumU());
                v.add(face.minimumV());
                v.add(face.maximumV());
            }
            return new Edges(List.copyOf(u), List.copyOf(v));
        }
    }

    record Cell(int u, int v) implements Comparable<Cell> {
        @Override
        public int compareTo(Cell other) {
            int result = Integer.compare(this.v, other.v);
            return result != 0 ? result : Integer.compare(this.u, other.u);
        }
    }

    record CellValue(Cell cell, List<SurfaceKey> surfaces) {
    }

    record SurfaceKey(
            SurfaceDefinition.InterfaceMode mode,
            BindingKey primary,
            BindingKey secondary,
            MediumKey positiveMedium,
            MediumKey negativeMedium,
            boolean positiveOnly) {
    }

    record BindingKey(
            int owner,
            SpriteId texture,
            int color,
            BuiltinMaterialClass builtinMaterial,
            TransmissiveTopology topology,
            List<Integer> uv) {
    }

    record MediumKey(
            int owner,
            SpriteId texture,
            int color,
            TransmissiveTopology topology,
            int referenceU,
            int referenceV) {
    }
}
