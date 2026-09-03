package dev.prime.render.scene.vanilla;

import com.mojang.blaze3d.vertex.VertexSorting;
import dev.prime.render.scene.CapturedSectionGeometry;
import net.minecraft.client.renderer.SectionBufferBuilderPack;
import net.minecraft.client.renderer.chunk.SectionCompiler;
import net.minecraft.core.SectionPos;

/**
 * Runs Minecraft's real Section compiler with a renderer-owned semantic side channel.
 *
 * <p>The compiler invocation is Prime's one mesh-production entry point. It deliberately reuses
 * vanilla model selection, face culling, randomization, tint lookup, fluid tessellation, render
 * layers, and Fabric renderer integration, but it is scheduled from Prime's scene rather than from
 * vanilla's visible raster Sections.
 *
 * <p>Raster AO, light-map vertex illumination and translucent index sorting are not captured
 * because the path-traced representation does not consume them. Geometry representation changes,
 * including reverse-fluid-face collapse, happen later in the pure cluster translator.
 */
public final class VanillaSectionMesher {
    private VanillaSectionMesher() {
    }

    public static CapturedSectionGeometry compile(
            VanillaClusterCompiler.Capture cluster,
            VanillaSectionSnapshot snapshot,
            SectionBufferBuilderPack builders,
            VanillaSpriteResolver spriteResolver) {
        // AO and the light map affect only raster vertex illumination. Disabling AO here avoids
        // doing expensive work that the side channel deliberately does not consume; geometry,
        // model selection, culling, UVs, fluid surfaces and render layers still come from vanilla.
        SectionCompiler compiler = new SectionCompiler(
                false,
                cluster.assets().cutoutLeaves(),
                cluster.assets().blockModels(),
                cluster.assets().fluidModels(),
                cluster.assets().blockColors());
        SectionPos section = SectionPos.of(
                snapshot.sectionX(), snapshot.sectionY(), snapshot.sectionZ());
        boolean completed = false;
        try (VanillaSectionCapture capture = VanillaSectionCapture.open(
                snapshot.region(),
                cluster.assets().blockModels(),
                cluster.assets().blockColors(),
                cluster.assets().blockSpriteFinder(),
                spriteResolver,
                cluster.assets().cutoutLeaves(),
                snapshot.sectionX(),
                snapshot.sectionY(),
                snapshot.sectionZ(),
                cluster.clusterX,
                cluster.clusterY,
                cluster.clusterZ)) {
            SectionCompiler.Results results = compiler.compile(
                    section,
                    snapshot.region(),
                    VertexSorting.byDistance(0.0F, 0.0F, 0.0F),
                    builders);
            try {
                CapturedSectionGeometry geometry = capture.finish(results);
                completed = true;
                return geometry;
            } finally {
                results.release();
            }
        } finally {
            if (completed) {
                builders.clearAll();
            } else {
                builders.discardAll();
            }
        }
    }
}
