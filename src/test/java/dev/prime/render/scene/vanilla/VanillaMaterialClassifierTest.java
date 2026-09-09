// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

package dev.prime.render.scene.vanilla;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.prime.render.material.BuiltinMaterialClass;
import dev.prime.render.scene.SpriteId;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

final class VanillaMaterialClassifierTest {
    @BeforeAll
    static void bootstrapRegistries() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void classifiesOnlyHomogeneousVanillaSurfaces() {
        assertClass(
                BuiltinMaterialClass.WOOD,
                Blocks.OAK_STAIRS,
                "minecraft",
                "block/oak_planks");
        assertClass(
                BuiltinMaterialClass.DEFAULT,
                Blocks.CRAFTING_TABLE,
                "minecraft",
                "block/oak_planks");
        assertClass(
                BuiltinMaterialClass.ROUGH_STONE,
                Blocks.STONE_BRICKS,
                "minecraft",
                "block/stone_bricks");
        assertClass(
                BuiltinMaterialClass.POLISHED_STONE,
                Blocks.POLISHED_GRANITE,
                "minecraft",
                "block/polished_granite");
        assertClass(
                BuiltinMaterialClass.DEFAULT,
                Blocks.IRON_ORE,
                "minecraft",
                "block/iron_ore");
        assertClass(
                BuiltinMaterialClass.DEFAULT,
                Blocks.STONE,
                "example",
                "block/stone");
    }

    @Test
    void metalPresetsUseExactBlockAllowLists() {
        assertClass(
                BuiltinMaterialClass.IRON,
                Blocks.IRON_BLOCK,
                "minecraft",
                "block/iron_block");
        assertClass(
                BuiltinMaterialClass.GOLD,
                Blocks.GOLD_BLOCK,
                "minecraft",
                "block/gold_block");
        assertClass(
                BuiltinMaterialClass.COPPER,
                Blocks.CUT_COPPER.weathering().unaffected(),
                "minecraft",
                "block/cut_copper");
        assertClass(
                BuiltinMaterialClass.AGED_COPPER,
                Blocks.COPPER_BLOCK.weathering().oxidized(),
                "minecraft",
                "block/oxidized_copper");
        assertClass(
                BuiltinMaterialClass.DEFAULT,
                Blocks.COPPER_BULB.weathering().unaffected(),
                "minecraft",
                "block/copper_bulb");
    }

    private static void assertClass(
            BuiltinMaterialClass expected,
            net.minecraft.world.level.block.Block block,
            String namespace,
            String path) {
        assertEquals(
                expected,
                VanillaMaterialClassifier.classify(
                        block.defaultBlockState(), new SpriteId(namespace, path)));
    }
}
