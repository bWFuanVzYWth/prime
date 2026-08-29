package dev.prime.render.terrain;

import dev.prime.render.scene.CapturedSprite;
import dev.prime.render.scene.CapturedSectionGeometry;
import dev.prime.render.material.BuiltinMaterialClass;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Invocation-local lowering stage used by the cluster scene translator.
 *
 * <p>This class deliberately has no knowledge of Mixins, render tasks, block states, or model
 * selection. It consumes explicit translated quad semantics and emits immutable Section-local
 * mesh parts which are immediately assembled into one cluster result.
 */
public final class SectionMeshAccumulator {
    private static final int[] FIRST_TRIANGLE = new int[] {0, 1, 2};
    private static final int[] REVERSED_FIRST_TRIANGLE = new int[] {0, 2, 1};
    private static final int[] SECOND_TRIANGLE = new int[] {0, 2, 3};
    private static final int[] REVERSED_SECOND_TRIANGLE = new int[] {0, 3, 2};

    private final boolean buildOpacityMicromap;
    private final LabPbrMaterialSet labPbrMaterials;
    private final int segmentTriangleTarget;
    private final int maxOpacity2StateSubdivisionLevel;
    private final int maxOpacity4StateSubdivisionLevel;
    private final Map<EmissionDistribution.Key, EmissionDistribution> emissionBuildCache;
    private final ArrayList<CpuSectionMesh> segments = new ArrayList<>();
    private final ArrayList<MergeFace> mergeFaces = new ArrayList<>();
    private MeshBuilder opaque;
    private MeshBuilder cutout;
    private MeshBuilder transmissive;
    private OpacityMicromapData.Builder opacityMicromap;
    private CpuSectionLights.Builder lights;
    private int triangleCount;
    private boolean built;

    public SectionMeshAccumulator(
            LabPbrMaterialSet labPbrMaterials, boolean buildOpacityMicromap) {
        this(labPbrMaterials, buildOpacityMicromap,
                TerrainMemoryBudget.TARGET_SEGMENT_TRIANGLES,
                OpacityMicromapData.MAX_SUBDIVISION_LEVEL);
    }

    public SectionMeshAccumulator(
            LabPbrMaterialSet labPbrMaterials,
            boolean buildOpacityMicromap,
            int segmentTriangleTarget) {
        this(
                labPbrMaterials,
                buildOpacityMicromap,
                segmentTriangleTarget,
                OpacityMicromapData.MAX_SUBDIVISION_LEVEL,
                OpacityMicromapData.MAX_SUBDIVISION_LEVEL);
    }

    public SectionMeshAccumulator(
            LabPbrMaterialSet labPbrMaterials,
            boolean buildOpacityMicromap,
            int segmentTriangleTarget,
            int maxOpacityMicromapSubdivisionLevel) {
        this(
                labPbrMaterials,
                buildOpacityMicromap,
                segmentTriangleTarget,
                maxOpacityMicromapSubdivisionLevel,
                maxOpacityMicromapSubdivisionLevel);
    }

    public SectionMeshAccumulator(
            LabPbrMaterialSet labPbrMaterialSet,
            boolean buildOpacityMicromap,
            int segmentTriangleTarget,
            int maxOpacity2StateSubdivisionLevel,
            int maxOpacity4StateSubdivisionLevel) {
        this(
                labPbrMaterialSet,
                buildOpacityMicromap,
                segmentTriangleTarget,
                maxOpacity2StateSubdivisionLevel,
                maxOpacity4StateSubdivisionLevel,
                new HashMap<>());
    }

