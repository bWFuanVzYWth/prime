package dev.primept.settings;

import dev.primept.abi.PrimeAbi.PrimeSettings;
import java.lang.foreign.MemorySegment;
import java.util.Arrays;
import java.util.Properties;

/** Immutable point/Hybrid controls, validated before crossing the native boundary. */
public final class RestirSettings {
    public static final long DEFAULT_SEED = 0x13572468L;
    public enum Kind { INTEGER, BOOLEAN, FLOAT }
    public enum Control {
        HISTORY_LENGTH("history_length", Kind.INTEGER, 0, 100, 20, 1),
        SPATIAL_REUSE("spatial_reuse", Kind.BOOLEAN, 0, 1, 1, 1),
        SPATIAL_ITERATIONS("spatial_iterations", Kind.INTEGER, 0, 8, 1, 1),
        SPATIAL_NEIGHBORS("spatial_neighbors", Kind.INTEGER, 1, 5, 3, 1),
        PAIRING_RADIUS("pairing_radius", Kind.INTEGER, 5, 50, 30, 5),
        STOCHASTIC_REPROJECTION("stochastic_reprojection", Kind.BOOLEAN, 0, 1, 0, 1),
        DUPLICATE_MAP("duplicate_map", Kind.BOOLEAN, 0, 1, 1, 1),
        DUPLICATION_POWER("duplication_power", Kind.FLOAT, 0, 10, 0.1, 0),
        DECOUPLED_SHADING("decoupled_shading", Kind.BOOLEAN, 0, 1, 0, 1),
        INITIAL_SAMPLES("initial_samples", Kind.INTEGER, 1, 16, 1, 1),
        DISTANCE_THRESHOLD("distance_threshold", Kind.FLOAT, 0, 10000, 0.02, 0),
        DISTANCE_SIGMA("distance_sigma", Kind.FLOAT, 0, 1, 0.2, 0),
        ROUGHNESS_THRESHOLD("roughness_threshold", Kind.FLOAT, 0, 1, 0.2, 0),
        ROUGHNESS_SIGMA("roughness_sigma", Kind.FLOAT, 0, 1, 0, 0),
        NORMAL_THRESHOLD("normal_threshold", Kind.FLOAT, -1, 1, 0.5, 0),
        DEPTH_THRESHOLD("depth_threshold", Kind.FLOAT, 0, 1, 0.1, 0),
        DEBUG_VIEW("debug_view", Kind.INTEGER, 0, 2, 0, 1),
        RR_DECORRELATION("rr_decorrelation", Kind.BOOLEAN, 0, 1, 1, 1),
        RR_MODE("rr_mode", Kind.INTEGER, 0, 2, 2, 1),
        RR_FACTOR("rr_factor", Kind.FLOAT, 0, 1, 0.4, 0),
        RR_STAGNANCY_EXPONENT("rr_stagnancy_exponent", Kind.FLOAT, 0, 10, 0.5, 0),
        RR_EMA("rr_ema", Kind.FLOAT, 0, 1, 0.2, 0),
        RR_FIREFLY_STRENGTH("rr_firefly_strength", Kind.FLOAT, 0, 1, 0.7, 0),
        RR_MULTIPLY_BOUND("rr_multiply_bound", Kind.FLOAT, 1, 100, 15, 0),
        RR_BIAS_REDUCTION("rr_bias_reduction", Kind.BOOLEAN, 0, 1, 1, 1),
        RR_FIREFLY("rr_firefly", Kind.BOOLEAN, 0, 1, 1, 1);

