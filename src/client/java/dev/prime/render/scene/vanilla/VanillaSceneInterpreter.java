package dev.prime.render.scene.vanilla;

import com.mojang.blaze3d.vertex.VertexSorting;
import dev.prime.infrastructure.ResourceCleanup;
import dev.prime.render.scene.CapturedSectionGeometry;
import java.util.concurrent.ConcurrentLinkedQueue;
import net.minecraft.client.renderer.SectionBufferBuilderPack;
import net.minecraft.client.renderer.chunk.SectionCompiler;
import net.minecraft.client.renderer.chunk.RenderSectionRegion;
import net.minecraft.core.SectionPos;

/**
 * Single geometry authority between Minecraft's rendering front end and Prime's renderer-owned
 * world scene.
 *
 * <p>{@code TerrainStreamer} owns coverage, invalidation, scheduling and lifetime. This module owns
 * only the capture of one {@link RenderSectionRegion} through Minecraft's real Section compiler
 * into immutable accepted-quad facts. The region's block states are copied by
 * Minecraft; live tint/light/entity services remain in the Minecraft adapter boundary rather than
 * becoming interpreter-owned hidden state. It neither consumes completed raster meshes nor observes raster
 * visibility, so there is no multi-source reconciliation policy.
 *
 * <p>Only this module observes vanilla mesh production. Cluster translation and Vulkan code never
 * observe Mixins, block states, model objects, or vanilla vertex interfaces.
 */
public final class VanillaSceneInterpreter implements AutoCloseable {
    private final ConcurrentLinkedQueue<SectionBufferBuilderPack> availableSectionBuffers =
            new ConcurrentLinkedQueue<>();
    // close() runs on the client thread while shared-executor compilers may still return buffers.
    private volatile boolean closed;

    public VanillaSceneInterpreter() {
    }

    public CapturedSectionGeometry compileSection(
            VanillaClusterCompiler.Capture cluster,
            VanillaSectionSnapshot section,
            VanillaSpriteResolver spriteResolver) {
        if (this.closed) {
            throw new IllegalStateException("Vanilla scene interpreter is closed");
        }
        SectionBufferBuilderPack buffers = this.availableSectionBuffers.poll();
        if (buffers == null) {
            buffers = new SectionBufferBuilderPack();
        }
        try {
            // Raster AO and light-map illumination do not affect the captured path-traced
            // geometry, model choice, culling, UVs, fluid surfaces, or render layers.
            SectionCompiler compiler = new SectionCompiler(
                    false,
                    cluster.assets().cutoutLeaves(),
                    cluster.assets().blockModels(),
                    cluster.assets().fluidModels(),
                    cluster.assets().blockColors());
            SectionPos sectionPosition = SectionPos.of(
                    section.sectionX(), section.sectionY(), section.sectionZ());
            boolean completed = false;
            try (VanillaSectionCapture capture = VanillaSectionCapture.open(
                    section.region(),
                    cluster.assets().blockModels(),
                    cluster.assets().blockColors(),
                    cluster.assets().blockSpriteFinder(),
                    spriteResolver,
                    cluster.assets().cutoutLeaves(),
                    section.sectionX(),
                    section.sectionY(),
                    section.sectionZ(),
                    cluster.clusterX,
                    cluster.clusterY,
                    cluster.clusterZ)) {
                SectionCompiler.Results results = compiler.compile(
                        sectionPosition,
                        section.region(),
                        VertexSorting.byDistance(0.0F, 0.0F, 0.0F),
                        buffers);
                try {
                    CapturedSectionGeometry geometry = capture.finish(results);
                    completed = true;
                    return geometry;
                } finally {
                    results.release();
                }
            } finally {
                if (completed) {
                    buffers.clearAll();
                } else {
                    buffers.discardAll();
                }
            }
        } finally {
            if (this.closed) {
                buffers.close();
            } else {
                this.availableSectionBuffers.offer(buffers);
                // close() can win between the first check and offer(). Removing after publication
                // transfers ownership to exactly one side without adding a lock domain.
                if (this.closed && this.availableSectionBuffers.remove(buffers)) {
                    buffers.close();
                }
            }
        }
    }

    @Override
    public void close() {
        this.closed = true;
        RuntimeException failure = null;
        SectionBufferBuilderPack buffers;
        while ((buffers = this.availableSectionBuffers.poll()) != null) {
            SectionBufferBuilderPack retired = buffers;
            failure = ResourceCleanup.run(retired::close, failure);
        }
        ResourceCleanup.throwIfFailed(failure);
    }
}