    SectionMeshAccumulator(
            LabPbrMaterialSet labPbrMaterialSet,
            boolean buildOpacityMicromap,
            int segmentTriangleTarget,
            int maxOpacity2StateSubdivisionLevel,
            int maxOpacity4StateSubdivisionLevel,
            Map<EmissionDistribution.Key, EmissionDistribution> emissionBuildCache) {
        if (segmentTriangleTarget < 2 || (segmentTriangleTarget & 1) != 0) {
            throw new IllegalArgumentException(
                    "Section mesh segment capacity must contain whole quads");
        }
        this.labPbrMaterials = Objects.requireNonNull(
                labPbrMaterialSet, "labPbrMaterials");
        this.buildOpacityMicromap = buildOpacityMicromap;
        this.segmentTriangleTarget = segmentTriangleTarget;
        if (maxOpacity2StateSubdivisionLevel < 0
                || maxOpacity4StateSubdivisionLevel < 0) {
            throw new IllegalArgumentException(
                    "Opacity-micromap subdivision limit must be nonnegative");
        }
        this.maxOpacity2StateSubdivisionLevel = maxOpacity2StateSubdivisionLevel;
        this.maxOpacity4StateSubdivisionLevel = maxOpacity4StateSubdivisionLevel;
        this.emissionBuildCache = Objects.requireNonNull(
                emissionBuildCache, "emissionBuildCache");
        this.beginSegment();
    }

    public void addQuad(Quad quad, Surface surface) {
        if (this.built) {
            throw new IllegalStateException("Section mesh was already built");
        }
        requireValidAttributes(Objects.requireNonNull(quad, "quad"));
        Objects.requireNonNull(surface, "surface").requireComplete();
        MergeFace mergeFace = MergeFace.tryCreate(
                quad, surface, this.labPbrMaterials, this.buildOpacityMicromap);
        if (mergeFace != null) {
            this.mergeFaces.add(mergeFace);
            return;
        }
        if (this.triangleCount >= this.segmentTriangleTarget) {
            this.finishSegment();
        }
        MeshBuilder destination = surface.geometryTransmissive()
                ? this.transmissive
                : (surface.geometryCutout() ? this.cutout : this.opaque);
        int[] first = orientTriangle(quad, FIRST_TRIANGLE, REVERSED_FIRST_TRIANGLE);
        int[] second = orientTriangle(quad, SECOND_TRIANGLE, REVERSED_SECOND_TRIANGLE);
        this.triangleCount += this.emitTriangle(destination, quad, first, surface) ? 1 : 0;
        this.triangleCount += this.emitTriangle(destination, quad, second, surface) ? 1 : 0;
    }

    private static void requireValidAttributes(Quad quad) {
        boolean finite = Float.isFinite(quad.normalX)
                && Float.isFinite(quad.normalY)
                && Float.isFinite(quad.normalZ);
        boolean normalizedUv = true;
        for (int index = 0; index < 4; index++) {
            finite &= Float.isFinite(quad.x[index])
                    && Float.isFinite(quad.y[index])
                    && Float.isFinite(quad.z[index])
                    && Float.isFinite(quad.u[index])
                    && Float.isFinite(quad.v[index]);
            normalizedUv &= quad.u[index] >= 0.0F
                    && quad.u[index] <= 1.0F
                    && quad.v[index] >= 0.0F
                    && quad.v[index] <= 1.0F;
        }
        if (!finite) {
            throw new IllegalArgumentException(
                    "Captured Section quad contains a non-finite vertex attribute");
        }
        if (!normalizedUv) {
            throw new IllegalArgumentException(
                    "Captured Section quad contains a non-normalized local texture UV");
        }
    }

    public CpuSectionGeometry build() {
        if (this.built) {
            throw new IllegalStateException("Section mesh was already built");
        }
        this.built = true;
        this.finishSegment();
        return new CpuSectionGeometry(this.segments, this.mergeFaces);
    }

    private void beginSegment() {
        this.opaque = new MeshBuilder();
        this.cutout = new MeshBuilder();
        this.transmissive = new MeshBuilder();
        this.opacityMicromap = this.buildOpacityMicromap
                ? new OpacityMicromapData.Builder(
                        this.maxOpacity2StateSubdivisionLevel,
                        this.maxOpacity4StateSubdivisionLevel)
                : null;
        this.lights = new CpuSectionLights.Builder(this.emissionBuildCache);
        this.triangleCount = 0;
    }

