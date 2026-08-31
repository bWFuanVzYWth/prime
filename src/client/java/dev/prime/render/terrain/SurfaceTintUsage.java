package dev.prime.render.terrain;

import dev.prime.render.scene.CapturedSectionGeometry;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Exact four-corner tint facts retained for stage-2 continuous-field layout decisions. */
public final class SurfaceTintUsage {
    public static final String MEASUREMENT_ENABLE_PROPERTY = "prime.renderer.measure";
    public static final SurfaceTintUsage EMPTY = new SurfaceTintUsage(
            Set.of(), 0L, 0L, 0L, 0L, 0L, 0L, 0L);

    private final Set<Integer> sourceColors;
    private final long primaryReferences;
    private final long relationReferences;
    private final long constantReferences;
    private final long varyingReferences;
    private final long varyingRgbReferences;
    private final long varyingAlphaReferences;
    private final long nonOpaqueAlphaReferences;

    private SurfaceTintUsage(
            Set<Integer> sourceColors,
            long primaryReferences,
            long relationReferences,
            long constantReferences,
            long varyingReferences,
            long varyingRgbReferences,
            long varyingAlphaReferences,
            long nonOpaqueAlphaReferences) {
        this.sourceColors = Set.copyOf(sourceColors);
        this.primaryReferences = requireNonNegative(primaryReferences, "Primary reference count");
        this.relationReferences = requireNonNegative(relationReferences, "Relation reference count");
        this.constantReferences = requireNonNegative(constantReferences, "Constant reference count");
        this.varyingReferences = requireNonNegative(varyingReferences, "Varying reference count");
        this.varyingRgbReferences = requireNonNegative(
                varyingRgbReferences, "RGB-varying reference count");
        this.varyingAlphaReferences = requireNonNegative(
                varyingAlphaReferences, "Alpha-varying reference count");
        this.nonOpaqueAlphaReferences = requireNonNegative(
                nonOpaqueAlphaReferences, "Non-opaque-alpha reference count");
        long total = this.referenceCount();
        if (constantReferences > total
                || varyingReferences > total
                || varyingRgbReferences > varyingReferences
                || varyingAlphaReferences > varyingReferences
                || nonOpaqueAlphaReferences > total) {
            throw new IllegalArgumentException("Surface tint classifications are inconsistent");
        }
    }

    /** Sums concurrently resident cluster observations and unions exact source colors. */
    public static SurfaceTintUsage combine(List<SurfaceTintUsage> values) {
        Builder result = new Builder();
        for (SurfaceTintUsage value : values) {
            result.add(Objects.requireNonNull(value, "value"), false);
        }
        return result.build();
    }

    /** Unions observations over time while retaining peak, rather than frame-summed, counts. */
    public SurfaceTintUsage observedUnion(SurfaceTintUsage other) {
        Objects.requireNonNull(other, "other");
        Builder result = new Builder();
        result.add(this, true);
        result.add(other, true);
        return result.build();
    }

    public Set<Integer> sourceColors() {
        return this.sourceColors;
    }

    public long primaryReferences() {
        return this.primaryReferences;
    }

    public long relationReferences() {
        return this.relationReferences;
    }

    public long constantReferences() {
        return this.constantReferences;
    }

    public long varyingRgbReferences() {
        return this.varyingRgbReferences;
    }

    public long varyingAlphaReferences() {
        return this.varyingAlphaReferences;
    }

    public long nonOpaqueAlphaReferences() {
        return this.nonOpaqueAlphaReferences;
    }

    public long referenceCount() {
        return Math.addExact(this.primaryReferences, this.relationReferences);
    }

    /** A reference is varying when either its RGB or alpha corners differ. */
    public long varyingReferences() {
        return this.varyingReferences;
    }

    /** One globally deduplicated RGBA16F sample per exact source color. */
    public long globalSamplePaletteRgba16fBytes() {
        return Math.multiplyExact((long) this.sourceColors.size(), 4L * Short.BYTES);
    }

    /** Global RGBA16F samples plus four u16 sample IDs per varying source quad. */
    public long quadIndexedRgba16fBytes() {
        return Math.addExact(
                this.globalSamplePaletteRgba16fBytes(),
                Math.multiplyExact(this.varyingReferences(), 4L * Short.BYTES));
    }

    /** Global RGBA16F samples plus three u16 IDs for each of two source triangles. */
    public long triangleIndexedRgba16fBytes() {
        return Math.addExact(
                this.globalSamplePaletteRgba16fBytes(),
                Math.multiplyExact(this.varyingReferences(), 2L * 3L * Short.BYTES));
    }

    /** Four RGBA16F corners shared by the two triangles of each varying source quad. */
    public long quadSharedRgba16fBytes() {
        return Math.multiplyExact(this.varyingReferences(), 4L * 4L * Short.BYTES);
    }

    /** Three RGBA16F samples stored independently for each of the two source triangles. */
    public long triangleLocalRgba16fBytes() {
        return Math.multiplyExact(this.varyingReferences(), 2L * 3L * 4L * Short.BYTES);
    }

