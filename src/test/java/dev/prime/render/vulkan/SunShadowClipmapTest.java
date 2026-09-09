// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

package dev.prime.render.vulkan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.prime.render.AstronomySettings;
import dev.prime.render.AstronomyState;
import dev.prime.render.SunDirection;
import dev.prime.render.shader.ShaderAbi;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Set;
import org.junit.jupiter.api.Test;

final class SunShadowClipmapTest {
    @Test
    void queryConstantsPublishTheExactActiveDirectionBankAndBasis() {
        SunDirection direction = new SunDirection(0.6F, 0.8F, 0.0F);
        ByteBuffer constants = ByteBuffer.allocateDirect(
                        ShaderAbi.SUN_SHADOW_QUERY_CONSTANT_SIZE)
                .order(ByteOrder.nativeOrder());

        SunShadowClipmap.writeQueryConstants(constants, direction, 1, true);

        assertEquals(0.6F, constants.getFloat(
                ShaderAbi.SUN_SHADOW_QUERY_DIRECTION_TO_SUN_OFFSET));
        assertEquals(0.8F, constants.getFloat(
                ShaderAbi.SUN_SHADOW_QUERY_DIRECTION_TO_SUN_OFFSET
                        + Float.BYTES));
        assertEquals(0.0F, constants.getFloat(
                ShaderAbi.SUN_SHADOW_QUERY_DIRECTION_TO_SUN_OFFSET
                        + 2 * Float.BYTES));
        assertEquals(1, constants.getInt(ShaderAbi.SUN_SHADOW_QUERY_BANK_OFFSET));
        assertEquals(1, constants.getInt(ShaderAbi.SUN_SHADOW_QUERY_VALID_OFFSET));
        assertEquals(0, constants.getInt(ShaderAbi.SUN_SHADOW_QUERY_RESERVED_OFFSET));

        int uOffset = ShaderAbi.SUN_SHADOW_QUERY_BASIS_U_OFFSET;
        int vOffset = ShaderAbi.SUN_SHADOW_QUERY_BASIS_V_OFFSET;
        float ux = constants.getFloat(uOffset);
        float uy = constants.getFloat(uOffset + Float.BYTES);
        float uz = constants.getFloat(uOffset + 2 * Float.BYTES);
        float vx = constants.getFloat(vOffset);
        float vy = constants.getFloat(vOffset + Float.BYTES);
        float vz = constants.getFloat(vOffset + 2 * Float.BYTES);
        assertEquals(1.0F, ux * ux + uy * uy + uz * uz, 1.0e-6F);
        assertEquals(1.0F, vx * vx + vy * vy + vz * vz, 1.0e-6F);
        assertEquals(0.0F, ux * vx + uy * vy + uz * vz, 1.0e-6F);
        assertEquals(0.0F, ux * direction.x()
                + uy * direction.y()
                + uz * direction.z(), 1.0e-6F);
        assertEquals(0.0F, vx * direction.x()
                + vy * direction.y()
                + vz * direction.z(), 1.0e-6F);
    }

    @Test
    void twoToOneCascadesCoverOneKilometreWithAFarGuard() {
        assertEquals(0.5F, SunShadowClipmap.texelSize(0));
        assertEquals(1.0F, SunShadowClipmap.texelSize(1));
        assertEquals(2.0F, SunShadowClipmap.texelSize(2));
        assertEquals(4.0F, SunShadowClipmap.texelSize(3));
        assertEquals(8.0F, SunShadowClipmap.texelSize(4));
        assertEquals(128.0F, SunShadowClipmap.cascadeRadius(0));
        assertEquals(1_024.0F, SunShadowClipmap.cascadeRadius(3));
        assertEquals(2_048.0F, SunShadowClipmap.cascadeRadius(4));
        assertEquals(3, SunShadowClipmap.cascadeForProjectedDistance(1_000.0F));
        assertEquals(4, SunShadowClipmap.cascadeForProjectedDistance(1_500.0F));
        assertEquals(-1, SunShadowClipmap.cascadeForProjectedDistance(2_049.0F));
    }

    @Test
    void unknownDepthCanOnlyRemoveSunlight() {
        assertFalse(SunShadowClipmap.conservativeVisibility(
                100.0F, SunShadowClipmap.UNKNOWN_DEPTH));
        assertTrue(SunShadowClipmap.conservativeVisibility(
                100.0F, SunShadowClipmap.NO_HIT_DEPTH));
        assertFalse(SunShadowClipmap.conservativeVisibility(4.0F, 5.0F));
        assertTrue(SunShadowClipmap.conservativeVisibility(5.0F, 5.0F));
    }

    @Test
    void initialBuildCoversAllFourTilesTouchingTheCamera() {
        assertEquals(
                Set.of(5, 6, 9, 10),
                Set.of(
                        SunShadowClipmap.primaryTileForBuild(0),
                        SunShadowClipmap.primaryTileForBuild(1),
                        SunShadowClipmap.primaryTileForBuild(2),
                        SunShadowClipmap.primaryTileForBuild(3)));
    }

    @Test
    void streamedDirtyTilesRemainRepairableBeforeTheBankIsPublished() {
        assertFalse(SunShadowClipmap.readyForDirtyRepair(
                true, SunShadowClipmap.TILE_COUNT - 1));
        assertTrue(SunShadowClipmap.readyForDirtyRepair(
                true, SunShadowClipmap.TILE_COUNT));
        assertFalse(SunShadowClipmap.readyForDirtyRepair(
                false, SunShadowClipmap.TILE_COUNT));
    }

    @Test
    void streamedChangesNeverInvalidateThePublishedBank() {
        assertFalse(SunShadowClipmap.deferInvalidation(0, 0));
        assertFalse(SunShadowClipmap.deferInvalidation(1, 1));
        assertTrue(SunShadowClipmap.deferInvalidation(1, 0));
    }

    @Test
    void lightSpaceBasisRemainsStableThroughTheSolarZenith() {
        AstronomySettings equatorAtEquinox =
                new AstronomySettings(0, 0);
        SunDirection before = AstronomyState.atSolarHourAngle(
                -0.01F, equatorAtEquinox).sunDirection();
        SunDirection after = AstronomyState.atSolarHourAngle(
                0.01F, equatorAtEquinox).sunDirection();

        assertTrue(
                SunShadowClipmap.basisDirectionCosine(before, after) > 0.999F);
    }

    @Test
    void lightSpaceBasisDoesNotFlipWhereTheSunCrossesWorldX() {
        AstronomySettings equatorAtEquinox =
                new AstronomySettings(0, 0);
        SunDirection before = AstronomyState.atSolarHourAngle(
                (float) -Math.asin(0.9985),
                equatorAtEquinox).sunDirection();
        SunDirection after = AstronomyState.atSolarHourAngle(
                (float) -Math.asin(0.9995),
                equatorAtEquinox).sunDirection();

        assertTrue(
                SunShadowClipmap.basisDirectionCosine(before, after) > 0.999F);
    }
}
