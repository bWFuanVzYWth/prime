package dev.primept.capture;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
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
    private static final ValueLayout.OfInt I =
            ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
    private static final ValueLayout.OfLong L =
            ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
    private static final Field SEED = field(BiomeManager.class, "biomeZoomSeed");
    private static final Field CLIMATE = field(Biome.class, "climateSettings");
    private static final Field TEMPERATURE = field(CLIMATE.getType(), "temperature");
    private static final Field DOWNFALL = field(CLIMATE.getType(), "downfall");
    private static final Field[] MAPS = {field(GrassColor.class, "pixels"),
                                         field(FoliageColor.class, "pixels"),
                                         field(DryFoliageColor.class, "pixels")};

    static void definitions(SourcePages out, BiomeManager manager) {
        out.l((long)get(SEED, manager));
        noise(out);
        for (var field : MAPS) {
            var pixels = (int[])get(field, null);
            int count = Math.min(65536, pixels.length);
            out.i(count).ints(pixels, count);
        }
    }
    static void respond(MemorySegment request, SourcePages out, ClientLevel world) {
        int cells = 0;
        for (long at = 32; at < request.byteSize(); at += 20)
            cells = Math.addExact(cells, Long.bitCount(request.get(L, at + 12)));
        var ids = new int[cells];
        var palette = new IdentityHashMap<Biome, Integer>();
        var biomes = new ArrayList<Biome>();
        var manager = world.getBiomeManager();
        int next = 0;
        for (long at = 32; at < request.byteSize(); at += 20) {
            int x = request.get(I, at) * 4, y = request.get(I, at + 4) * 4,
                z = request.get(I, at + 8) * 4;
            long mask = request.get(L, at + 12);
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
        out.i(biomes.size());
        for (var biome : biomes)
            fields(out, biome);
        out.ints(ids, ids.length);
    }
    static void fields(SourcePages out, Biome biome) {
        var climate = get(CLIMATE, biome);
        var effects = biome.getSpecialEffects();
        var grass = effects.grassColorOverride();
        var foliage = effects.foliageColorOverride();
        var dry = effects.dryFoliageColorOverride();
        out.f((float)get(TEMPERATURE, climate))
                .f((float)get(DOWNFALL, climate))
                .i(effects.waterColor())
                .i(grass.orElse(0))
                .i(foliage.orElse(0))
                .i(dry.orElse(0))
                .i((grass.isPresent() ? 1 : 0) | (foliage.isPresent() ? 2 : 0) |
                   (dry.isPresent() ? 4 : 0))
                .i(switch (effects.grassColorModifier()) {
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
    private static void noise(SourcePages out) {
        var noise = Biome.BIOME_INFO_NOISE;
        var levels = (SimplexNoise[])get(LEVELS, noise);
        if (levels.length != 1 || levels[0] == null || levels[0].getClass() != SimplexNoise.class)
            throw new IllegalStateException("Unsupported biome noise source");
        var permutation = (int[])get(PERMUTATION, levels[0]);
        out.ints(permutation, 256)
                .d(0)
                .d(0)
                .d((double)get(INPUT_SCALE, noise))
                .d((double)get(VALUE_SCALE, noise));
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