    private void finishSegment() {
        if (this.triangleCount == 0) {
            return;
        }
        float[] positions = concatenate(
                this.opaque.positions, this.cutout.positions, this.transmissive.positions);
        int[] primitives = concatenate(
                this.opaque.primitives, this.cutout.primitives, this.transmissive.primitives);
        ArrayList<int[]> relations = new ArrayList<>(
                this.opaque.relations.size()
                        + this.cutout.relations.size()
                        + this.transmissive.relations.size());
        relations.addAll(this.opaque.relations);
        relations.addAll(this.cutout.relations);
        relations.addAll(this.transmissive.relations);
        this.segments.add(new CpuSectionMesh(
                positions,
                primitives,
                SurfaceRelationTable.encode(relations),
                this.opaque.triangleCount,
                this.cutout.triangleCount,
                this.transmissive.triangleCount,
                this.opacityMicromap == null
                        ? OpacityMicromapData.fullyUnknown(this.cutout.triangleCount)
                        : this.opacityMicromap.build(),
                this.lights.build()));
        if (!this.built) {
            this.beginSegment();
        }
    }

    private static int[] orientTriangle(Quad quad, int[] forward, int[] reversed) {
        int first = forward[0];
        int second = forward[1];
        int third = forward[2];
        float edgeOneX = quad.x[second] - quad.x[first];
        float edgeOneY = quad.y[second] - quad.y[first];
        float edgeOneZ = quad.z[second] - quad.z[first];
        float edgeTwoX = quad.x[third] - quad.x[first];
        float edgeTwoY = quad.y[third] - quad.y[first];
        float edgeTwoZ = quad.z[third] - quad.z[first];
        float normalX = edgeOneY * edgeTwoZ - edgeOneZ * edgeTwoY;
        float normalY = edgeOneZ * edgeTwoX - edgeOneX * edgeTwoZ;
        float normalZ = edgeOneX * edgeTwoY - edgeOneY * edgeTwoX;
        double orientation = (double) normalX * quad.normalX
                + (double) normalY * quad.normalY
                + (double) normalZ * quad.normalZ;
        return orientation < 0.0
                ? reversed
                : forward;
    }

