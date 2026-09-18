// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.
package dev.prime.render.vulkan;

import java.util.ArrayList;
import java.util.List;

/** Immutable scheduling for Sky Tracer's balanced, fixed-medium successive transport. */
public final class AtmospherePrecomputation {
    public static final int HEIGHTS = 40;
    public static final int SUNS = 160;
    public static final int PHASES = 20;
    public static final int CONES = 12;
    public static final int LOW_HEIGHTS = 20;
    public static final int BATCH_HEIGHTS = 4;
    public static final int DIRECTIONS = 1536;
    public static final int ITERATIONS = 8;

    public enum Stage {
        TRANSMITTANCE("transmittance"), DIRECTIONS("directions"), INCIDENT("incident"),
        MOMENTS("moments"), MULTI_SCATTERING("multi_scattering"), GROUND("ground");

        private final String artifact;

        Stage(String suffix) {
            this.artifact = "atmosphere_" + suffix;
        }

        public String artifact() { return this.artifact; }
    }

    /** Bank selects the complete previous field; producers write the opposite field. */
    public record Dispatch(Stage stage, int bank, int x, int y, int firstHeight, int heightCount, int iteration) {}

    private AtmospherePrecomputation() {}

    public static List<Dispatch> plan() {
        ArrayList<Dispatch> result = new ArrayList<>();
        result.add(new Dispatch(Stage.TRANSMITTANCE, 0, 64, 16, 0, 0, 0));
        // Seed direct ground illumination into field zero before any incident ray is traced.
        result.add(new Dispatch(Stage.GROUND, 1, (SUNS + 63) / 64, 1, 0, 0, 0));
        for (int iteration = 1; iteration <= ITERATIONS; iteration++) {
            int bank = (iteration - 1) & 1;
            for (int first = 0; first < HEIGHTS; first += BATCH_HEIGHTS) {
                int count = Math.min(BATCH_HEIGHTS, HEIGHTS - first);
                result.add(new Dispatch(Stage.DIRECTIONS, bank, DIRECTIONS / 64, count * SUNS, first, count, iteration));
                result.add(new Dispatch(Stage.INCIDENT, bank, DIRECTIONS / 64, count * SUNS, first, count, iteration));
                result.add(new Dispatch(Stage.MOMENTS, bank, (count * SUNS + 63) / 64, 1, first, count, iteration));
                int low = Math.max(0, Math.min(count, LOW_HEIGHTS - first));
                if (low > 0) {
                    result.add(new Dispatch(Stage.MULTI_SCATTERING, bank, SUNS * PHASES / 8,
                            (low * CONES + 7) / 8, first, count, iteration));
                }
                if (first == 0) {
                    result.add(new Dispatch(Stage.GROUND, bank, (SUNS + 63) / 64, 1, first, count, iteration));
                }
            }
        }
        return List.copyOf(result);
    }

    public static int finalBank() { return ITERATIONS & 1; }
}
