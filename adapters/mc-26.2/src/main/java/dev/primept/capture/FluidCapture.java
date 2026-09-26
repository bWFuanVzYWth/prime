package dev.primept.capture;

import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.block.FluidRenderer;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;

/** Observes a fluid tessellation once, including Fabric's nested default handler. */
public final class FluidCapture implements AutoCloseable, FluidRenderer.Output {
    private static final ThreadLocal<FluidCapture> ACTIVE = new ThreadLocal<>();
    private final FluidCapture previous;
    private final TerrainCapture terrain;
    private final FluidRenderer.Output delegate;
    private final Sink[] sinks = new Sink[ChunkSectionLayer.values().length];
    private int tint = -1;
    private boolean vanillaVertex, closed;

    private FluidCapture(TerrainCapture terrain, FluidRenderer.Output output) {
        this.terrain = terrain;
        delegate = output;
        previous = ACTIVE.get();
        if (previous != null)
            previous.flush();
        ACTIVE.set(this);
    }

    public static FluidCapture open(FluidRenderer.Output output) {
        var terrain = TerrainCapture.current();
        return terrain == null ? null : new FluidCapture(terrain, output);
    }

    public static void observedTint(int color) {
        var scope = ACTIVE.get();
        if (scope != null)
            scope.tint = color;
    }

    /** The vanilla vertex helper receives cardinal-shaded color; custom emitters supply source color. */
    public static void vanillaVertex(boolean active) {
        var scope = ACTIVE.get();
        if (scope != null)
            scope.vanillaVertex = active;
    }

    @Override
    public VertexConsumer getBuilder(ChunkSectionLayer layer) {
        Sink sink = sinks[layer.ordinal()];
        if (sink == null) {
            VertexConsumer original = delegate.getBuilder(layer);
            // Fabric may call vanilla tesselate recursively with the same Output. Never capture twice.
            while (original instanceof Sink wrapped)
                original = wrapped.delegate;
            sink = sinks[layer.ordinal()] = new Sink(original, layer);
        }
        return sink;
    }

    private void flush() {
        for (Sink sink : sinks)
            if (sink != null)
                sink.flush();
    }

    @Override
    public void close() {
        if (closed)
            return;
        closed = true;
        try {
            flush();
            for (Sink sink : sinks)
                if (sink != null && sink.vertices % 4 != 0)
                    terrain.failed(
                            new IllegalStateException("Fluid renderer emitted an incomplete quad"));
        } finally {
            if (previous == null)
                ACTIVE.remove();
            else
                ACTIVE.set(previous);
        }
    }

    private final class Sink implements VertexConsumer {
        private final VertexConsumer delegate;
        private final ChunkSectionLayer layer;
        private float x, y, z, u, v;
        private int color, attributes, vertices;

        private Sink(VertexConsumer delegate, ChunkSectionLayer layer) {
            this.delegate = delegate;
            this.layer = layer;
        }

        private void flush() {
            if (attributes == 0)
                return;
            if (attributes != 7)
                terrain.failed(new IllegalStateException(
                        "Fluid source vertex lacks position, color or UV"));
            else {
                terrain.fluidVertex(layer, x, y, z, color, u, v);
                ++vertices;
            }
            attributes = 0;
        }

        @Override
        public void addVertex(float x, float y, float z, int color, float u, float v, int overlay,
                              int light, float nx, float ny, float nz) {
            flush();
            terrain.fluidVertex(layer, x, y, z, vanillaVertex ? tint : color, u, v);
            ++vertices;
            delegate.addVertex(x, y, z, color, u, v, overlay, light, nx, ny, nz);
        }

        @Override
        public VertexConsumer addVertex(float x, float y, float z) {
            flush();
            this.x = x;
            this.y = y;
            this.z = z;
            attributes = 1;
            delegate.addVertex(x, y, z);
            return this;
        }

        @Override
        public VertexConsumer setColor(int color) {
            this.color = vanillaVertex ? tint : color;
            attributes |= 2;
            delegate.setColor(color);
            return this;
        }

        @Override
        public VertexConsumer setColor(int r, int g, int b, int a) {
            color = vanillaVertex ? tint : (a << 24 | r << 16 | g << 8 | b);
            attributes |= 2;
            delegate.setColor(r, g, b, a);
            return this;
        }

        @Override
        public VertexConsumer setUv(float u, float v) {
            this.u = u;
            this.v = v;
            attributes |= 4;
            delegate.setUv(u, v);
            return this;
        }

        @Override
        public VertexConsumer setUv1(int u, int v) {
            delegate.setUv1(u, v);
            return this;
        }
        @Override
        public VertexConsumer setUv2(int u, int v) {
            delegate.setUv2(u, v);
            return this;
        }
        @Override
        public VertexConsumer setNormal(float x, float y, float z) {
            delegate.setNormal(x, y, z);
            return this;
        }
        @Override
        public VertexConsumer setLineWidth(float width) {
            delegate.setLineWidth(width);
            return this;
        }
    }
}