    private boolean emitTriangle(
            MeshBuilder destination, Quad quad, int[] indices, Surface surface) {
        int firstIndex = indices[0];
        int secondIndex = indices[1];
        int thirdIndex = indices[2];
        float firstX = quad.x[firstIndex];
        float firstY = quad.y[firstIndex];
        float firstZ = quad.z[firstIndex];
        float secondX = quad.x[secondIndex];
        float secondY = quad.y[secondIndex];
        float secondZ = quad.z[secondIndex];
        float thirdX = quad.x[thirdIndex];
        float thirdY = quad.y[thirdIndex];
        float thirdZ = quad.z[thirdIndex];
        float edge1X = secondX - firstX;
        float edge1Y = secondY - firstY;
        float edge1Z = secondZ - firstZ;
        float edge2X = thirdX - firstX;
        float edge2Y = thirdY - firstY;
        float edge2Z = thirdZ - firstZ;
        float normalX = edge1Y * edge2Z - edge1Z * edge2Y;
        float normalY = edge1Z * edge2X - edge1X * edge2Z;
        float normalZ = edge1X * edge2Y - edge1Y * edge2X;
        double normalLength = Math.sqrt(
                (double) normalX * normalX
                        + (double) normalY * normalY
                        + (double) normalZ * normalZ);
        if (!(normalLength > 0.0) || !Double.isFinite(normalLength)) {
            return false;
        }
        float unitNormalX = (float) (normalX / normalLength);
        float unitNormalY = (float) (normalY / normalLength);
        float unitNormalZ = (float) (normalZ / normalLength);
        destination.positions.add(firstX);
        destination.positions.add(firstY);
        destination.positions.add(firstZ);
        destination.positions.add(secondX);
        destination.positions.add(secondY);
        destination.positions.add(secondZ);
        destination.positions.add(thirdX);
        destination.positions.add(thirdY);
        destination.positions.add(thirdZ);

        float uv0U = quad.u[firstIndex];
        float uv0V = quad.v[firstIndex];
        float uv1U = quad.u[secondIndex];
        float uv1V = quad.v[secondIndex];
        float uv2U = quad.u[thirdIndex];
        float uv2V = quad.v[thirdIndex];
        int packedUv0 = PrimitivePacking.packUv(uv0U, uv0V);
        int packedUv1 = PrimitivePacking.packUv(uv1U, uv1V);
        int packedUv2 = PrimitivePacking.packUv(uv2U, uv2V);
        if (destination == this.cutout && this.opacityMicromap != null) {
            if (surface.hasSurfaceRelation()) {
                // Opaque/transparent OMM states may skip any-hit. Directional material sheets
                // and layered surfaces require any-hit to resolve the selected side/material.
                this.opacityMicromap.addFullyUnknownTriangle();
            } else {
                this.opacityMicromap.addTriangle(
                        surface.sprite(), packedUv0, packedUv1, packedUv2);
            }
        }
        int packedTint = PrimitivePacking.packTint(surface.tint());
        destination.primitives.add(packedUv0);
        destination.primitives.add(packedUv1);
        destination.primitives.add(packedUv2);

        int packedUvDensity = PrimitivePacking.packUvDensity(
                edge1X,
                edge1Y,
                edge1Z,
                edge2X,
                edge2Y,
                edge2Z,
                uv1U - uv0U,
                uv1V - uv0V,
                uv2U - uv0U,
                uv2V - uv0V);
        long packedTangent = PrimitivePacking.packTriangleTangent(
                edge1X,
                edge1Y,
                edge1Z,
                edge2X,
                edge2Y,
                edge2Z,
                uv1U - uv0U,
                uv1V - uv0V,
                uv2U - uv0U,
                uv2V - uv0V,
                unitNormalX,
                unitNormalY,
                unitNormalZ);
        int flags = PrimitivePacking.encode(MaterialRecipeResolver.resolve(
                surface.sprite(),
                surface.builtinMaterialClass(),
                surface.animated(),
                surface.water(),
                surface.foliage(),
                this.labPbrMaterials,
                surface.geometryCutout(),
                surface.geometryTransmissive(),
                surface.thinWalled(),
                (packedTangent & 0x1_0000_0000L) != 0L,
                false));
        packedTint = PrimitivePacking.packTintControl(packedTint, flags);
        int encodedEmitterIndex = this.lights.addTriangle(
                firstX,
                firstY,
                firstZ,
                secondX,
                secondY,
                secondZ,
                thirdX,
                thirdY,
                thirdZ,
                packedUv0,
                packedUv1,
                packedUv2,
                surface.tint(),
                packedTint,
                surface.cutout(),
                surface.emitterTwoSided(),
                surface.lightEmission(),
                surface.sprite(),
                this.labPbrMaterials.emissionMap(surface.sprite().id()));
        destination.primitives.add(packedTint);
        destination.primitives.add(0);
        destination.primitives.add(encodedEmitterIndex == 0
                ? PrimitivePacking.packControlTexture(flags, surface.sprite().textureId())
                : PrimitivePacking.packControlEmitter(flags, encodedEmitterIndex - 1));
        destination.primitives.add(packedUvDensity);
        destination.primitives.add((int) packedTangent);
        destination.relations.add(this.surfaceRelationRecord(
                surface,
                quad,
                indices,
                surface.definition instanceof SurfaceDefinition.Overlay
                        ? flags | PrimitivePacking.CONTROL_ALPHA_CUTOUT
                        : flags));
        destination.triangleCount++;
        return true;
    }

