package dev.primept.settings;

import java.nio.ByteBuffer;
import java.util.Arrays;

/** Immutable client settings. Version adapters own widgets; native consumes the validated wire. */
public final class RenderSettings {
    public static final int VERSION = 5;
    public static final int WIRE_BYTES = 72;
    public enum Control {
        BOUNCES("render.bounces", 1, 64, 12),
        OFFLINE_SAMPLES("render.offline_samples", 1, 64, 1),
        TERRAIN_BATCHES_PER_FRAME("terrain.batches_per_frame", 1, 128, 8),
        LATITUDE("astronomy.latitude_degrees", -90, 90, 30),
        SOLAR_LONGITUDE("astronomy.solar_longitude_degrees", 0, 359, 0),
        SUN_EV("lighting.sun_ev_quarters", -32, 32, 0),
        SKY_EV("lighting.sky_ev_quarters", -32, 32, 0),
        EXPOSURE_EV("display.exposure_ev_quarters", -48, 48, 0),
        HUE("display.hue_percent", 0, 100, 75),
        SATURATION("display.saturation_percent", 0, 50, 8),
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
    private final boolean pathTracing;
    private final boolean opacityMicromap;
    private final boolean rayReconstruction;
    private final DlssQuality dlssQuality;
    private final int[] values;

    private RenderSettings(boolean pathTracing, boolean opacityMicromap, boolean rayReconstruction,
                           DlssQuality dlssQuality, int[] values) {
        this.pathTracing = pathTracing;
        this.opacityMicromap = opacityMicromap;
        this.rayReconstruction = rayReconstruction;
        this.dlssQuality = dlssQuality;
        this.values = values;
    }
    public static RenderSettings defaults() {
        return new RenderSettings(
                true, true, true, DlssQuality.PERFORMANCE,
                Arrays.stream(Control.values()).mapToInt(c -> c.initial).toArray());
    }
    public boolean pathTracing() {
        return pathTracing;
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
    public int value(Control control) {
        return values[control.ordinal()];
    }
    public RenderSettings withPathTracing(boolean value) {
        return new RenderSettings(value, opacityMicromap, rayReconstruction, dlssQuality, values);
    }
    public RenderSettings withOpacityMicromap(boolean value) {
        return new RenderSettings(pathTracing, value, rayReconstruction, dlssQuality, values);
    }
    public RenderSettings withRayReconstruction(boolean value) {
        return new RenderSettings(pathTracing, opacityMicromap, value, dlssQuality, values);
    }
    public RenderSettings withDlssQuality(DlssQuality value) {
        return new RenderSettings(pathTracing, opacityMicromap, rayReconstruction,
                                  java.util.Objects.requireNonNull(value), values);
    }
    public RenderSettings with(Control control, int value) {
        control.validate(value);
        int[] next = values.clone();
        next[control.ordinal()] = value;
        return new RenderSettings(pathTracing, opacityMicromap, rayReconstruction, dlssQuality,
                                  next);
    }
    /** Little-endian, borrowed only for prime_configure; offline and view are session controls. */
    public void write(ByteBuffer target, boolean offline, View view) {
        target.clear();
        target.putInt(VERSION)
                .putInt(offline ? 1 : 0)
                .putInt(value(Control.BOUNCES))
                .putInt(value(Control.OFFLINE_SAMPLES))
                .putFloat(multiplier(Control.EXPOSURE_EV))
                .putFloat(value(Control.HUE) / 100.0f)
                .putFloat(value(Control.SATURATION) / 100.0f)
                .putInt(view.ordinal())
                .putFloat(multiplier(Control.SUN_EV))
                .putFloat(multiplier(Control.SKY_EV))
                .putFloat(value(Control.DEPTH_RANGE))
                .putInt(0x13572468)
                .putInt(value(Control.LATITUDE))
                .putInt(value(Control.SOLAR_LONGITUDE))
                .putInt(opacityMicromap ? 1 : 0)
                .putInt(rayReconstruction ? 1 : 0)
                .putInt(dlssQuality.ordinal())
                .putInt(value(Control.TERRAIN_BATCHES_PER_FRAME));
    }
    private float multiplier(Control control) {
        return (float)Math.pow(2.0, value(control) / 4.0);
    }
    @Override
    public boolean equals(Object other) {
        return other instanceof RenderSettings settings && pathTracing == settings.pathTracing &&
                opacityMicromap == settings.opacityMicromap &&
                rayReconstruction == settings.rayReconstruction &&
                dlssQuality == settings.dlssQuality && Arrays.equals(values, settings.values);
    }
    @Override
    public int hashCode() {
        return 31 * (31 * (31 * (31 * Boolean.hashCode(pathTracing) +
                                 Boolean.hashCode(opacityMicromap)) +
                           Boolean.hashCode(rayReconstruction)) +
                     dlssQuality.hashCode()) +
                Arrays.hashCode(values);
    }
}
