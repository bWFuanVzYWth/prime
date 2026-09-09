// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

package dev.prime.render.scene.vanilla;

import dev.prime.render.material.BuiltinMaterialClass;
import dev.prime.render.scene.SpriteId;
import java.util.HashSet;
import java.util.Set;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/** Conservative, surface-local material defaults for homogeneous vanilla blocks. */
public final class VanillaMaterialClassifier {
    private static final Set<String> IRON = Set.of(
            "iron_block",
            "iron_bars",
            "iron_door",
            "iron_trapdoor",
            "chain");
    private static final Set<String> GOLD = Set.of("gold_block");
    private static final Set<String> COPPER = copperBlocks();

    private VanillaMaterialClassifier() {
    }

    public static BuiltinMaterialClass classify(BlockState state, SpriteId sprite) {
        if (!sprite.namespace().equals("minecraft")
                || !sprite.path().startsWith("block/")) {
            return BuiltinMaterialClass.DEFAULT;
        }
        String path = sprite.path();
        var blockKey = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        if (!blockKey.getNamespace().equals("minecraft")) {
            return BuiltinMaterialClass.DEFAULT;
        }
        String blockId = blockKey.getPath();
        if (mixedBlock(blockId) || mixedSprite(path)) {
            return BuiltinMaterialClass.DEFAULT;
        }
        if (IRON.contains(blockId)) {
            return BuiltinMaterialClass.IRON;
        }
        if (GOLD.contains(blockId)) {
            return BuiltinMaterialClass.GOLD;
        }
        BuiltinMaterialClass copper = copper(blockId);
        if (copper != BuiltinMaterialClass.DEFAULT) {
            return copper;
        }
        if (state.is(BlockTags.WOOL) || state.is(BlockTags.WOOL_CARPETS)) {
            return BuiltinMaterialClass.FIBER;
        }
        if (state.is(BlockTags.LOGS)
                || state.is(BlockTags.PLANKS)
                || state.is(BlockTags.BAMBOO_BLOCKS)
                || wood(path)) {
            return BuiltinMaterialClass.WOOD;
        }
        if (state.is(BlockTags.SAND)
                || state.is(BlockTags.DIRT)
                || state.is(BlockTags.MUD)
                || state.is(BlockTags.CONCRETE_POWDERS)
                || state.is(Blocks.GRAVEL)
                || state.is(Blocks.CLAY)) {
            return BuiltinMaterialClass.EARTH;
        }
        if (state.is(BlockTags.GLAZED_TERRACOTTA)) {
            return BuiltinMaterialClass.GLAZED_CERAMIC;
        }
        if (state.is(BlockTags.TERRACOTTA) || state.is(BlockTags.CONCRETE)) {
            return BuiltinMaterialClass.CERAMIC;
        }
        if (state.is(BlockTags.LEAVES) || state.is(BlockTags.MOSS_BLOCKS)) {
            return BuiltinMaterialClass.ORGANIC;
        }
        if (polishedStone(path)) {
            return BuiltinMaterialClass.POLISHED_STONE;
        }
        if (roughStone(state, path)) {
            return BuiltinMaterialClass.ROUGH_STONE;
        }
        return BuiltinMaterialClass.DEFAULT;
    }

    private static BuiltinMaterialClass copper(String blockId) {
        String bare = blockId.startsWith("waxed_")
                ? blockId.substring("waxed_".length())
                : blockId;
        if (!COPPER.contains(blockId)) {
            return BuiltinMaterialClass.DEFAULT;
        }
        return bare.startsWith("exposed_")
                        || bare.startsWith("weathered_")
                        || bare.startsWith("oxidized_")
                ? BuiltinMaterialClass.AGED_COPPER
                : BuiltinMaterialClass.COPPER;
    }

    private static Set<String> copperBlocks() {
        HashSet<String> result = new HashSet<>();
        for (String oxidation : new String[] {"", "exposed_", "weathered_", "oxidized_"}) {
            String block = oxidation.isEmpty() ? "copper_block" : oxidation + "copper";
            addCopper(result, block);
            for (String form : new String[] {
                "cut_copper",
                "cut_copper_stairs",
                "cut_copper_slab",
                "chiseled_copper",
                "copper_grate",
                "copper_door",
                "copper_trapdoor"
            }) {
                addCopper(result, oxidation + form);
            }
        }
        return Set.copyOf(result);
    }

    private static void addCopper(Set<String> destination, String id) {
        destination.add(id);
        destination.add("waxed_" + id);
    }

    private static boolean polishedStone(String path) {
        return path.contains("polished_")
                || path.contains("smooth_stone")
                || path.contains("quartz_block")
                || path.contains("quartz_bricks");
    }

    private static boolean wood(String path) {
        return path.endsWith("_planks")
                || path.contains("_log")
                || path.contains("_wood")
                || path.contains("_stem")
                || path.contains("_hyphae");
    }

    private static boolean roughStone(BlockState state, String path) {
        return state.is(Blocks.STONE)
                || state.is(Blocks.COBBLESTONE)
                || state.is(Blocks.DEEPSLATE)
                || state.is(Blocks.COBBLED_DEEPSLATE)
                || state.is(Blocks.ANDESITE)
                || state.is(Blocks.DIORITE)
                || state.is(Blocks.GRANITE)
                || path.contains("stone_bricks")
                || path.contains("deepslate_bricks")
                || path.contains("deepslate_tiles")
                || path.contains("blackstone")
                || path.contains("tuff_bricks");
    }

    private static boolean mixedBlock(String id) {
        return id.contains("_ore")
                || id.contains("rail")
                || id.contains("redstone")
                || id.contains("piston")
                || id.contains("furnace")
                || id.contains("smoker")
                || id.contains("blast_furnace")
                || id.contains("crafting_table")
                || id.contains("cartography_table")
                || id.contains("smithing_table")
                || id.contains("fletching_table")
                || id.contains("loom")
                || id.contains("bookshelf")
                || id.contains("lectern")
                || id.contains("barrel")
                || id.contains("chest")
                || id.contains("shulker_box")
                || id.contains("hopper")
                || id.contains("dispenser")
                || id.contains("dropper")
                || id.contains("observer")
                || id.contains("crafter")
                || id.contains("lamp")
                || id.contains("lantern")
                || id.contains("torch")
                || id.contains("bulb");
    }

    private static boolean mixedSprite(String path) {
        return path.contains("_ore")
                || path.contains("rail")
                || path.contains("redstone")
                || path.contains("piston")
                || path.contains("furnace")
                || path.contains("crafting_table")
                || path.contains("bookshelf")
                || path.contains("lectern")
                || path.contains("barrel")
                || path.contains("chest")
                || path.contains("lamp")
                || path.contains("lantern")
                || path.contains("torch");
    }
}
