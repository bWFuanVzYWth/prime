package dev.prime.render.terrain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.prime.render.scene.CapturedSectionGeometry;
import dev.prime.render.scene.CapturedSprite;
import dev.prime.render.scene.SpriteId;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

final class ClusterTranslationRelationOracleTest {
    private static final CapturedSprite FIRST = sprite("relation_first", 1);
    private static final CapturedSprite SECOND = sprite("relation_second", 2);
    private static final CapturedSprite THIRD = sprite("relation_third", 3);

    @Test
    void canonicalRelationsExposeSingleOverlayBilateralAndBoundarySemantics() {
        SurfaceDefinition single = resolve(List.of(new QuadSpec(
                        0.0F, 1.0F, 0.0F, opaque(FIRST, null))))
                .getFirst();
        assertEquals(SurfaceDefinition.InterfaceMode.SINGLE, single.interfaceMode());
        assertSame(FIRST, single.primary().surface().sprite());
        assertEquals(TransmissiveTopology.NONE, single.primary().transmissiveTopology());

        SurfaceDefinition overlay = resolve(List.of(
                        new QuadSpec(0.0F, 1.0F, 0.0F, opaque(FIRST, null)),
                        new QuadSpec(0.0F, 1.0F, 0.0F, overlay(SECOND, null))))
                .getFirst();
        assertEquals(SurfaceDefinition.InterfaceMode.OVERLAY, overlay.interfaceMode());
        SurfaceDefinition.Overlay overlayDefinition = (SurfaceDefinition.Overlay) overlay;
        assertSame(SECOND, overlayDefinition.primary().surface().sprite());
        assertSame(FIRST, overlayDefinition.secondary().surface().sprite());
        assertFalse(overlayDefinition.positiveOnly());
        assertUnitUv(overlayDefinition.primary().uv());
        assertUnitUv(overlayDefinition.secondary().uv());

        SurfaceDefinition bilateral = resolve(List.of(
                        new QuadSpec(0.0F, 1.0F, 0.0F, cutout(FIRST, null)),
                        new QuadSpec(0.0F, -1.0F, 0.0F, cutout(SECOND, null))))
                .getFirst();
        assertEquals(SurfaceDefinition.InterfaceMode.BILATERAL, bilateral.interfaceMode());
        SurfaceDefinition.Bilateral bilateralDefinition =
                (SurfaceDefinition.Bilateral) bilateral;
        assertSame(FIRST, bilateralDefinition.primary().surface().sprite());
        assertSame(SECOND, bilateralDefinition.secondary().surface().sprite());
        assertUnitUv(bilateralDefinition.primary().uv());
        assertUnitUv(bilateralDefinition.secondary().uv());

        List<SurfaceDefinition> boundaryDefinitions = resolve(List.of(
                new QuadSpec(
                        0.0F,
                        -1.0F,
                        0.0F,
                        solid(FIRST, new CapturedSectionGeometry.BlockFacts(0, 0, 0, 1))),
                new QuadSpec(
                        0.0F,
                        1.0F,
                        0.0F,
                        solid(SECOND, new CapturedSectionGeometry.BlockFacts(-1, 0, 0, 2)))));
        assertEquals(1, boundaryDefinitions.size());
        SurfaceDefinition.Boundary boundary =
                (SurfaceDefinition.Boundary) boundaryDefinitions.getFirst();
        assertSame(SECOND, boundary.primary().surface().sprite());
        assertSame(FIRST, boundary.positiveMedium().surface().sprite());
        assertSame(SECOND, boundary.negativeMedium().surface().sprite());
        assertEquals(TransmissiveTopology.SOLID, boundary.primary().transmissiveTopology());
    }

    @Test
    void attachedOverlayIsExplicitlyPositiveOnly() {
        CapturedSectionGeometry.BlockFacts substrateOwner =
                new CapturedSectionGeometry.BlockFacts(-1, 0, 0);
        CapturedSectionGeometry.BlockFacts overlayOwner =
                new CapturedSectionGeometry.BlockFacts(0, 0, 0);
        List<SurfaceDefinition> definitions = resolve(List.of(
                new QuadSpec(0.0F, 1.0F, 0.0F, opaque(FIRST, substrateOwner)),
                new QuadSpec(0.0F, -1.0F, 0.0F, attachedOverlay(SECOND, overlayOwner))));

        assertEquals(1, definitions.size());
        SurfaceDefinition.Overlay overlay = (SurfaceDefinition.Overlay) definitions.getFirst();
        assertTrue(overlay.positiveOnly());
        assertSame(SECOND, overlay.primary().surface().sprite());
        assertSame(FIRST, overlay.secondary().surface().sprite());
    }

    @Test
    void exactOverlayPairingKeepsTheEarliestMatchingInput() {
        List<SurfaceDefinition> definitions = resolve(List.of(
                new QuadSpec(0.0F, 1.0F, 0.0F, opaque(FIRST, null)),
                new QuadSpec(0.0F, 1.0F, 0.0F, overlay(SECOND, null)),
                new QuadSpec(0.0F, 1.0F, 0.0F, overlay(THIRD, null))));

        assertEquals(2, definitions.size());
        SurfaceDefinition.Overlay paired = (SurfaceDefinition.Overlay) definitions.getFirst();
        assertSame(SECOND, paired.primary().surface().sprite());
        assertSame(THIRD, definitions.get(1).primary().surface().sprite());
    }