    static Builder builder() {
        return new Builder(true);
    }

    static Builder builder(boolean enabled) {
        return new Builder(enabled);
    }

    static Builder runtimeBuilder() {
        return new Builder(Boolean.getBoolean(MEASUREMENT_ENABLE_PROPERTY));
    }

    private static long requireNonNegative(long value, String name) {
        if (value < 0L) {
            throw new IllegalArgumentException(name + " must not be negative");
        }
        return value;
    }

    static final class Builder {
        private final HashSet<Integer> sourceColors;
        private long primaryReferences;
        private long relationReferences;
        private long constantReferences;
        private long varyingReferences;
        private long varyingRgbReferences;
        private long varyingAlphaReferences;
        private long nonOpaqueAlphaReferences;

        private Builder() {
            this(true);
        }

        private Builder(boolean enabled) {
            this.sourceColors = enabled ? new HashSet<>() : null;
        }

        void addPrimary(CapturedSectionGeometry.Surface surface) {
            this.addSurface(surface, false);
        }

        void addRelation(CapturedSectionGeometry.Surface surface) {
            this.addSurface(surface, true);
        }

        private void addSurface(CapturedSectionGeometry.Surface surface, boolean relation) {
            Objects.requireNonNull(surface, "surface");
            if (this.sourceColors == null) {
                return;
            }
            int first = surface.color0();
            boolean constant = true;
            boolean varyingRgb = false;
            boolean varyingAlpha = false;
            boolean nonOpaqueAlpha = false;
            for (int vertex = 0; vertex < 4; vertex++) {
                int color = surface.color(vertex);
                this.sourceColors.add(color);
                constant &= color == first;
                varyingRgb |= (color & 0x00ff_ffff) != (first & 0x00ff_ffff);
                varyingAlpha |= (color >>> 24) != (first >>> 24);
                nonOpaqueAlpha |= (color >>> 24) != 0xff;
            }
            if (relation) {
                this.relationReferences = Math.addExact(this.relationReferences, 1L);
            } else {
                this.primaryReferences = Math.addExact(this.primaryReferences, 1L);
            }
            if (constant) {
                this.constantReferences = Math.addExact(this.constantReferences, 1L);
            } else {
                this.varyingReferences = Math.addExact(this.varyingReferences, 1L);
            }
            if (varyingRgb) {
                this.varyingRgbReferences = Math.addExact(this.varyingRgbReferences, 1L);
            }
            if (varyingAlpha) {
                this.varyingAlphaReferences = Math.addExact(this.varyingAlphaReferences, 1L);
            }
            if (nonOpaqueAlpha) {
                this.nonOpaqueAlphaReferences = Math.addExact(
                        this.nonOpaqueAlphaReferences, 1L);
            }
        }

        void add(SurfaceTintUsage value, boolean maximum) {
            if (this.sourceColors == null) {
                return;
            }
            this.sourceColors.addAll(value.sourceColors);
            if (maximum) {
                this.primaryReferences = Math.max(
                        this.primaryReferences, value.primaryReferences);
                this.relationReferences = Math.max(
                        this.relationReferences, value.relationReferences);
                this.constantReferences = Math.max(
                        this.constantReferences, value.constantReferences);
                this.varyingReferences = Math.max(
                        this.varyingReferences, value.varyingReferences);
                this.varyingRgbReferences = Math.max(
                        this.varyingRgbReferences, value.varyingRgbReferences);
                this.varyingAlphaReferences = Math.max(
                        this.varyingAlphaReferences, value.varyingAlphaReferences);
                this.nonOpaqueAlphaReferences = Math.max(
                        this.nonOpaqueAlphaReferences, value.nonOpaqueAlphaReferences);
            } else {
                this.primaryReferences = Math.addExact(
                        this.primaryReferences, value.primaryReferences);
                this.relationReferences = Math.addExact(
                        this.relationReferences, value.relationReferences);
                this.constantReferences = Math.addExact(
                        this.constantReferences, value.constantReferences);
                this.varyingReferences = Math.addExact(
                        this.varyingReferences, value.varyingReferences);
                this.varyingRgbReferences = Math.addExact(
                        this.varyingRgbReferences, value.varyingRgbReferences);
                this.varyingAlphaReferences = Math.addExact(
                        this.varyingAlphaReferences, value.varyingAlphaReferences);
                this.nonOpaqueAlphaReferences = Math.addExact(
                        this.nonOpaqueAlphaReferences, value.nonOpaqueAlphaReferences);
            }
        }

        SurfaceTintUsage build() {
            if (this.sourceColors == null
                    || this.primaryReferences == 0L && this.relationReferences == 0L) {
                return EMPTY;
            }
            return new SurfaceTintUsage(
                    this.sourceColors,
                    this.primaryReferences,
                    this.relationReferences,
                    this.constantReferences,
                    this.varyingReferences,
                    this.varyingRgbReferences,
                    this.varyingAlphaReferences,
                    this.nonOpaqueAlphaReferences);
        }
    }
}
