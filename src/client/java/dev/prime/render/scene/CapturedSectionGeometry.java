package dev.prime.render.scene;

import dev.prime.render.material.BuiltinMaterialClass;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Immutable geometry facts observed while Minecraft compiles one Section.
 *
 * <p>This value deliberately stops before Prime material encoding, triangle lowering, face
 * merging, opacity micromaps, light extraction, and GPU ABI packing. Referenced sprites belong to
 * the immutable resource epoch captured by the caller.
 */
public final class CapturedSectionGeometry {
    private final List<Quad> quads;

    private CapturedSectionGeometry(List<Quad> quads) {
        this.quads = List.copyOf(quads);
    }

    public List<Quad> quads() {
        return this.quads;
    }

    /** Thread-confined capture scratch. Values are copied when a quad is published. */
    public static final class MutableQuad {
        public final float[] x = new float[4];
        public final float[] y = new float[4];
        public final float[] z = new float[4];
        public final float[] u = new float[4];
        public final float[] v = new float[4];
        public float normalX;
        public float normalY;
        public float normalZ;
    }

    /** One accepted Minecraft quad with immutable vertex attributes and semantic facts. */
    public static final class Quad {
        private final float[] x;
        private final float[] y;
        private final float[] z;
        private final float[] u;
        private final float[] v;
        private final float normalX;
        private final float normalY;
        private final float normalZ;
        private final Surface surface;
        private final boolean peerOnly;

        private Quad(MutableQuad source, Surface surface, boolean peerOnly) {
            this.x = source.x.clone();
            this.y = source.y.clone();
            this.z = source.z.clone();
            this.u = source.u.clone();
            this.v = source.v.clone();
            this.normalX = source.normalX;
            this.normalY = source.normalY;
            this.normalZ = source.normalZ;
            this.surface = Objects.requireNonNull(surface, "surface");
            this.peerOnly = peerOnly;
        }

        public float x(int vertex) {
            return this.x[vertex];
        }

        public float y(int vertex) {
            return this.y[vertex];
        }

        public float z(int vertex) {
            return this.z[vertex];
        }

        public float u(int vertex) {
            return this.u[vertex];
        }

        public float v(int vertex) {
            return this.v[vertex];
        }

        public float normalX() {
            return this.normalX;
        }

        public float normalY() {
            return this.normalY;
        }

        public float normalZ() {
            return this.normalZ;
        }

        public Surface surface() {
            return this.surface;
        }

        /** True for a cluster-halo face captured only to resolve the shared physical boundary. */
        public boolean peerOnly() {
            return this.peerOnly;
        }
    }

