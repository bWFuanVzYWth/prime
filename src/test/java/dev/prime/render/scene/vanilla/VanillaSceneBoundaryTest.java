// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

package dev.prime.render.scene.vanilla;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class VanillaSceneBoundaryTest {
    @Test
    void localPlayerIsWorldGeometryOnlyOutsideFirstPerson() {
        assertFalse(VanillaSceneBoundary.includes(
                VanillaSceneBoundary.Element.ENTITY, true, true));
        assertTrue(VanillaSceneBoundary.includes(
                VanillaSceneBoundary.Element.ENTITY, true, false));
        assertTrue(VanillaSceneBoundary.includes(
                VanillaSceneBoundary.Element.ENTITY, false, true));
    }

    @Test
    void worldEffectsRemainInSceneButCameraOverlaysDoNot() {
        assertTrue(VanillaSceneBoundary.includes(
                VanillaSceneBoundary.Element.BLOCK_ENTITY, false, true));
        assertTrue(VanillaSceneBoundary.includes(
                VanillaSceneBoundary.Element.PARTICLE, false, true));
        assertTrue(VanillaSceneBoundary.includes(
                VanillaSceneBoundary.Element.FEATURE, false, true));
        assertTrue(VanillaSceneBoundary.includes(
                VanillaSceneBoundary.Element.WEATHER, false, true));
        assertFalse(VanillaSceneBoundary.includes(
                VanillaSceneBoundary.Element.FIRST_PERSON_HAND, false, true));
        assertFalse(VanillaSceneBoundary.includes(
                VanillaSceneBoundary.Element.FIRST_PERSON_ITEM, false, true));
        assertFalse(VanillaSceneBoundary.includes(
                VanillaSceneBoundary.Element.SCREEN_OVERLAY, false, true));
    }

    @Test
    void primeOwnedTerrainDoesNotRequireVanillaSectionCompilation() {
        assertTrue(VanillaSceneBoundary.includesEntitySection(true, false));
        assertTrue(VanillaSceneBoundary.includesEntitySection(false, true));
        assertFalse(VanillaSceneBoundary.includesEntitySection(false, false));
    }
}
