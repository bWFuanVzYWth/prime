package dev.prime.render.terrain;

import dev.prime.render.scene.CapturedSectionGeometry;
import java.util.Objects;
import net.minecraft.core.Direction;

/**
 * Pure translation entry from one captured 4x4x4 cluster to Prime's CPU upload payload.
 *
 * <p>Mutable accumulators are invocation-local implementation details. No input is mutated and no
 * state escapes except the returned immutable payload.
 */
public final class ClusterSceneTranslator {
    private ClusterSceneTranslator() {
    }

    public static CpuClusterMesh translate(
            CapturedCluster captured,
            LabPbrMaterialSet materials,
            ClusterTranslationSettings settings) {
        return translate(
                new ClusterTranslationInput(captured, materials, settings),
                ClusterTranslationControl.UNINTERRUPTIBLE);
    }

    public static CpuClusterMesh translate(ClusterTranslationInput input) {
        return translate(input, ClusterTranslationControl.UNINTERRUPTIBLE);
    }

    public static CpuClusterMesh translate(
            ClusterTranslationInput input,
            ClusterTranslationControl control) {
        Objects.requireNonNull(input, "input");
        ClusterTranslationWork work = new ClusterTranslationWork(control);
        work.checkpoint();
        CapturedCluster captured = input.captured();
        LabPbrMaterialSet materials = input.materials();
        ClusterTranslationSettings settings = input.settings();

        SectionClusterMeshBuilder cluster = new SectionClusterMeshBuilder(
                captured.clusterX(),
                captured.clusterY(),
                captured.clusterZ(),
                settings.segmentTriangleTarget(),
                settings.maxOpacity2StateSubdivisionLevel(),
                settings.maxOpacity4StateSubdivisionLevel(),
                settings.voxelSurfacesEnabled(),
                settings.voxelSurfaceMaximumHeight(),
                work);
        TransparentBoundaryResolver.Result boundaries =
                TransparentBoundaryResolver.resolve(
                        captured, !settings.voxelSurfacesEnabled(), work);
        // One translation owns this cache. Section-local light tables retain their original index
        // order while identical source distributions avoid repeating texture sampling work.
        java.util.Map<EmissionDistribution.Key, EmissionDistribution> emissionBuildCache =
                new java.util.HashMap<>();
        for (int localIndex = 0;
                localIndex < SectionCluster.SECTION_COUNT;
                localIndex++) {
            work.checkpoint();
            CapturedSectionGeometry section = captured.section(localIndex);
            if (section == null) {
                continue;
            }
            int sectionX = captured.clusterX() + CapturedCluster.sectionX(localIndex);
            int sectionY = captured.clusterY() + CapturedCluster.sectionY(localIndex);
            int sectionZ = captured.clusterZ() + CapturedCluster.sectionZ(localIndex);
            cluster.add(
                    sectionX,
                    sectionY,
                    sectionZ,
                    translateSection(
                            boundaries.section(localIndex),
                            materials,
                            settings,
                            emissionBuildCache,
                            work));
        }
        work.checkpoint();
        return cluster.build().withCompatibilityIssues(boundaries.issues());
    }

    private static CpuSectionGeometry translateSection(
            java.util.List<TransparentBoundaryResolver.ResolvedQuad> resolvedQuads,
            LabPbrMaterialSet materials,
            ClusterTranslationSettings settings,
            java.util.Map<EmissionDistribution.Key, EmissionDistribution> emissionBuildCache,
            ClusterTranslationWork work) {
        SectionMeshAccumulator accumulator = new SectionMeshAccumulator(
                materials,
                settings.buildOpacityMicromap(),
                settings.segmentTriangleTarget(),
                settings.maxOpacity2StateSubdivisionLevel(),
                settings.maxOpacity4StateSubdivisionLevel(),
                emissionBuildCache);
        SectionMeshAccumulator.Quad quad = new SectionMeshAccumulator.Quad();
        SectionMeshAccumulator.Surface surface = new SectionMeshAccumulator.Surface();
        for (TransparentBoundaryResolver.ResolvedQuad resolved : resolvedQuads) {
            work.step();
            resolved.write(quad);
            SurfaceDefinition definition = resolved.definition();
            CapturedSectionGeometry.Surface capturedSurface =
                    definition.primary().surface();
            if (capturedSurface.fluid() != null) {
                if (!translateFluidQuad(quad, capturedSurface.fluid(), settings)) {
                    continue;
                }
            } else if (!hasArea(quad)) {
                continue;
            }
            boolean cutout = isCutout(capturedSurface);
            boolean transmissive = isTransmissive(capturedSurface);
            surface.set(
                    averageColor(capturedSurface),
                    cutout,
                    capturedSurface.animated(),
                    transmissive,
                    capturedSurface.foliage()
                            || definition.primary()
                                    .transmissiveTopology()
                                    .thinWalled(),
                    capturedSurface.water(),
                    capturedSurface.foliage(),
                    capturedSurface.mergeable(),
                    capturedSurface.rasterOverlay(),
                    capturedSurface.lightEmission(),
                    capturedSurface.sprite(),
                    capturedSurface.builtinMaterialClass())
                    .setDefinition(definition);
            accumulator.addQuad(quad, surface);
        }
        return accumulator.build();
    }

