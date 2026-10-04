package dev.primept.settings;

import java.nio.ByteBuffer;
import java.lang.foreign.MemorySegment;
import dev.primept.NativeBridge;
import static dev.primept.abi.PrimeAbi.*;
import java.util.Arrays;

/** Immutable client settings. Version adapters own widgets; native consumes the validated wire. */
public final class RenderSettings {
    public static final int VERSION = 9;
    public static final int WIRE_BYTES = (int)PrimeSettings.SIZE;
    public enum Control {
        BOUNCES("render.bounces", 1, 64, 12),
        OFFLINE_SAMPLES("render.offline_samples", 1, 64, 1),
        TERRAIN_BATCHES_PER_FRAME("terrain.batches_per_frame", 1, 128, 8),
        LATITUDE("astronomy.latitude_degrees", -90, 90, 30),
        SOLAR_LONGITUDE("astronomy.solar_longitude_degrees", 0, 359, 0),
        SUN_EV("lighting.sun_ev_quarters", -32, 32, 0),
        SKY_EV("lighting.sky_ev_quarters", -32, 32, 0),
        STARS("lighting.stars_percent", 0, 400, 100),
        AUTO_EXPOSURE("display.auto_exposure_percent", 0, 100, 60),
        HDR("display.hdr", 0, 1, 0),
        HDR_WHITE("display.hdr_reference_white_nits", 0, 10000, 0),
        FRAME_GENERATION("render.frame_generation", 0, 1, 0),
        EXPOSURE_EV("display.exposure_ev_quarters", -48, 48, 0),
        HUE("display.hue_percent", 0, 100, 75),
        SATURATION("display.saturation_percent", 0, 50, 20),
        DEPTH_RANGE("diagnostics.depth_range", 1, 4096, 128);

        public final String key;
        public final int minimum, maximum, initial;
        Control(String key, int minimum, int maximum, int initial) {
            this.key = key;
            this.minimum = minimum;
            this.maximum = maximum;
            this.initial = initial;
        }
        void validate(int value) {
            if (value < minimum || value > maximum)
                throw new IllegalArgumentException(key + " out of range");
        }
    }
    public enum View { OUTPUT, NOISY_COLOR, LINEAR_DEPTH, NORMAL }
    public enum DlssQuality { DLAA, QUALITY, BALANCED, PERFORMANCE, ULTRA_PERFORMANCE }
    public enum LightSampling {
        TREE(1),
        TREE_SPHERE(2);
        public final int wireId;
        LightSampling(int wireId) {
            this.wireId = wireId;
        }
        public static LightSampling fromKey(String key) {
            // Retire the old choice without resetting unrelated saved settings.
            return switch (key) {
                case "GRID", "TREE" -> TREE;
                case "TREE_SPHERE" -> TREE_SPHERE;
                default -> throw new IllegalArgumentException("Unknown light sampling: " + key);
            };
        }
    }
    public enum Renderer {
        VANILLA("vanilla", 0),
        PATH_TRACE("path_trace", 0),
        RESTIR_PT("restir_pt", 1);
        public final String key;
        private final int integrator;
        Renderer(String key, int integrator) {
            this.key = key;
            this.integrator = integrator;
        }
        public static Renderer fromKey(String key) {
            for (var renderer : values())
                if (renderer.key.equals(key))
                    return renderer;
            throw new IllegalArgumentException("Unknown renderer: " + key);
        }
    }
    private final Renderer renderer;
    private final boolean opacityMicromap;
    private final boolean rayReconstruction;
    private final DlssQuality dlssQuality;
    private final LightSampling lightSampling;
    private final boolean ignoreGlobalHistoryResets;
    private final int[] values;