        public final String key;
        public final Kind kind;
        public final double minimum, maximum, initial;
        public final int step;
        Control(String key, Kind kind, double minimum, double maximum, double initial, int step) {
            this.key = "restir_pt." + key;
            this.kind = kind;
            this.minimum = minimum;
            this.maximum = maximum;
            this.initial = normalize(initial);
            this.step = step;
        }
        public double normalize(double value) {
            return kind == Kind.FLOAT ? (double)(float)value : value;
        }
        public void validate(double value) {
            if (!Double.isFinite(value) || value < minimum || value > maximum ||
                kind != Kind.FLOAT && (value != Math.rint(value) || value % step != 0))
                throw new IllegalArgumentException(key + " out of range");
        }
        String encode(double value) {
            return switch (kind) {
                case BOOLEAN -> Boolean.toString(value != 0);
                case INTEGER -> Integer.toString((int)value);
                case FLOAT -> Float.toString((float)value);
            };
        }
        double decode(String value) {
            if (kind == Kind.BOOLEAN) {
                if (!"true".equals(value) && !"false".equals(value))
                    throw new IllegalArgumentException("Invalid " + key);
                return Boolean.parseBoolean(value) ? 1 : 0;
            }
            return Double.parseDouble(value);
        }
    }
    private final double[] values;
    private final long seed;
    private RestirSettings(double[] values, long seed) {
        this.values = values;
        this.seed = seed;
    }
    public static RestirSettings defaults() {
        return new RestirSettings(
                Arrays.stream(Control.values()).mapToDouble(c -> c.initial).toArray(),
                DEFAULT_SEED);
    }
    public double value(Control control) {
        return values[control.ordinal()];
    }
    public boolean enabled(Control control) {
        return value(control) != 0;
    }
    public long seed() {
        return seed;
    }
    public RestirSettings with(Control control, double value) {
        control.validate(value);
        value = control.normalize(value);
        if (value == value(control))
            return this;
        var next = values.clone();
        next[control.ordinal()] = value;
        return new RestirSettings(next, seed);
    }
    public RestirSettings withSeed(long value) {
        if (value < 0 || value > 0xffffffffL)
            throw new IllegalArgumentException("restir_pt.seed out of uint32 range");
        return value == seed ? this : new RestirSettings(values, value);
    }
    static RestirSettings decode(Properties properties, boolean legacy) {
        var result = defaults();
        if (legacy)
            return result;
        for (var control : Control.values())
            result = result.with(control, control.decode(properties.getProperty(control.key)));
        return result.withSeed(Long.parseLong(properties.getProperty("restir_pt.seed")));
    }
    void encode(StringBuilder text) {
        for (var control : Control.values())
            text.append(control.key)
                    .append('=')
                    .append(control.encode(value(control)))
                    .append('\n');
        text.append("restir_pt.seed=").append(seed).append('\n');
    }
    void write(MemorySegment s) {
        PrimeSettings.seed(s, (int)seed);
        PrimeSettings.restir_history_length(s, (int)value(Control.HISTORY_LENGTH));
        PrimeSettings.restir_spatial_reuse(s, (int)value(Control.SPATIAL_REUSE));
        PrimeSettings.restir_spatial_iterations(s, (int)value(Control.SPATIAL_ITERATIONS));
        PrimeSettings.restir_spatial_neighbors(s, (int)value(Control.SPATIAL_NEIGHBORS));
        PrimeSettings.restir_pairing_radius(s, (int)value(Control.PAIRING_RADIUS));
        PrimeSettings.restir_stochastic_reprojection(s,
                                                     (int)value(Control.STOCHASTIC_REPROJECTION));
        PrimeSettings.restir_duplicate_map(s, (int)value(Control.DUPLICATE_MAP));
        PrimeSettings.restir_duplication_power(s, (float)value(Control.DUPLICATION_POWER));
        PrimeSettings.restir_decoupled_shading(s, (int)value(Control.DECOUPLED_SHADING));
        PrimeSettings.restir_initial_samples(s, (int)value(Control.INITIAL_SAMPLES));
        PrimeSettings.restir_distance_threshold(s, (float)value(Control.DISTANCE_THRESHOLD));
        PrimeSettings.restir_distance_sigma(s, (float)value(Control.DISTANCE_SIGMA));
        PrimeSettings.restir_roughness_threshold(s, (float)value(Control.ROUGHNESS_THRESHOLD));
        PrimeSettings.restir_roughness_sigma(s, (float)value(Control.ROUGHNESS_SIGMA));
        PrimeSettings.restir_normal_threshold(s, (float)value(Control.NORMAL_THRESHOLD));
        PrimeSettings.restir_depth_threshold(s, (float)value(Control.DEPTH_THRESHOLD));
        PrimeSettings.restir_debug_view(s, (int)value(Control.DEBUG_VIEW));
        PrimeSettings.restir_rr_decorrelation(s, (int)value(Control.RR_DECORRELATION));
        PrimeSettings.restir_rr_mode(s, (int)value(Control.RR_MODE));
        PrimeSettings.restir_rr_factor(s, (float)value(Control.RR_FACTOR));
        PrimeSettings.restir_rr_stagnancy_exponent(s, (float)value(Control.RR_STAGNANCY_EXPONENT));
        PrimeSettings.restir_rr_ema(s, (float)value(Control.RR_EMA));
        PrimeSettings.restir_rr_firefly_strength(s, (float)value(Control.RR_FIREFLY_STRENGTH));
        PrimeSettings.restir_rr_multiply_bound(s, (float)value(Control.RR_MULTIPLY_BOUND));
        PrimeSettings.restir_rr_bias_reduction(s, (int)value(Control.RR_BIAS_REDUCTION));
        PrimeSettings.restir_rr_firefly(s, (int)value(Control.RR_FIREFLY));
    }
    @Override
    public boolean equals(Object other) {
        return other instanceof RestirSettings settings && seed == settings.seed &&
                Arrays.equals(values, settings.values);
    }
    @Override
    public int hashCode() {
        return 31 * Long.hashCode(seed) + Arrays.hashCode(values);
    }
}
