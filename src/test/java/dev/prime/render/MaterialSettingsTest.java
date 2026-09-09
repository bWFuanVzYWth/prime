// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

package dev.prime.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

final class MaterialSettingsTest {
    @Test
    void unauthoredMaterialsUseTheCalibratedDefaultRoughness() {
        assertEquals(90, MaterialSettings.DEFAULT_ROUGHNESS_STEPS);
        assertEquals(0.90F,
                MaterialSettings.linearRoughness(MaterialSettings.DEFAULT_ROUGHNESS_STEPS),
                1.0e-7F);
    }

    @Test
    void snapshotDerivesLinearRoughnessFromItsCanonicalSteps() {
        MaterialSettings.Snapshot snapshot =
                new MaterialSettings.Snapshot(37, true);

        assertEquals(0.37F, snapshot.linearRoughness());
        assertTrue(snapshot.seamlessGlass());
        assertTrue(snapshot.airGap());
        assertTrue(snapshot.vanillaPbrPresets());
        assertTrue(snapshot.baseColorCompensation());
        assertThrows(
                IllegalArgumentException.class,
                () -> new MaterialSettings.Snapshot(101));
    }
}