    private RenderSettings(Renderer renderer, boolean opacityMicromap, boolean rayReconstruction,
                           DlssQuality dlssQuality, LightSampling lightSampling,
                           boolean ignoreGlobalHistoryResets, int[] values) {
        this.renderer = renderer;
        this.opacityMicromap = opacityMicromap;
        this.rayReconstruction = rayReconstruction;
        this.dlssQuality = dlssQuality;
        this.lightSampling = lightSampling;
        this.ignoreGlobalHistoryResets = ignoreGlobalHistoryResets;
        this.values = values;
    }
    public static RenderSettings defaults() {
        return new RenderSettings(
                Renderer.PATH_TRACE, true, true, DlssQuality.PERFORMANCE, LightSampling.TREE, false,
                Arrays.stream(Control.values()).mapToInt(c -> c.initial).toArray());
    }
    public boolean pathTracing() {
        return renderer != Renderer.VANILLA;
    }
    public Renderer renderer() {
        return renderer;
    }
    public boolean opacityMicromap() {
        return opacityMicromap;
    }
    public boolean rayReconstruction() {
        return rayReconstruction;
    }
    public DlssQuality dlssQuality() {
        return dlssQuality;
    }
    public LightSampling lightSampling() {
        return lightSampling;
    }
    public boolean ignoreGlobalHistoryResets() {
        return ignoreGlobalHistoryResets;
    }
    public boolean hdr() {
        return value(Control.HDR) != 0;
    }
    public boolean frameGeneration() {
        return value(Control.FRAME_GENERATION) != 0;
    }
    public int value(Control control) {
        return values[control.ordinal()];
    }
    public RenderSettings withPathTracing(boolean value) {
        return withRenderer(value ? pathTracing() ? renderer : Renderer.PATH_TRACE
                                  : Renderer.VANILLA);
    }
    public RenderSettings withRenderer(Renderer value) {
        java.util.Objects.requireNonNull(value);
        if (value == renderer)
            return this;
        return new RenderSettings(value, opacityMicromap, rayReconstruction, dlssQuality,
                                  lightSampling, ignoreGlobalHistoryResets, values);
    }
    public RenderSettings withOpacityMicromap(boolean value) {
        return new RenderSettings(renderer, value, rayReconstruction, dlssQuality, lightSampling,
                                  ignoreGlobalHistoryResets, values);
    }
    public RenderSettings withRayReconstruction(boolean value) {
        return new RenderSettings(renderer, opacityMicromap, value, dlssQuality, lightSampling,
                                  ignoreGlobalHistoryResets, values);
    }
    public RenderSettings withDlssQuality(DlssQuality value) {
        return new RenderSettings(renderer, opacityMicromap, rayReconstruction,
                                  java.util.Objects.requireNonNull(value), lightSampling,
                                  ignoreGlobalHistoryResets, values);
    }
    public RenderSettings withLightSampling(LightSampling value) {
        java.util.Objects.requireNonNull(value);
        if (value == lightSampling)
            return this;
        return new RenderSettings(renderer, opacityMicromap, rayReconstruction, dlssQuality, value,
                                  ignoreGlobalHistoryResets, values);
    }
    public RenderSettings withIgnoreGlobalHistoryResets(boolean value) {
        if (value == ignoreGlobalHistoryResets)
            return this;
        return new RenderSettings(renderer, opacityMicromap, rayReconstruction, dlssQuality,
                                  lightSampling, value, values);
    }
    public RenderSettings with(Control control, int value) {
        control.validate(value);
        int[] next = values.clone();
        next[control.ordinal()] = value;
        return new RenderSettings(renderer, opacityMicromap, rayReconstruction, dlssQuality,
                                  lightSampling, ignoreGlobalHistoryResets, next);
    }
    /** Named C structure, borrowed only for prime_configure; offline and view are session controls. */
    public void write(ByteBuffer target, boolean offline, View view) {
        if (target.capacity() != PrimeSettings.SIZE)
            throw new IllegalArgumentException("Settings structure size mismatch");
        target.clear();
        var s = MemorySegment.ofBuffer(target);
        NativeBridge.header(PrimeSettings.header(s), PrimeSettings.SIZE);
        PrimeSettings.mode(s, offline ? 1 : 0);
        PrimeSettings.bounces(s, value(Control.BOUNCES));
        PrimeSettings.offline_samples(s, value(Control.OFFLINE_SAMPLES));
        PrimeSettings.exposure(s, multiplier(Control.EXPOSURE_EV));
        PrimeSettings.hue(s, value(Control.HUE) / 100.0f);
        PrimeSettings.saturation(s, value(Control.SATURATION) / 100.0f);
        PrimeSettings.view(s, view.ordinal());
        PrimeSettings.sun(s, multiplier(Control.SUN_EV));
        PrimeSettings.sky(s, multiplier(Control.SKY_EV));
        PrimeSettings.depth_range(s, value(Control.DEPTH_RANGE));
        PrimeSettings.seed(s, 0x13572468);
        PrimeSettings.latitude_degrees(s, value(Control.LATITUDE));
        PrimeSettings.solar_longitude_degrees(s, value(Control.SOLAR_LONGITUDE));
        PrimeSettings.opacity_micromap(s, opacityMicromap ? 1 : 0);
        PrimeSettings.ray_reconstruction(s, rayReconstruction ? 1 : 0);
        PrimeSettings.reconstruction_quality(s, dlssQuality.ordinal());
        PrimeSettings.terrain_batches_per_frame(s, value(Control.TERRAIN_BATCHES_PER_FRAME));
        PrimeSettings.stars(s, value(Control.STARS) / 100.0f);
        PrimeSettings.auto_exposure_compensation(s, value(Control.AUTO_EXPOSURE) / 100.0f);
        PrimeSettings.hdr(s, hdr() ? 1 : 0);
        PrimeSettings.hdr_reference_white(s, value(Control.HDR_WHITE));
        PrimeSettings.frame_generation(s, frameGeneration() ? 1 : 0);
        PrimeSettings.light_sampling(s, lightSampling.wireId);
        PrimeSettings.integrator(s, renderer.integrator);
        PrimeSettings.ignore_global_history_resets(s, ignoreGlobalHistoryResets ? 1 : 0);
        target.position(WIRE_BYTES);
    }

    private float multiplier(Control control) {
        return (float)Math.pow(2.0, value(control) / 4.0);
    }
    @Override
    public boolean equals(Object other) {
        return other instanceof RenderSettings settings && renderer == settings.renderer &&
                opacityMicromap == settings.opacityMicromap &&
                rayReconstruction == settings.rayReconstruction &&
                dlssQuality == settings.dlssQuality && lightSampling == settings.lightSampling &&
                ignoreGlobalHistoryResets == settings.ignoreGlobalHistoryResets &&
                Arrays.equals(values, settings.values);
    }
    @Override
    public int hashCode() {
        return 31 * (31 * (31 * (31 * (31 * (31 * renderer.hashCode() +
                                             Boolean.hashCode(opacityMicromap)) +
                                       Boolean.hashCode(rayReconstruction)) +
                                 dlssQuality.hashCode()) +
                           lightSampling.hashCode()) +
                     Boolean.hashCode(ignoreGlobalHistoryResets)) +
                Arrays.hashCode(values);
    }
}
