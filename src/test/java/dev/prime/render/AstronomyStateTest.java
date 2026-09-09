// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

package dev.prime.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;

final class AstronomyStateTest {
    private static final float EPSILON = 1.0e-6F;

    @Test
    void defaultEquinoxNoonIsSixtyDegreesHighAndDueSouth() {
        SunDirection sun = state(0.0F, 30, 0).sunDirection();
        assertDirection(
                sun,
                0.0F,
                (float) Math.cos(Math.toRadians(30.0)),
                (float) Math.sin(Math.toRadians(30.0)));
    }

    @Test
    void equinoxRisesDueEastAndSetsDueWest() {
        assertDirection(
                state((float) (-Math.PI * 0.5), 30, 0).sunDirection(),
                1.0F,
                0.0F,
                0.0F);
        assertDirection(
                state((float) (Math.PI * 0.5), 30, 0).sunDirection(),
                -1.0F,
                0.0F,
                0.0F);
    }

    @Test
    void solsticeNoonAltitudesFollowAxialTilt() {
        SunDirection june = state(0.0F, 30, 90).sunDirection();
        SunDirection december = state(0.0F, 30, 270).sunDirection();
        assertAltitude(june, 83.43928);
        assertAltitude(december, 36.56072);
        assertEquals(0.0F, june.x(), EPSILON);
        assertEquals(0.0F, december.x(), EPSILON);
        assertEquals(1.0F, Math.signum(june.z()));
        assertEquals(1.0F, Math.signum(december.z()));
    }

    @Test
    void hemispheresMirrorAndCardinalObserversRemainWellDefined() {
        for (int solarLongitude : new int[] {0, 90, 180, 270}) {
            SunDirection north =
                    state(0.83F, 47, solarLongitude).sunDirection();
            SunDirection south = state(
                    0.83F,
                    -47,
                    (solarLongitude + 180) % 360)
                    .sunDirection();
            assertEquals(north.x(), south.x(), EPSILON);
            assertEquals(north.y(), south.y(), EPSILON);
            assertEquals(north.z(), -south.z(), EPSILON);
        }

        for (int latitude : new int[] {-90, 0, 90}) {
            for (int solarLongitude : new int[] {0, 90, 180, 270}) {
                for (float hourAngle : new float[] {
                    0.0F,
                    (float) (-Math.PI * 0.5),
                    (float) (Math.PI * 0.5),
                    (float) Math.PI
                }) {
                    assertUnit(state(
                            hourAngle,
                            latitude,
                            solarLongitude)
                            .sunDirection());
                }
            }
        }
    }

    @Test
    void everySupportedObserverSeasonAndHourProducesAUnitDirection() {
        SplittableRandom random = new SplittableRandom(0x5A17_D1AEC710L);
        for (int index = 0; index < 65_536; index++) {
            int latitude = random.nextInt(-90, 91);
            int solarLongitude = random.nextInt(360);
            float hourAngle =
                    random.nextFloat() * (float) (Math.PI * 2.0);
            SunDirection direction =
                    state(hourAngle, latitude, solarLongitude).sunDirection();
            assertUnit(direction);
        }
    }

    @Test
    void settingsAndSemanticDirectionRejectInvalidStates() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new AstronomySettings(-91, 0));
        assertThrows(
                IllegalArgumentException.class,
                () -> new AstronomySettings(91, 0));
        assertThrows(
                IllegalArgumentException.class,
                () -> new AstronomySettings(30, -1));
        assertThrows(
                IllegalArgumentException.class,
                () -> new AstronomySettings(30, 360));
        assertThrows(
                IllegalArgumentException.class,
                () -> AstronomyState.atSolarHourAngle(
                        Float.NaN, AstronomySettings.defaults()));
        assertThrows(
                IllegalArgumentException.class,
                () -> new AstronomyState(
                        AstronomySettings.defaults(),
                        new SunDirection(0.0F, 1.0F, 0.0F)));
        assertThrows(
                IllegalArgumentException.class,
                () -> new SunDirection(0.0F, 0.0F, 0.0F));
        assertThrows(
                IllegalArgumentException.class,
                () -> new SunDirection(0.0F, 2.0F, 0.0F));
    }

    private static AstronomyState state(
            float hourAngle,
            int latitude,
            int solarLongitude) {
        return AstronomyState.atSolarHourAngle(
                hourAngle,
                new AstronomySettings(latitude, solarLongitude));
    }

    private static void assertAltitude(
            SunDirection direction,
            double expectedDegrees) {
        assertEquals(
                expectedDegrees,
                Math.toDegrees(Math.asin(direction.y())),
                3.0e-5);
    }

    private static void assertDirection(
            SunDirection actual,
            float expectedX,
            float expectedY,
            float expectedZ) {
        assertEquals(expectedX, actual.x(), EPSILON);
        assertEquals(expectedY, actual.y(), EPSILON);
        assertEquals(expectedZ, actual.z(), EPSILON);
    }

    private static void assertUnit(SunDirection direction) {
        float lengthSquared = direction.x() * direction.x()
                + direction.y() * direction.y()
                + direction.z() * direction.z();
        assertEquals(1.0F, lengthSquared, 2.0e-7F);
    }
}