    private static boolean hasArea(SectionMeshAccumulator.Quad quad) {
        return triangleHasArea(quad, 0, 1, 2)
                || triangleHasArea(quad, 0, 2, 3);
    }

    static void requireValidAttributes(CapturedSectionGeometry.Quad quad) {
        boolean finite = Float.isFinite(quad.normalX())
                && Float.isFinite(quad.normalY())
                && Float.isFinite(quad.normalZ());
        boolean normalizedUv = true;
        for (int vertex = 0; vertex < 4; vertex++) {
            finite &= Float.isFinite(quad.x(vertex))
                    && Float.isFinite(quad.y(vertex))
                    && Float.isFinite(quad.z(vertex))
                    && Float.isFinite(quad.u(vertex))
                    && Float.isFinite(quad.v(vertex));
            normalizedUv &= quad.u(vertex) >= 0.0F
                    && quad.u(vertex) <= 1.0F
                    && quad.v(vertex) >= 0.0F
                    && quad.v(vertex) <= 1.0F;
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

    private static boolean triangleHasArea(
            SectionMeshAccumulator.Quad quad,
            int first,
            int second,
            int third) {
        float abX = quad.x[second] - quad.x[first];
        float abY = quad.y[second] - quad.y[first];
        float abZ = quad.z[second] - quad.z[first];
        float acX = quad.x[third] - quad.x[first];
        float acY = quad.y[third] - quad.y[first];
        float acZ = quad.z[third] - quad.z[first];
        float crossX = abY * acZ - abZ * acY;
        float crossY = abZ * acX - abX * acZ;
        float crossZ = abX * acY - abY * acX;
        // Exact zero is omitted only after the raw captured attributes have been validated.
        return crossX != 0.0F || crossY != 0.0F || crossZ != 0.0F;
    }

    static boolean isCutout(CapturedSectionGeometry.Surface surface) {
        return surface.layer() == CapturedSectionGeometry.Layer.CUTOUT
                || surface.foliage()
                || surface.alphaCutOverride();
    }

    static boolean isTransmissive(CapturedSectionGeometry.Surface surface) {
        return surface.layer() == CapturedSectionGeometry.Layer.TRANSLUCENT
                && !surface.alphaCutOverride();
    }

    private static boolean translateFluidQuad(
            SectionMeshAccumulator.Quad quad,
            CapturedSectionGeometry.FluidFacts fluid,
            ClusterTranslationSettings settings) {
        if (settings.closeCoveredFluidGap() && fluid.fullCeiling()) {
            for (int vertex = 0; vertex < 4; vertex++) {
                if (quad.y[vertex] > fluid.localY() + 0.5F) {
                    quad.y[vertex] = fluid.localY() + 1.0F;
                }
            }
        }

        float edgeOneX = quad.x[1] - quad.x[0];
        float edgeOneY = quad.y[1] - quad.y[0];
        float edgeOneZ = quad.z[1] - quad.z[0];
        float edgeTwoX = quad.x[2] - quad.x[0];
        float edgeTwoY = quad.y[2] - quad.y[0];
        float edgeTwoZ = quad.z[2] - quad.z[0];
        float normalX = edgeOneY * edgeTwoZ - edgeOneZ * edgeTwoY;
        float normalY = edgeOneZ * edgeTwoX - edgeOneX * edgeTwoZ;
        float normalZ = edgeOneX * edgeTwoY - edgeOneY * edgeTwoX;
        float squaredNormalLength =
                normalX * normalX + normalY * normalY + normalZ * normalZ;
        if (!(squaredNormalLength > 1.0e-20F)) {
            return false;
        }
        // FluidRenderer emits the outward quad first and optionally appends its exact reversed
        // raster back face. TwoSidedQuadReducer removes that duplicate before this method. Do not
        // infer sidedness from the quad center: a valid shallow or sloped top can lie below the
        // owning block's midpoint, which would invert water medium transitions and lava emission.
        Direction direction =
                Direction.getApproximateNearest(normalX, normalY, normalZ);
        // A full neighboring collision face proves that this raster inset is internal. Keeping it
        // creates a second, nearly coincident ray surface; this is a representation invariant, not
        // an optional visual tweak.
        if (fluid.fullCollision(direction.ordinal())) {
            return false;
        }
        float inverseNormalLength =
                1.0F / (float) Math.sqrt(squaredNormalLength);
        quad.normalX = normalX * inverseNormalLength;
        quad.normalY = normalY * inverseNormalLength;
        quad.normalZ = normalZ * inverseNormalLength;
        return true;
    }

    static int averageColor(CapturedSectionGeometry.Surface surface) {
        int alpha = 0;
        int red = 0;
        int green = 0;
        int blue = 0;
        for (int vertex = 0; vertex < 4; vertex++) {
            int color = surface.color(vertex);
            alpha += color >>> 24;
            red += color >>> 16 & 0xff;
            green += color >>> 8 & 0xff;
            blue += color & 0xff;
        }
        return (alpha + 2) / 4 << 24
                | (red + 2) / 4 << 16
                | (green + 2) / 4 << 8
                | (blue + 2) / 4;
    }
}
