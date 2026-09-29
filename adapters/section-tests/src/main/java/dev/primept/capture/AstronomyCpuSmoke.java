package dev.primept.capture;

import dev.primept.PrimeClient;
import dev.primept.render.OfflineMode;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.SkyRenderer;
import net.minecraft.client.renderer.extract.LevelExtractor;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.core.Holder;
import net.minecraft.world.attribute.EnvironmentAttribute;
import net.minecraft.world.attribute.EnvironmentAttributeProbe;
import net.minecraft.world.attribute.EnvironmentAttributeSystem;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeGenerationSettings;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.biome.BiomeSpecialEffects;
import net.minecraft.world.level.biome.MobSpawnSettings;
import net.minecraft.world.phys.Vec3;
import sun.misc.Unsafe;

/** The transformed source hook and real host attribute interpolation, without a raster sky or GPU. */
final class AstronomyCpuSmoke {
    static void run() throws Exception {
        var unsafe = (Unsafe)field(Unsafe.class, "theUnsafe").get(null);
        var extractor = (LevelExtractor)unsafe.allocateInstance(LevelExtractor.class);
        var renderer = (LevelRenderer)unsafe.allocateInstance(LevelRenderer.class);
        var camera = (Camera)unsafe.allocateInstance(Camera.class);
        var world = (ClientLevel)unsafe.allocateInstance(ClientLevel.class);
        var state = new LevelRenderState();
        var probe = new CountingProbe();
        field(LevelExtractor.class, "levelRenderState").set(extractor, state);
        field(Camera.class, "attributeProbe").set(camera, probe);
        var biome = Holder.direct(
                new Biome.BiomeBuilder()
                        .temperature(.8f)
                        .downfall(.4f)
                        .specialEffects(
                                new BiomeSpecialEffects.Builder().waterColor(0x3f76e4).build())
                        .mobSpawnSettings(MobSpawnSettings.EMPTY)
                        .generationSettings(BiomeGenerationSettings.EMPTY)
                        .build());
        field(Level.class, "biomeManager").set(world, new BiomeManager((x, y, z) -> biome, 0));
        float[] degrees = {60};
        var attributes = EnvironmentAttributeSystem.builder()
                                 .addTimeBasedLayer(EnvironmentAttributes.SUN_ANGLE,
                                                    (base, ticks) -> degrees[0])
                                 .build();
        field(ClientLevel.class, "environmentAttributes").set(world, attributes);
        Method source = Arrays.stream(LevelExtractor.class.getDeclaredMethods())
                                .filter(m -> m.getName().endsWith("primept$skySource"))
                                .findFirst()
                                .orElseThrow();
        source.setAccessible(true);
        var suspended = field(ExclusiveTerrainCapture.class, "vanillaSuspended");
        boolean previous = suspended.getBoolean(null);
        var client = field(PrimeClient.class, "INSTANCE").get(null);
        var offline = (OfflineMode)field(PrimeClient.class, "offline").get(client);
        check(!offline.active() && !offline.requested(), "Fixture needs an unfrozen session");
        try {
            suspended.setBoolean(null, true);
            check(renderer.skyRenderer() == null, "Cold Prime start has no raster sky owner");
            probe.tick(world, Vec3.ZERO);
            sample(source, extractor, renderer, camera, state, probe, .5f, 60);
            for (float next : new float[] {90, 180, 270, 359, 1, 123.25f}) {
                degrees[0] = next;
                attributes.invalidateTickCache();
                probe.tick(world, Vec3.ZERO);
                sample(source, extractor, renderer, camera, state, probe, 1, next);
            }
            // Actual angle interpolation must use the supplied partial tick, including wraparound.
            degrees[0] = 359;
            attributes.invalidateTickCache();
            probe.tick(world, Vec3.ZERO);
            sample(source, extractor, renderer, camera, state, probe, 1, 359);
            degrees[0] = 1;
            attributes.invalidateTickCache();
            probe.tick(world, Vec3.ZERO);
            sample(source, extractor, renderer, camera, state, probe, .25f, 359.5f);
            sample(source, extractor, renderer, camera, state, probe, .75f, 360.5f);
            check(renderer.skyRenderer() == null, "Source reads never allocate a raster sky owner");

            var warmSky = (SkyRenderer)unsafe.allocateInstance(SkyRenderer.class);
            field(LevelRenderer.class, "skyRenderer").set(renderer, warmSky);
            sample(source, extractor, renderer, camera, state, probe, 1, 1);
            suspended.setBoolean(null, false);
            float last = state.skyRenderState.sunAngle;
            int reads = probe.reads;
            check(source.invoke(extractor, renderer, null, null, 0f) == warmSky &&
                          state.skyRenderState.sunAngle == last && probe.reads == reads,
                  "Vanilla retains its sky owner and sole source evaluation");
            suspended.setBoolean(null, true);
            sample(source, extractor, renderer, camera, state, probe, .5f, 360);

            offline.request(true);
            offline.committed(true);
            last = state.skyRenderState.sunAngle;
            reads = probe.reads;
            // The real extraction entry must cancel before touching a world, camera or source hook.
            extractor.extract(null, null, 0);
            check(state.skyRenderState.sunAngle == last && probe.reads == reads,
                  "Frozen extraction neither resamples nor overwrites the live solar snapshot");
            offline.reset();
            degrees[0] = 45;
            attributes.invalidateTickCache();
            probe.tick(world, Vec3.ZERO);
            sample(source, extractor, renderer, camera, state, probe, 1, 45);
        } finally {
            suspended.setBoolean(null, previous);
            offline.reset();
        }
        System.out.println(
                "PRIME_ASTRONOMY_CPU_SMOKE_OK: cold null sky owner, changing/custom sun attributes, partial ticks and angle wrap, one read, warm sky/vanilla handoff, frozen extraction and resume");
    }

    private static void sample(Method source, LevelExtractor extractor, LevelRenderer renderer,
                               Camera camera, LevelRenderState state, CountingProbe probe,
                               float partial, float expectedDegrees) throws Exception {
        state.skyRenderState.sunAngle = Float.NaN;
        int reads = probe.reads;
        check(source.invoke(extractor, renderer, null, camera, partial) == null,
              "Prime skips unused raster sky extraction even when its owner already exists");
        check(probe.reads == reads + 1, "Exactly one SUN_ANGLE query per source snapshot");
        // Compare periodic angles: the host may normalize a wrapped interpolant.
        double error = Math.IEEEremainder(
                state.skyRenderState.sunAngle - Math.toRadians(expectedDegrees), 2 * Math.PI);
        check(Math.abs(error) < 1e-6,
              "Solar source must change with host time and partial tick: expected=" +
                      expectedDegrees + " degrees, actual=" + state.skyRenderState.sunAngle +
                      " radians");
    }

    private static final class CountingProbe extends EnvironmentAttributeProbe {
        int reads;
        @Override
        public <Value> Value getValue(EnvironmentAttribute<Value> attribute, float partial) {
            check(attribute == EnvironmentAttributes.SUN_ANGLE,
                  "Only the needed source is evaluated");
            ++reads;
            return super.getValue(attribute, partial);
        }
    }
    private static Field field(Class<?> type, String name) throws Exception {
        Field result = type.getDeclaredField(name);
        result.setAccessible(true);
        return result;
    }
    private static void check(boolean value, String message) {
        if (!value)
            throw new AssertionError(message);
    }
}