    @ParameterizedTest
    @MethodSource("exactOverlayToleranceCases")
    void exactOverlayIndexPreservesPositionTolerance(
            float offset, SurfaceDefinition.InterfaceMode expectedMode, int expectedCount) {
        List<SurfaceDefinition> definitions = resolve(List.of(
                new QuadSpec(0.0F, 1.0F, 0.0F, opaque(FIRST, null)),
                new QuadSpec(offset, 1.0F, 0.0F, overlay(SECOND, null))));

        assertEquals(expectedCount, definitions.size());
        assertEquals(expectedMode, definitions.getFirst().interfaceMode());
    }

    @Test
    void negativeZeroHasTheSameCanonicalCellSemanticsAsPositiveZero() {
        CapturedSectionGeometry.Surface surface = opaque(FIRST, null);
        List<SurfaceDefinition> positive = resolve(List.of(
                new QuadSpec(0.0F, 1.0F, 0.0F, surface)));
        List<SurfaceDefinition> negative = resolve(List.of(
                new QuadSpec(-0.0F, 1.0F, 0.0F, surface)));

        assertEquals(
                positive.getFirst().interfaceMode(),
                negative.getFirst().interfaceMode());
        assertEquals(
                positive.getFirst().primary(),
                negative.getFirst().primary());
    }

    @ParameterizedTest
    @MethodSource("positionToleranceCases")
    void positionToleranceHasLockedBelowEqualAndAboveBehavior(
            float offset, SurfaceDefinition.InterfaceMode expectedMode, int expectedCount) {
        List<SurfaceDefinition> definitions = resolve(List.of(
                new QuadSpec(
                        offset,
                        -1.0F,
                        0.0F,
                        solid(FIRST, new CapturedSectionGeometry.BlockFacts(0, 0, 0, 1))),
                new QuadSpec(
                        0.0F,
                        1.0F,
                        0.0F,
                        solid(SECOND, new CapturedSectionGeometry.BlockFacts(-1, 0, 0, 2)))));

        assertEquals(expectedCount, definitions.size());
        assertEquals(expectedMode, definitions.getFirst().interfaceMode());
    }

    @ParameterizedTest
    @MethodSource("normalToleranceCases")
    void normalToleranceHasLockedBelowEqualAndAboveBehavior(
            float orthogonal, SurfaceDefinition.InterfaceMode expectedMode, int expectedCount) {
        List<SurfaceDefinition> definitions = resolve(List.of(
                new QuadSpec(
                        0.0F,
                        -1.0F,
                        orthogonal,
                        solid(FIRST, new CapturedSectionGeometry.BlockFacts(0, 0, 0, 1))),
                new QuadSpec(
                        0.0F,
                        1.0F,
                        0.0F,
                        solid(SECOND, new CapturedSectionGeometry.BlockFacts(-1, 0, 0, 2)))));

        assertEquals(expectedCount, definitions.size());
        assertEquals(expectedMode, definitions.getFirst().interfaceMode());
    }

    @ParameterizedTest
    @MethodSource("attachedToleranceCases")
    void attachedSurfaceToleranceHasLockedBelowEqualAndAboveBehavior(
            float offset, SurfaceDefinition.InterfaceMode expectedMode, int expectedCount) {
        List<SurfaceDefinition> definitions = resolve(List.of(
                new QuadSpec(
                        0.0F,
                        1.0F,
                        0.0F,
                        opaque(FIRST, new CapturedSectionGeometry.BlockFacts(-1, 0, 0))),
                new QuadSpec(
                        offset,
                        -1.0F,
                        0.0F,
                        attachedOverlay(
                                SECOND, new CapturedSectionGeometry.BlockFacts(0, 0, 0)))));

        assertEquals(expectedCount, definitions.size());
        assertEquals(expectedMode, definitions.getFirst().interfaceMode());
    }

    private static java.util.stream.Stream<Arguments> positionToleranceCases() {
        float epsilon = 1.0E-5F;
        return java.util.stream.Stream.of(
                Arguments.of(Math.nextDown(epsilon), SurfaceDefinition.InterfaceMode.BOUNDARY, 1),
                Arguments.of(epsilon, SurfaceDefinition.InterfaceMode.BOUNDARY, 1),
                Arguments.of(Math.nextUp(epsilon), SurfaceDefinition.InterfaceMode.SINGLE, 2));
    }

    private static java.util.stream.Stream<Arguments> exactOverlayToleranceCases() {
        float epsilon = 1.0E-5F;
        return java.util.stream.Stream.of(
                Arguments.of(Math.nextDown(epsilon), SurfaceDefinition.InterfaceMode.OVERLAY, 1),
                Arguments.of(epsilon, SurfaceDefinition.InterfaceMode.OVERLAY, 1),
                Arguments.of(Math.nextUp(epsilon), SurfaceDefinition.InterfaceMode.SINGLE, 2));
    }