    private int[] surfaceRelationRecord(
            Surface surface,
            Quad quad,
            int[] indices,
            int primaryMaterialFlags) {
        SurfaceDefinition definition = surface.definition;
        if (definition == null || definition instanceof SurfaceDefinition.Single) {
            return null;
        }
        if (definition instanceof SurfaceDefinition.Boundary boundary) {
            SurfaceDefinition.MediumEndpoint endpoint = boundary.positiveMedium();
            CapturedSectionGeometry.Surface adjacent = endpoint.surface();
            int adjacentControl = PrimitivePacking.encode(
                    MaterialRecipeResolver.resolve(
                            adjacent,
                            this.labPbrMaterials,
                            ClusterSceneTranslator.isCutout(adjacent),
                            ClusterSceneTranslator.isTransmissive(adjacent),
                            adjacent.foliage()
                                    || endpoint.transmissiveTopology().thinWalled(),
                            false,
                            false));
            int control = CpuSectionMesh.SURFACE_RELATION_BOUNDARY
                    | CpuSectionMesh.SURFACE_RELATION_MICRO_GAP_ELIGIBLE
                    | PrimitivePacking.materialRecipeControl(adjacentControl) << 8;
            return new int[] {
                control,
                PrimitivePacking.packUv(endpoint.referenceU(), endpoint.referenceV()),
                PrimitivePacking.packTint(
                        ClusterSceneTranslator.averageColor(adjacent)),
                adjacent.sprite().textureId()
            };
        }
        SurfaceDefinition.MaterialBinding secondary;
        int control;
        if (definition instanceof SurfaceDefinition.Overlay overlay) {
            secondary = overlay.secondary();
            control = CpuSectionMesh.SURFACE_RELATION_OVERLAY;
            if (overlay.positiveOnly()) {
                control |= CpuSectionMesh.SURFACE_RELATION_POSITIVE_ONLY;
            }
            control |= PrimitivePacking.materialRecipeControl(primaryMaterialFlags) << 8;
        } else if (definition instanceof SurfaceDefinition.Bilateral bilateral) {
            secondary = bilateral.secondary();
            control = CpuSectionMesh.SURFACE_RELATION_BILATERAL;
        } else {
            throw new IllegalStateException(
                    "Unhandled surface definition " + definition.getClass().getSimpleName());
        }
        int[] primitive = this.packMaterialPrimitive(
                secondary, quad, indices);
        int[] relation = new int[1 + CpuSectionMesh.PRIMITIVE_WORDS];
        relation[0] = control;
        System.arraycopy(primitive, 0, relation, 1, primitive.length);
        return relation;
    }

