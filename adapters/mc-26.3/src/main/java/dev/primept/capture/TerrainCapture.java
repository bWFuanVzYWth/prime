package dev.primept.capture;

import dev.primept.PrimeClient;
import java.util.concurrent.atomic.AtomicLong;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.MutableQuadView;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.QuadAtlas;
import net.minecraft.client.model.geom.builders.UVPair;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.util.ARGB;
import net.minecraft.world.level.block.state.BlockState;

/** Observes an actual MC compile; never repeats a model, random, visibility, or tint callback. */
public final class TerrainCapture implements AutoCloseable {
    private static final ThreadLocal<TerrainCapture> ACTIVE = new ThreadLocal<>();
    private static final boolean AUDIT = Boolean.getBoolean("primept.capture.audit");
    private static final AtomicLong SECTIONS = new AtomicLong();
    private static final AtomicLong VANILLA = new AtomicLong(), FABRIC = new AtomicLong();
    private static final AtomicLong TINTS = new AtomicLong(), SHADED = new AtomicLong();
    private static final AtomicLong FAST_LEAF_CUTOUT = new AtomicLong();
    private final TerrainCapture previous;
    private final CaptureInbox inbox;
    private final CaptureInbox.Token token;
    private final SourceQuads source;
    private final boolean cutoutLeaves;
    private final int[] fabricColors = new int[4];
    private int fabricTintIndex, observedTint;
    private boolean tintObserved, fabricPending, fabricVanillaForceOpaque;
    private long vanillaTintPosition;
    private int vanillaTintIndex;
    private RuntimeException failure;
    private int vanillaQuads, fabricQuads, fluidVertices, tintCalls, shadedQuads,
            fastLeafCutoutQuads;
    private boolean closed;

    private TerrainCapture(CaptureInbox inbox, SectionPos section, boolean cutoutLeaves) {
        this.inbox = inbox;
        token = inbox.begin(section);
        source = token == null ? null : new SourceQuads();
        this.cutoutLeaves = cutoutLeaves;
        previous = ACTIVE.get();
        ACTIVE.set(this);
    }

    public static TerrainCapture open(CaptureInbox inbox, SectionPos section,
                                      boolean cutoutLeaves) {
        return new TerrainCapture(inbox, section, cutoutLeaves);
    }

    static TerrainCapture current() {
        TerrainCapture scope = ACTIVE.get();
        return scope == null || scope.token == null || scope.failure != null ? null : scope;
    }

    public static void beginVanilla(BlockPos position, int tintIndex) {
        TerrainCapture scope = current();
        if (scope == null)
            return;
        scope.vanillaTintPosition = position.asLong();
        scope.vanillaTintIndex = tintIndex;
        scope.tintObserved = false;
    }

    public static void vanillaTint(BlockPos position, int tintIndex, int tint) {
        TerrainCapture scope = current();
        if (scope == null)
            return;
        if (scope.vanillaTintPosition != position.asLong() || scope.vanillaTintIndex != tintIndex) {
            scope.failure = new IllegalStateException(
                    "Observed vanilla tint does not belong to the pending quad");
            return;
        }
        scope.observedTint = tint;
        scope.tintObserved = true;
        scope.tintCalls++;
    }

    public static void vanillaQuad(float x, float y, float z, BlockState state, BakedQuad quad) {
        TerrainCapture scope = current();
        if (scope == null)
            return;
        try {
            int layer = scope.layer(ModelBlockRenderer.forceOpaque(scope.cutoutLeaves, state)
                                            ? ChunkSectionLayer.SOLID
                                            : quad.materialInfo().layer());
            if (layer < 0)
                return;
            int tintIndex = quad.materialInfo().tintIndex();
            if (tintIndex != -1 && (!scope.tintObserved || tintIndex != scope.vanillaTintIndex))
                throw new IllegalStateException(
                        "Accepted vanilla quad has no observed source tint");
            int color = tintIndex == -1 ? -1 : scope.observedTint;
            for (int vertex = 0; vertex < 4; vertex++) {
                var position = quad.position(vertex);
                long uv = quad.packedUV(vertex);
                scope.source.vertex(layer, x + position.x(), y + position.y(), z + position.z(),
                                    color, UVPair.unpackU(uv), UVPair.unpackV(uv));
            }
            scope.vanillaQuads++;
        } catch (RuntimeException exception) {
            scope.failure = exception;
        }
    }

    public static void beginFabricBlock(BlockState state) {
        if (!AUDIT)
            return;
        TerrainCapture scope = current();
        if (scope != null)
            scope.fabricVanillaForceOpaque =
                    ModelBlockRenderer.forceOpaque(scope.cutoutLeaves, state);
    }