    private static java.util.stream.Stream<Arguments> normalToleranceCases() {
        float epsilon = 1.0E-4F;
        return java.util.stream.Stream.of(
                Arguments.of(Math.nextDown(epsilon), SurfaceDefinition.InterfaceMode.BOUNDARY, 1),
                Arguments.of(epsilon, SurfaceDefinition.InterfaceMode.BOUNDARY, 1),
                Arguments.of(Math.nextUp(epsilon), SurfaceDefinition.InterfaceMode.SINGLE, 2));
    }

    private static java.util.stream.Stream<Arguments> attachedToleranceCases() {
        float epsilon = 1.0E-3F;
        return java.util.stream.Stream.of(
                Arguments.of(Math.nextDown(epsilon), SurfaceDefinition.InterfaceMode.OVERLAY, 1),
                Arguments.of(epsilon, SurfaceDefinition.InterfaceMode.OVERLAY, 1),
                Arguments.of(Math.nextUp(epsilon), SurfaceDefinition.InterfaceMode.SINGLE, 2));
    }

    private static List<SurfaceDefinition> resolve(List<QuadSpec> specs) {
        CapturedSectionGeometry.Builder section = new CapturedSectionGeometry.Builder();
        for (QuadSpec spec : specs) {
            section.add(quad(spec), spec.surface());
        }
        CapturedCluster.Builder cluster = new CapturedCluster.Builder(0, 0, 0);
        cluster.add(0, 0, 0, section.build());
        TransparentBoundaryResolver.Result result =
                TransparentBoundaryResolver.resolve(cluster.build(), true);
        return result.section(0).stream()
                .map(TransparentBoundaryResolver.ResolvedQuad::definition)
                .toList();
    }

    private static CapturedSectionGeometry.MutableQuad quad(QuadSpec spec) {
        float[] y = spec.normalX() > 0.0F
                ? new float[] {0.0F, 1.0F, 1.0F, 0.0F}
                : new float[] {0.0F, 0.0F, 1.0F, 1.0F};
        float[] z = spec.normalX() > 0.0F
                ? new float[] {0.0F, 0.0F, 1.0F, 1.0F}
                : new float[] {0.0F, 1.0F, 1.0F, 0.0F};
        CapturedSectionGeometry.MutableQuad quad = new CapturedSectionGeometry.MutableQuad();
        for (int vertex = 0; vertex < 4; vertex++) {
            quad.x[vertex] = spec.plane();
            quad.y[vertex] = y[vertex];
            quad.z[vertex] = z[vertex];
            quad.u[vertex] = y[vertex];
            quad.v[vertex] = z[vertex];
        }
        quad.normalX = spec.normalX();
        quad.normalY = spec.normalY();
        return quad;
    }

    private static CapturedSectionGeometry.Surface opaque(
            CapturedSprite sprite, CapturedSectionGeometry.BlockFacts block) {
        return surface(sprite, CapturedSectionGeometry.Layer.OPAQUE, false, false, 0, block);
    }

    private static CapturedSectionGeometry.Surface solid(
            CapturedSprite sprite, CapturedSectionGeometry.BlockFacts block) {
        return surface(sprite, CapturedSectionGeometry.Layer.TRANSLUCENT, false, false, 0, block);
    }

    private static CapturedSectionGeometry.Surface cutout(
            CapturedSprite sprite, CapturedSectionGeometry.BlockFacts block) {
        return surface(sprite, CapturedSectionGeometry.Layer.CUTOUT, false, false, 0, block);
    }

    private static CapturedSectionGeometry.Surface overlay(
            CapturedSprite sprite, CapturedSectionGeometry.BlockFacts block) {
        return surface(sprite, CapturedSectionGeometry.Layer.CUTOUT, true, false, 0, block);
    }

    private static CapturedSectionGeometry.Surface attachedOverlay(
            CapturedSprite sprite, CapturedSectionGeometry.BlockFacts block) {
        return surface(sprite, CapturedSectionGeometry.Layer.CUTOUT, false, true, 1, block);
    }

    private static CapturedSectionGeometry.Surface surface(
            CapturedSprite sprite,
            CapturedSectionGeometry.Layer layer,
            boolean rasterOverlay,
            boolean animated,
            int emission,
            CapturedSectionGeometry.BlockFacts block) {
        return CapturedSectionGeometry.Surface.uniform(
                0xff80_a0c0,
                layer,
                false,
                false,
                animated,
                false,
                false,
                true,
                rasterOverlay,
                emission,
                sprite,
                block);
    }

    private static void assertUnitUv(SurfaceDefinition.UvMapping uv) {
        assertEquals(0.0F, uv.u0());
        assertEquals(0.0F, uv.v0());
        assertEquals(1.0F, uv.u1());
        assertEquals(0.0F, uv.v1());
        assertEquals(1.0F, uv.u2());
        assertEquals(1.0F, uv.v2());
        assertEquals(0.0F, uv.u3());
        assertEquals(1.0F, uv.v3());
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

    private record QuadSpec(
            float plane,
            float normalX,
            float normalY,
            CapturedSectionGeometry.Surface surface) {
    }
}