    private int[] packMaterialPrimitive(
            SurfaceDefinition.MaterialBinding binding,
            Quad quad,
            int[] indices) {
        SurfaceDefinition.UvMapping uv = binding.uv();
        int firstIndex = indices[0];
        int secondIndex = indices[1];
        int thirdIndex = indices[2];
        float firstX = quad.x[firstIndex];
        float firstY = quad.y[firstIndex];
        float firstZ = quad.z[firstIndex];
        float edge1X = quad.x[secondIndex] - firstX;
        float edge1Y = quad.y[secondIndex] - firstY;
        float edge1Z = quad.z[secondIndex] - firstZ;
        float edge2X = quad.x[thirdIndex] - firstX;
        float edge2Y = quad.y[thirdIndex] - firstY;
        float edge2Z = quad.z[thirdIndex] - firstZ;
        float uv0U = uv.u(firstIndex);
        float uv0V = uv.v(firstIndex);
        float uv1U = uv.u(secondIndex);
        float uv1V = uv.v(secondIndex);
        float uv2U = uv.u(thirdIndex);
        float uv2V = uv.v(thirdIndex);
        float normalX = edge1Y * edge2Z - edge1Z * edge2Y;
        float normalY = edge1Z * edge2X - edge1X * edge2Z;
        float normalZ = edge1X * edge2Y - edge1Y * edge2X;
        long packedTangent = PrimitivePacking.packTriangleTangent(
                edge1X, edge1Y, edge1Z,
                edge2X, edge2Y, edge2Z,
                uv1U - uv0U, uv1V - uv0V,
                uv2U - uv0U, uv2V - uv0V,
                normalX, normalY, normalZ);
        CapturedSectionGeometry.Surface captured = binding.surface();
        int flags = PrimitivePacking.encode(MaterialRecipeResolver.resolve(
                captured,
                this.labPbrMaterials,
                ClusterSceneTranslator.isCutout(captured),
                ClusterSceneTranslator.isTransmissive(captured),
                captured.foliage()
                        || binding.transmissiveTopology().thinWalled(),
                (packedTangent & 0x1_0000_0000L) != 0L,
                false));
        int packedTint = PrimitivePacking.packTintControl(
                PrimitivePacking.packTint(
                        ClusterSceneTranslator.averageColor(binding.surface())),
                flags);
        return new int[] {
            PrimitivePacking.packUv(uv0U, uv0V),
            PrimitivePacking.packUv(uv1U, uv1V),
            PrimitivePacking.packUv(uv2U, uv2V),
            packedTint,
            0,
            PrimitivePacking.packControlTexture(flags, captured.sprite().textureId()),
            PrimitivePacking.packUvDensity(
                    edge1X, edge1Y, edge1Z,
                    edge2X, edge2Y, edge2Z,
                    uv1U - uv0U, uv1V - uv0V,
                    uv2U - uv0U, uv2V - uv0V),
            (int) packedTangent
        };
    }

    private static float[] concatenate(
            FloatArrayBuilder first, FloatArrayBuilder second, FloatArrayBuilder third) {
        int secondOffset = first.size;
        int thirdOffset = Math.addExact(secondOffset, second.size);
        float[] result = new float[Math.addExact(thirdOffset, third.size)];
        System.arraycopy(first.values, 0, result, 0, first.size);
        System.arraycopy(second.values, 0, result, secondOffset, second.size);
        System.arraycopy(third.values, 0, result, thirdOffset, third.size);
        return result;
    }

    private static int[] concatenate(
            IntArrayBuilder first, IntArrayBuilder second, IntArrayBuilder third) {
        int secondOffset = first.size;
        int thirdOffset = Math.addExact(secondOffset, second.size);
        int[] result = new int[Math.addExact(thirdOffset, third.size)];
        System.arraycopy(first.values, 0, result, 0, first.size);
        System.arraycopy(second.values, 0, result, secondOffset, second.size);
        System.arraycopy(third.values, 0, result, thirdOffset, third.size);
        return result;
    }

    /** Mutable quad scratch owned by one capture session and never published. */
    public static final class Quad {
        public final float[] x = new float[4];
        public final float[] y = new float[4];
        public final float[] z = new float[4];
        public final float[] u = new float[4];
        public final float[] v = new float[4];
        public float normalX;
        public float normalY;
        public float normalZ;
    }

    /** Mutable per-session scratch for semantics kept outside Minecraft's mesh interfaces. */
    public static final class Surface {
        private int tint;
        private boolean cutout;
        private boolean animated;
        private boolean transmissive;
        private boolean thinWalled;
        private boolean water;
        private boolean foliage;
        private boolean mergeable;
        private boolean rasterOverlay;
        private int lightEmission;
        private CapturedSprite sprite;
        private SurfaceDefinition definition;
        private BuiltinMaterialClass builtinMaterialClass;

        public Surface set(
                int tint,
                boolean cutout,
                boolean animated,
                boolean transmissive,
                boolean thinWalled,
                boolean water,
                boolean foliage,
                boolean mergeable,
                int lightEmission,
                CapturedSprite sprite) {
            return this.set(
                    tint,
                    cutout,
                    animated,
                    transmissive,
                    thinWalled,
                    water,
                    foliage,
                    mergeable,
                    false,
                    lightEmission,
                    sprite,
                    BuiltinMaterialClass.DEFAULT);
        }

