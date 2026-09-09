// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

package dev.prime.render.scene.vanilla;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import net.minecraft.SharedConstants;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.block.dispatch.SingleVariant;
import net.minecraft.client.renderer.block.dispatch.WeightedVariants;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.client.resources.model.sprite.Material;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.util.RandomSource;
import net.minecraft.util.random.WeightedList;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

final class VanillaSectionCaptureTest {
    @BeforeAll
    static void bootstrapRegistries() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void atlasEndpointRoundingIsLoweredToNormalizedLocalUv() {
        float lower = 1537.0F / 2048.0F;
        float upper = 1553.0F / 2048.0F;

        assertEquals(0.0F, VanillaSectionCapture.localCoordinate(
                Math.nextDown(lower), lower, upper));
        assertEquals(1.0F, VanillaSectionCapture.localCoordinate(
                Math.nextUp(upper), lower, upper));
        assertEquals(0.25F, VanillaSectionCapture.localCoordinate(
                lower + 0.25F * (upper - lower), lower, upper));
        assertTrue(VanillaSectionCapture.localCoordinate(
                upper + 0.25F * (upper - lower), lower, upper) > 1.0F);
    }

    @Test
    void indigoAndDirectCaptureAdmitSelectedVanillaVariantsButNotCustomModels() {
        BlockStateModel deterministic = new SingleVariant(new EmptyPart());
        BlockStateModel randomized =
                new WeightedVariants(WeightedList.of(deterministic));
        BlockStateModel custom = new EmptyModel();

        assertTrue(VanillaSectionCapture.mergeableModel(deterministic));
        assertTrue(VanillaSectionCapture.mergeableModel(randomized));
        assertFalse(VanillaSectionCapture.mergeableModel(custom));
    }

    @Test
    void onlyVanillaCoplanarOverlayRolesAreCapturedForLaterComposition() {
        assertTrue(VanillaSectionCapture.isRasterOverlay(
                true, false, 0, 0.0F));
        assertFalse(VanillaSectionCapture.isRasterOverlay(
                true, false, -1, 0.0F));
        assertFalse(VanillaSectionCapture.isRasterOverlay(
                true, false, 0, 1.0F));
        assertTrue(VanillaSectionCapture.isRasterOverlay(
                false, true, -1, 1.0F));
    }

    @Test
    void onlyOpaqueFullBlocksOccludeFluidContacts() {
        assertTrue(VanillaSectionCapture.occludesFluid(Blocks.STONE.defaultBlockState()));
        assertFalse(VanillaSectionCapture.occludesFluid(Blocks.GLASS.defaultBlockState()));
    }

    private static final class EmptyModel implements BlockStateModel {
        @Override
        public void collectParts(RandomSource random, List<BlockStateModelPart> output) {
        }

        @Override
        public Material.Baked particleMaterial() {
            return null;
        }

        @Override
        public int materialFlags() {
            return 0;
        }
    }

    private static final class EmptyPart implements BlockStateModelPart {
        @Override
        public List<BakedQuad> getQuads(Direction direction) {
            return List.of();
        }

        @Override
        public boolean useAmbientOcclusion() {
            return false;
        }

        @Override
        public Material.Baked particleMaterial() {
            return null;
        }

        @Override
        public int materialFlags() {
            return 0;
        }
    }
}
