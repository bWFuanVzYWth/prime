package dev.primept.capture;

import java.lang.foreign.MemorySegment;
import dev.primept.abi.PrimeAbi.*;
import java.lang.reflect.Field;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.GrassColor;
import net.minecraft.world.level.FoliageColor;
import net.minecraft.world.level.DryFoliageColor;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.levelgen.synth.SimplexNoise;

/** Actual source fields only. Zoom, color lookup/modifiers, blending and caches belong to Rust. */
final class SectionBiomes {
    private static final Field SEED = field(BiomeManager.class, "biomeZoomSeed");
    private static final Field CLIMATE = field(Biome.class, "climateSettings");
    private static final Field TEMPERATURE = field(CLIMATE.getType(), "temperature");
    private static final Field DOWNFALL = field(CLIMATE.getType(), "downfall");
    private static final Field[] MAPS = {field(GrassColor.class, "pixels"),
                                         field(FoliageColor.class, "pixels"),
                                         field(DryFoliageColor.class, "pixels")};

    static void definitions(McSourceBatch out, BiomeManager manager) {
        var value = out.definitions.add();
        PrimeMcBiomeDefinitions.seed(value, (long)get(SEED, manager));
        noise(value);
        for (int i = 0; i < MAPS.length; i++) {
            var pixels = (int[])get(MAPS[i], null);
            var range = out.colormaps.copy(
                    MemorySegment.ofArray(pixels).asSlice(0, Math.min(65536, pixels.length) * 4L));
            range.write(switch (i) {
                case 0 -> PrimeMcBiomeDefinitions.grass(value);
                case 1 -> PrimeMcBiomeDefinitions.foliage(value);
                default -> PrimeMcBiomeDefinitions.dry_foliage(value);
            });
        }
    }
    static void respond(MemorySegment request, McSourceBatch out, ClientLevel world) {
        long count = PrimeMcRequests.biome_count(request);
        var requests = PrimeMcRequests.biomes(request).reinterpret(
                Math.multiplyExact(count, PrimeMcBiomeRequest.SIZE));
        int cells = 0;
        for (long i = 0; i < count; i++)
            cells = Math.addExact(
                    cells, Long.bitCount(PrimeMcBiomeRequest.mask(requests.asSlice(
                                   i * PrimeMcBiomeRequest.SIZE, PrimeMcBiomeRequest.SIZE))));
        var ids = new int[cells];
        var palette = new IdentityHashMap<Biome, Integer>();
        var biomes = new ArrayList<Biome>();
        var manager = world.getBiomeManager();
        int next = 0;
        for (long i = 0; i < count; i++) {
            var item = requests.asSlice(i * PrimeMcBiomeRequest.SIZE, PrimeMcBiomeRequest.SIZE);
            int x = PrimeMcBiomeRequest.x(item) * 4, y = PrimeMcBiomeRequest.y(item) * 4,
                z = PrimeMcBiomeRequest.z(item) * 4;
            long mask = PrimeMcBiomeRequest.mask(item);
            while (mask != 0) {
                int local = Long.numberOfTrailingZeros(mask);
                mask &= mask - 1;
                var biome = manager.getNoiseBiomeAtQuart(x + (local & 3), y + (local >>> 4),
                                                         z + ((local >>> 2) & 3))
                                    .value();
                var id = palette.get(biome);
                if (id == null) {
                    id = biomes.size();
                    palette.put(biome, id);
                    biomes.add(biome);
                }
                ids[next++] = id;
            }
        }
        for (var biome : biomes)
            fields(out, biome);
        out.indices.ints(ids);
    }
    static void fields(McSourceBatch out, Biome biome) {
        var climate = get(CLIMATE, biome);
        var effects = biome.getSpecialEffects();
        var grass = effects.grassColorOverride();
        var foliage = effects.foliageColorOverride();
        var dry = effects.dryFoliageColorOverride();
        var value = out.biomes.add();
        PrimeMcBiome.temperature(value, (float)get(TEMPERATURE, climate));
        PrimeMcBiome.downfall(value, (float)get(DOWNFALL, climate));
        PrimeMcBiome.water(value, effects.waterColor());
        PrimeMcBiome.overrides(value, 0, grass.orElse(0));
        PrimeMcBiome.overrides(value, 1, foliage.orElse(0));
        PrimeMcBiome.overrides(value, 2, dry.orElse(0));
        PrimeMcBiome.flags(value, (grass.isPresent() ? 1 : 0) | (foliage.isPresent() ? 2 : 0) |
                                          (dry.isPresent() ? 4 : 0));
        PrimeMcBiome.modifier(value, switch (effects.grassColorModifier()) {
            case NONE -> 0;
            case DARK_FOREST -> 1;
            case SWAMP -> 2;
        });
    }
    private static final Class<?> NOISE =
            net.minecraft.world.level.levelgen.synth.PerlinSimplexNoise.class;
    private static final Field LEVELS = field(NOISE, "noiseLevels");
    private static final Field INPUT_SCALE = field(NOISE, "highestFreqInputFactor");
    private static final Field VALUE_SCALE = field(NOISE, "highestFreqValueFactor");
    private static final Field PERMUTATION = field(SimplexNoise.class, "p");
    private static void noise(MemorySegment out) {
        var noise = Biome.BIOME_INFO_NOISE;
        var levels = (SimplexNoise[])get(LEVELS, noise);
        if (levels.length != 1 || levels[0] == null || levels[0].getClass() != SimplexNoise.class)
            throw new IllegalStateException("Unsupported biome noise source");
        var permutation = (int[])get(PERMUTATION, levels[0]);
        for (int i = 0; i < 256; i++)
            PrimeMcBiomeDefinitions.permutation(out, i, permutation[i]);
        PrimeMcBiomeDefinitions.offset(out, 0, 0);
        PrimeMcBiomeDefinitions.offset(out, 1, 0);
        PrimeMcBiomeDefinitions.input_scale(out, (double)get(INPUT_SCALE, noise));
        PrimeMcBiomeDefinitions.value_scale(out, (double)get(VALUE_SCALE, noise));
    }
    static Field field(Class<?> type, String name) {
        try {
            var field = type.getDeclaredField(name);
            field.setAccessible(true);
            return field;
        } catch (ReflectiveOperationException failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }
    static Object get(Field field, Object owner) {
        try {
            return field.get(owner);
        } catch (IllegalAccessException failure) {
            throw new IllegalStateException("Minecraft source field is inaccessible", failure);
        }
    }
}