        public Surface set(
                int tint,
                boolean cutout,
                boolean animated,
                boolean transmissive,
                boolean thinWalled,
                boolean water,
                boolean foliage,
                boolean mergeable,
                boolean rasterOverlay,
                int lightEmission,
                CapturedSprite sprite) {
            return this.set(
                    tint,
                    cutout,
                    animated,
                    transmissive,
                    thinWalled,
                    water,
                    foliage,
                    mergeable,
                    rasterOverlay,
                    lightEmission,
                    sprite,
                    BuiltinMaterialClass.DEFAULT);
        }

        public Surface set(
                int tint,
                boolean cutout,
                boolean animated,
                boolean transmissive,
                boolean thinWalled,
                boolean water,
                boolean foliage,
                boolean mergeable,
                boolean rasterOverlay,
                int lightEmission,
                CapturedSprite sprite,
                BuiltinMaterialClass builtinMaterialClass) {
            this.tint = tint;
            this.cutout = cutout;
            this.animated = animated;
            this.transmissive = transmissive;
            this.thinWalled = thinWalled;
            this.water = water;
            this.foliage = foliage;
            this.mergeable = mergeable;
            this.rasterOverlay = rasterOverlay;
            this.lightEmission = lightEmission;
            this.sprite = Objects.requireNonNull(sprite, "sprite");
            this.builtinMaterialClass = Objects.requireNonNull(
                    builtinMaterialClass, "builtinMaterialClass");
            this.definition = null;
            return this;
        }

        public Surface setDefinition(SurfaceDefinition definition) {
            this.definition = Objects.requireNonNull(definition, "definition");
            return this;
        }

        private void requireComplete() {
            Objects.requireNonNull(this.sprite, "surface sprite");
        }

        int tint() {
            return this.tint;
        }

        boolean cutout() {
            return this.cutout;
        }

        boolean animated() {
            return this.animated;
        }

        boolean transmissive() {
            return this.transmissive;
        }

        boolean thinWalled() {
            return this.thinWalled;
        }

        boolean water() {
            return this.water;
        }

        boolean foliage() {
            return this.foliage;
        }

        boolean mergeable() {
            return this.mergeable;
        }

        boolean rasterOverlay() {
            return this.rasterOverlay;
        }

        int lightEmission() {
            return this.lightEmission;
        }

        CapturedSprite sprite() {
            return this.sprite;
        }

        BuiltinMaterialClass builtinMaterialClass() {
            return this.builtinMaterialClass;
        }

        boolean geometryCutout() {
            return this.definition instanceof SurfaceDefinition.Overlay
                    ? false
                    : this.cutout;
        }

        boolean geometryTransmissive() {
            return this.transmissive;
        }

        boolean hasSurfaceRelation() {
            return this.definition != null
                    && !(this.definition instanceof SurfaceDefinition.Single);
        }

        boolean emitterTwoSided() {
            return this.cutout
                    && (!(this.definition instanceof SurfaceDefinition.Overlay overlay)
                            || !overlay.positiveOnly());
        }
    }

    private static final class MeshBuilder {
        private final FloatArrayBuilder positions = new FloatArrayBuilder();
        private final IntArrayBuilder primitives = new IntArrayBuilder();
        private final ArrayList<int[]> relations = new ArrayList<>();
        private int triangleCount;
    }

    private static final class FloatArrayBuilder {
        private float[] values = new float[1024];
        private int size;

        private void add(float value) {
            if (this.size == this.values.length) {
                this.values = Arrays.copyOf(this.values, this.values.length * 2);
            }
            this.values[this.size++] = value;
        }
    }

    private static final class IntArrayBuilder {
        private int[] values = new int[1024];
        private int size;

        private void add(int value) {
            if (this.size == this.values.length) {
                this.values = Arrays.copyOf(this.values, this.values.length * 2);
            }
            this.values[this.size++] = value;
        }
    }
}
