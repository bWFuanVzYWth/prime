// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

package dev.prime.render.terrain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

final class TerrainWorkerSettingsTest {
    @Test
    void percentageUsesTheMinecraftMaximumAndRoundsDown() {
        assertEquals(15, TerrainWorkerSettings.workerLimit(31, 50));
        assertEquals(16, TerrainWorkerSettings.workerLimit(32, 50));
        assertEquals(32, TerrainWorkerSettings.workerLimit(32, 100));
    }

    @Test
    void smallSharesAlwaysLeaveOnePrimeWorker() {
        assertEquals(1, TerrainWorkerSettings.workerLimit(1, 50));
        assertEquals(1, TerrainWorkerSettings.workerLimit(31, 1));
    }

    @Test
    void rejectsInvalidThreadAndPercentageInputs() {
        assertThrows(
                IllegalArgumentException.class,
                () -> TerrainWorkerSettings.workerLimit(0, 50));
        assertThrows(
                IllegalArgumentException.class,
                () -> TerrainWorkerSettings.workerLimit(16, 0));
        assertThrows(
                IllegalArgumentException.class,
                () -> TerrainWorkerSettings.workerLimit(16, 101));
    }

    @Test
    void backgroundStreamingReservesOneInteractiveAdmission() {
        assertEquals(14, TerrainWorkerSettings.admissionLimit(15, false));
        assertEquals(15, TerrainWorkerSettings.admissionLimit(15, true));
        assertEquals(1, TerrainWorkerSettings.admissionLimit(1, false));
        assertEquals(1, TerrainWorkerSettings.admissionLimit(1, true));
        assertThrows(
                IllegalArgumentException.class,
                () -> TerrainWorkerSettings.admissionLimit(0, false));
    }
}