    public static void beginFabricQuad(MutableQuadView quad) {
        TerrainCapture scope = current();
        if (scope == null)
            return;
        try {
            if (scope.fabricPending)
                throw new IllegalStateException("Nested Indigo quad capture");
            for (int vertex = 0; vertex < 4; vertex++)
                scope.fabricColors[vertex] = quad.color(vertex);
            scope.fabricTintIndex = quad.tintIndex();
            scope.tintObserved = false;
            scope.fabricPending = true;
        } catch (RuntimeException exception) {
            scope.failure = exception;
        }
    }

    public static void fabricTint(int tint) {
        TerrainCapture scope = current();
        if (scope == null || !scope.fabricPending)
            return;
        scope.observedTint = tint;
        scope.tintObserved = true;
        scope.tintCalls++;
    }

    public static void finishFabricQuad(MutableQuadView quad, boolean accepted) {
        TerrainCapture scope = current();
        if (scope == null)
            return;
        try {
            if (!scope.fabricPending)
                throw new IllegalStateException("Indigo quad capture was not opened");
            scope.fabricPending = false;
            if (!accepted)
                return;
            if (quad.atlas() != QuadAtlas.BLOCK)
                throw new IllegalStateException("Terrain quad uses a non-block atlas");
            // Fabric's SectionCompiler proxy discards vanilla's force-opaque BlockQuadOutput.
            // Its actual emitter buffers quad.chunkLayer(), including leaves with cutoutLeaves=false.
            int layer = scope.layer(quad.chunkLayer());
            if (layer < 0)
                return;
            if (scope.fabricTintIndex != -1 && !scope.tintObserved)
                throw new IllegalStateException("Accepted Indigo quad has no observed source tint");
            boolean shaded = false;
            for (int vertex = 0; vertex < 4; vertex++) {
                // Exactly MC's encoded 8-bit multiply, before any AO/cardinal brightness. No EOTF here.
                int color = scope.fabricTintIndex == -1
                                    ? scope.fabricColors[vertex]
                                    : ARGB.multiply(scope.fabricColors[vertex], scope.observedTint);
                scope.source.vertex(layer, quad.x(vertex), quad.y(vertex), quad.z(vertex), color,
                                    quad.u(vertex), quad.v(vertex));
                if (AUDIT && color != quad.color(vertex))
                    shaded = true;
            }
            scope.fabricQuads++;
            if (AUDIT && scope.fabricVanillaForceOpaque && layer == SourceQuads.CUTOUT)
                scope.fastLeafCutoutQuads++;
            if (shaded)
                scope.shadedQuads++;
        } catch (RuntimeException exception) {
            scope.failure = exception;
        }
    }

    private int layer(ChunkSectionLayer layer) {
        if (layer == ChunkSectionLayer.SOLID)
            return SourceQuads.OPAQUE;
        if (layer == ChunkSectionLayer.CUTOUT)
            return SourceQuads.CUTOUT;
        if (layer == ChunkSectionLayer.TRANSLUCENT)
            return SourceQuads.TRANSLUCENT;
        return -1;
    }

    void fluidVertex(ChunkSectionLayer sourceLayer, float x, float y, float z, int color, float u,
                     float v) {
        if (failure != null)
            return;
        try {
            int layer = layer(sourceLayer);
            if (layer < 0)
                throw new IllegalArgumentException("Unsupported fluid source layer");
            source.vertex(layer, x, y, z, color, u, v);
            ++fluidVertices;
        } catch (RuntimeException exception) {
            failure = exception;
        }
    }

    void failed(RuntimeException exception) {
        if (failure == null)
            failure = exception;
    }

    /** Only the original compiler's successful return can publish this fixed-identity batch. */
    public void publish() {
        if (closed)
            throw new IllegalStateException("Capture scope already closed");
        if (token == null)
            return;
        if (fabricPending && failure == null)
            failure = new IllegalStateException("Unfinished Indigo source quad");
        if (failure != null) {
            inbox.captureFailed(token, failure);
            return;
        }
        inbox.capture(token, source);
        if (vanillaQuads + fabricQuads + fluidVertices == 0)
            return;
        long vanilla = VANILLA.addAndGet(vanillaQuads), fabric = FABRIC.addAndGet(fabricQuads);
        long tints = TINTS.addAndGet(tintCalls), shaded = SHADED.addAndGet(shadedQuads);
        long fastLeaves = FAST_LEAF_CUTOUT.addAndGet(fastLeafCutoutQuads);
        long sections = SECTIONS.incrementAndGet();
        if (sections == 1 || AUDIT && sections % 256 == 0)
            PrimeClient.LOGGER.info(
                    "Prime PT source capture: source=pre-light stride=24 sections={} vanillaQuads={} fabricQuads={} observedTintCalls={} shadedQuadsExcluded={} fastLeafCutoutQuads={} audit={}; opaque/cutout/alpha terrain and fluid sources enabled",
                    sections, vanilla, fabric, tints, shaded, fastLeaves, AUDIT);
    }

    @Override
    public void close() {
        if (closed)
            return;
        closed = true;
        if (previous == null)
            ACTIVE.remove();
        else
            ACTIVE.set(previous);
    }
}
