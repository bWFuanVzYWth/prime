package dev.primept.settings;

import java.nio.ByteBuffer;
import java.lang.foreign.MemorySegment;
import dev.primept.NativeBridge;
import static dev.primept.abi.PrimeAbi.*;
import java.util.Arrays;

/** Immutable client settings. Version adapters own widgets; native consumes the validated wire. */
public final class RenderSettings {
    public static final int VERSION = 13;
    public static final int WIRE_BYTES = (int)PrimeSettings.SIZE;
    public enum Control {
        BOUNCES("render.bounces", 1, 64, 12),
        OFFLINE_SAMPLES("render.offline_samples", 1, 64, 1),
        TERRAIN_BATCHES_PER_FRAME("terrain.batches_per_frame", 1, 128, 1),
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
        TREE(1);
        public final int wireId;
        LightSampling(int wireId) {
            this.wireId = wireId;
        }
        public static LightSampling fromKey(String key) {
            // Retired samplers migrate without resetting unrelated saved settings.
            return switch (key) {
                case "GRID", "TREE_SPHERE", "TREE" -> TREE;
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
    private final Renderer realtimeRenderer;
    private final boolean pathTracing;
    private final boolean opacityMicromap;
    private final boolean nativeNoisyOutput;
    private final DlssQuality dlssQuality;
    private final LightSampling lightSampling;
    private final boolean ignoreGlobalHistoryResets;
    private final boolean restirSpatialOnly;
    private final RestirSettings restir;
    private final int[] values;

    private RenderSettings(Renderer realtimeRenderer, boolean pathTracing, boolean opacityMicromap,
                           boolean nativeNoisyOutput, DlssQuality dlssQuality,
                           LightSampling lightSampling, boolean ignoreGlobalHistoryResets,
                           boolean restirSpatialOnly, RestirSettings restir, int[] values) {
        this.realtimeRenderer = realtimeRenderer;
        this.pathTracing = pathTracing;
        this.opacityMicromap = opacityMicromap;
        this.nativeNoisyOutput = nativeNoisyOutput;
        this.dlssQuality = dlssQuality;
        this.lightSampling = lightSampling;
        this.ignoreGlobalHistoryResets = ignoreGlobalHistoryResets;
        this.restirSpatialOnly = restirSpatialOnly;
        this.restir = java.util.Objects.requireNonNull(restir);
        this.values = values;
    }
    public static RenderSettings defaults() {
        return new RenderSettings(
                Renderer.PATH_TRACE, true, true, false, DlssQuality.PERFORMANCE, LightSampling.TREE,
                false, false, RestirSettings.defaults(),
                Arrays.stream(Control.values()).mapToInt(c -> c.initial).toArray());
    }
    public boolean pathTracing() {
        return pathTracing;
    }
    public Renderer renderer() {
        return pathTracing ? realtimeRenderer : Renderer.VANILLA;
    }
    /** The remembered real-time integrator is independent of the vanilla/PT switch. */
    public Renderer realtimeRenderer() {
        return realtimeRenderer;
    }
    public boolean opacityMicromap() {
        return opacityMicromap;
    }
    public boolean nativeNoisyOutput() {
        return nativeNoisyOutput;
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
    public boolean restirSpatialOnly() {
        return restirSpatialOnly;
    }
    public boolean restirTemporalReuse() {
        return !restirSpatialOnly;
    }
    public RestirSettings restir() {
        return restir;
    }
    public RenderSettings withRestir(RestirSettings value) {
        java.util.Objects.requireNonNull(value);
        if (restir.equals(value))
            return this;
        return new RenderSettings(realtimeRenderer, pathTracing, opacityMicromap, nativeNoisyOutput,
                                  dlssQuality, lightSampling, ignoreGlobalHistoryResets,
                                  restirSpatialOnly, value, values);
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
        if (value == pathTracing)
            return this;
        return new RenderSettings(realtimeRenderer, value, opacityMicromap, nativeNoisyOutput,
                                  dlssQuality, lightSampling, ignoreGlobalHistoryResets,
                                  restirSpatialOnly, restir, values);
    }
    /** Compatibility for startup/backend requests: PT choices enable PT, vanilla disables it. */
    public RenderSettings withRenderer(Renderer value) {
        java.util.Objects.requireNonNull(value);
        return value == Renderer.VANILLA ? withPathTracing(false)
                                         : withRealtimeRenderer(value).withPathTracing(true);
    }
    public RenderSettings withRealtimeRenderer(Renderer value) {
        java.util.Objects.requireNonNull(value);
        if (value == Renderer.VANILLA)
            throw new IllegalArgumentException("Vanilla is not a real-time PT integrator");
        if (value == realtimeRenderer)
            return this;
        return new RenderSettings(value, pathTracing, opacityMicromap, nativeNoisyOutput,
                                  dlssQuality, lightSampling, ignoreGlobalHistoryResets,
                                  restirSpatialOnly, restir, values);
    }
    public RenderSettings withOpacityMicromap(boolean value) {
        return new RenderSettings(realtimeRenderer, pathTracing, value, nativeNoisyOutput,
                                  dlssQuality, lightSampling, ignoreGlobalHistoryResets,
                                  restirSpatialOnly, restir, values);
    }
    public RenderSettings withNativeNoisyOutput(boolean value) {
        return new RenderSettings(realtimeRenderer, pathTracing, opacityMicromap, value,
                                  dlssQuality, lightSampling, ignoreGlobalHistoryResets,
                                  restirSpatialOnly, restir, values);
    }
    public RenderSettings withDlssQuality(DlssQuality value) {
        return new RenderSettings(realtimeRenderer, pathTracing, opacityMicromap, nativeNoisyOutput,
                                  java.util.Objects.requireNonNull(value), lightSampling,
                                  ignoreGlobalHistoryResets, restirSpatialOnly, restir, values);
    }
    public RenderSettings withLightSampling(LightSampling value) {
        java.util.Objects.requireNonNull(value);
        if (value == lightSampling)
            return this;
        return new RenderSettings(realtimeRenderer, pathTracing, opacityMicromap, nativeNoisyOutput,
                                  dlssQuality, value, ignoreGlobalHistoryResets, restirSpatialOnly,
                                  restir, values);
    }
    public RenderSettings withIgnoreGlobalHistoryResets(boolean value) {
        if (value == ignoreGlobalHistoryResets)
            return this;
        return new RenderSettings(realtimeRenderer, pathTracing, opacityMicromap, nativeNoisyOutput,
                                  dlssQuality, lightSampling, value, restirSpatialOnly, restir,
                                  values);
    }
    public RenderSettings withRestirSpatialOnly(boolean value) {
        if (value == restirSpatialOnly)
            return this;
        return new RenderSettings(realtimeRenderer, pathTracing, opacityMicromap, nativeNoisyOutput,
                                  dlssQuality, lightSampling, ignoreGlobalHistoryResets, value,
                                  restir, values);
    }
    /** Public setting uses positive semantics; the native ABI retains its spatial-only bit. */
    public RenderSettings withRestirTemporalReuse(boolean value) {
        return withRestirSpatialOnly(!value);
    }
    public RenderSettings with(Control control, int value) {
        control.validate(value);
        int[] next = values.clone();
        next[control.ordinal()] = value;
        return new RenderSettings(realtimeRenderer, pathTracing, opacityMicromap, nativeNoisyOutput,
                                  dlssQuality, lightSampling, ignoreGlobalHistoryResets,
                                  restirSpatialOnly, restir, next);
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
        restir.write(s);
        PrimeSettings.latitude_degrees(s, value(Control.LATITUDE));
        PrimeSettings.solar_longitude_degrees(s, value(Control.SOLAR_LONGITUDE));
        PrimeSettings.opacity_micromap(s, opacityMicromap ? 1 : 0);
        PrimeSettings.native_noisy_output(s, nativeNoisyOutput ? 1 : 0);
        PrimeSettings.reconstruction_quality(s, dlssQuality.ordinal());
        PrimeSettings.terrain_batches_per_frame(s, value(Control.TERRAIN_BATCHES_PER_FRAME));
        PrimeSettings.stars(s, value(Control.STARS) / 100.0f);
        PrimeSettings.auto_exposure_compensation(s, value(Control.AUTO_EXPOSURE) / 100.0f);
        PrimeSettings.hdr(s, hdr() ? 1 : 0);
        PrimeSettings.hdr_reference_white(s, value(Control.HDR_WHITE));
        PrimeSettings.frame_generation(s, frameGeneration() ? 1 : 0);
        PrimeSettings.light_sampling(s, lightSampling.wireId);
        PrimeSettings.integrator(s, renderer().integrator);
        PrimeSettings.ignore_global_history_resets(s, ignoreGlobalHistoryResets ? 1 : 0);
        PrimeSettings.restir_spatial_only(s, restirSpatialOnly ? 1 : 0);
        target.position(WIRE_BYTES);
    }

    private float multiplier(Control control) {
        return (float)Math.pow(2.0, value(control) / 4.0);
    }
    @Override
    public boolean equals(Object other) {
        return other instanceof RenderSettings settings &&
                realtimeRenderer == settings.realtimeRenderer &&
                pathTracing == settings.pathTracing &&
                opacityMicromap == settings.opacityMicromap &&
                nativeNoisyOutput == settings.nativeNoisyOutput &&
                dlssQuality == settings.dlssQuality && lightSampling == settings.lightSampling &&
                ignoreGlobalHistoryResets == settings.ignoreGlobalHistoryResets &&
                restirSpatialOnly == settings.restirSpatialOnly && restir.equals(settings.restir) &&
                Arrays.equals(values, settings.values);
    }
    @Override
    public int hashCode() {
        return 31 * java.util.Objects.hash(realtimeRenderer, pathTracing, opacityMicromap,
                                           nativeNoisyOutput, dlssQuality, lightSampling,
                                           ignoreGlobalHistoryResets, restirSpatialOnly, restir) +
                Arrays.hashCode(values);
    }
}