    /**
     * Facts needed to translate an accepted surface without retaining its block state or renderer
     * implementation.
     */
    public record Surface(
            int color0,
            int color1,
            int color2,
            int color3,
            Layer layer,
            int flags,
            int lightEmission,
            CapturedSprite sprite,
            FluidFacts fluid,
            BlockFacts block,
            BuiltinMaterialClass builtinMaterialClass) {
        public static final int ALPHA_CUT_OVERRIDE = 1;
        public static final int COLLISION_EMPTY = 1 << 1;
        public static final int ANIMATED = 1 << 2;
        public static final int WATER = 1 << 3;
        public static final int FOLIAGE = 1 << 4;
        public static final int MERGEABLE = 1 << 5;
        public static final int RASTER_OVERLAY = 1 << 6;
        private static final int ALL_FLAGS = (1 << 7) - 1;

        public Surface {
            Objects.requireNonNull(layer, "layer");
            Objects.requireNonNull(sprite, "sprite");
            Objects.requireNonNull(builtinMaterialClass, "builtinMaterialClass");
            if ((flags & ~ALL_FLAGS) != 0) {
                throw new IllegalArgumentException("Unknown captured surface flags");
            }
            if (lightEmission < 0 || lightEmission > 15) {
                throw new IllegalArgumentException(
                        "Captured light emission must be in [0, 15]");
            }
        }

        public int color(int vertex) {
            return switch (vertex) {
                case 0 -> this.color0;
                case 1 -> this.color1;
                case 2 -> this.color2;
                case 3 -> this.color3;
                default -> throw new IndexOutOfBoundsException(vertex);
            };
        }

        public boolean alphaCutOverride() { return (this.flags & ALPHA_CUT_OVERRIDE) != 0; }
        public boolean collisionEmpty() { return (this.flags & COLLISION_EMPTY) != 0; }
        public boolean animated() { return (this.flags & ANIMATED) != 0; }
        public boolean water() { return (this.flags & WATER) != 0; }
        public boolean foliage() { return (this.flags & FOLIAGE) != 0; }
        public boolean mergeable() { return (this.flags & MERGEABLE) != 0; }
        public boolean rasterOverlay() { return (this.flags & RASTER_OVERLAY) != 0; }

        public static Surface uniform(
                int color,
                Layer layer,
                int flags,
                int lightEmission,
                CapturedSprite sprite) {
            return new Surface(
                    color,
                    color,
                    color,
                    color,
                    layer,
                    flags,
                    lightEmission,
                    sprite,
                    null,
                    null,
                    BuiltinMaterialClass.DEFAULT);
        }

        public static Surface uniform(
                int color,
                Layer layer,
                int flags,
                int lightEmission,
                CapturedSprite sprite,
                BlockFacts block) {
            return uniform(
                    color,
                    layer,
                    flags,
                    lightEmission,
                    sprite,
                    block,
                    BuiltinMaterialClass.DEFAULT);
        }

        public static Surface uniform(
                int color,
                Layer layer,
                int flags,
                int lightEmission,
                CapturedSprite sprite,
                BlockFacts block,
                BuiltinMaterialClass builtinMaterialClass) {
            return new Surface(
                    color,
                    color,
                    color,
                    color,
                    layer,
                    flags,
                    lightEmission,
                    sprite,
                    null,
                    block,
                    builtinMaterialClass);
        }
    }

    /**
     * Owning world block. A nonzero family joins shape variants of one optical medium, such as a
     * stained-glass block and its pane; zero keeps sprite identity authoritative.
     */
    public record BlockFacts(int x, int y, int z, int mediumFamily) {
        public BlockFacts(int x, int y, int z) {
            this(x, y, z, 0);
        }

        public BlockFacts {
            if (mediumFamily < 0) {
                throw new IllegalArgumentException("Block medium family must not be negative");
            }
        }
    }

    /** Raster-layer fact translated to physical material semantics only at cluster scope. */
    public enum Layer {
        OPAQUE,
        CUTOUT,
        TRANSLUCENT
    }

    /** World-query facts needed to translate one raw fluid quad without retaining the world. */
    public record FluidFacts(
            int localX,
            int localY,
            int localZ,
            int occlusionMask) {
        public FluidFacts {
            if (localX < 0 || localX > 15
                    || localY < 0 || localY > 15
                    || localZ < 0 || localZ > 15) {
                throw new IllegalArgumentException(
                        "Captured fluid owner must use Section-local block coordinates");
            }
            if ((occlusionMask & ~0x3f) != 0) {
                throw new IllegalArgumentException(
                        "Captured fluid occlusion mask exceeds six directions");
            }
        }

        public boolean occluded(int directionOrdinal) {
            if (directionOrdinal < 0 || directionOrdinal >= 6) {
                throw new IndexOutOfBoundsException(directionOrdinal);
            }
            return (this.occlusionMask & 1 << directionOrdinal) != 0;
        }
    }

    /** Single-use, thread-confined builder owned by one capture scope. */
    public static final class Builder {
        private final ArrayList<Quad> quads = new ArrayList<>();
        private boolean built;

        public void add(MutableQuad quad, Surface surface) {
            this.add(quad, surface, false);
        }

        public void addPeer(MutableQuad quad, Surface surface) {
            this.add(quad, surface, true);
        }

        private void add(MutableQuad quad, Surface surface, boolean peerOnly) {
            if (this.built) {
                throw new IllegalStateException("Captured Section was already built");
            }
            this.quads.add(new Quad(
                    Objects.requireNonNull(quad, "quad"),
                    Objects.requireNonNull(surface, "surface"),
                    peerOnly));
        }

        public CapturedSectionGeometry build() {
            if (this.built) {
                throw new IllegalStateException("Captured Section was already built");
            }
            this.built = true;
            return new CapturedSectionGeometry(this.quads);
        }
    }
}
