// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

package dev.prime.render;

import dev.prime.render.post.PostProcessingMode;
import dev.prime.render.post.ReconstructionQualityMode;
import dev.prime.render.terrain.TerrainWorkerSettings;
import dev.prime.render.terrain.VoxelSurfaceSettings;
import java.util.Objects;

/** Immutable renderer configuration captured once at the client frame boundary. */
public record RendererSettings(
        boolean pathTracingEnabled,
        RealtimeRenderMode realtimeRenderMode,
        SurfaceDetailMode surfaceDetailMode,
        int voxelTextureSurfaceStrengthSteps,
        PostProcessingMode postProcessingMode,
        ReconstructionQualityMode reconstructionQuality,
        AstronomySettings astronomy,
        LightingSettings.Snapshot lighting,
        MaterialSettings.Snapshot material,
        DisplaySettings.Snapshot display,
        int additionalSpecularBounces,
        int minimumBounces,
        int maximumBounces,
        int terrainWorkerPercentage,
        long revision) {
    public RendererSettings {
        realtimeRenderMode = Objects.requireNonNull(realtimeRenderMode, "realtimeRenderMode");
        postProcessingMode = Objects.requireNonNull(postProcessingMode, "postProcessingMode");
        reconstructionQuality = Objects.requireNonNull(
                reconstructionQuality, "reconstructionQuality");
        astronomy = Objects.requireNonNull(astronomy, "astronomy");
        lighting = Objects.requireNonNull(lighting, "lighting");
        material = Objects.requireNonNull(material, "material");
        display = Objects.requireNonNull(display, "display");
        surfaceDetailMode = Objects.requireNonNull(surfaceDetailMode, "surfaceDetailMode");
        VoxelSurfaceSettings.maximumHeight(voxelTextureSurfaceStrengthSteps);
        BounceSettings.validateCount(additionalSpecularBounces);
        BounceSettings.validateFixedCount(minimumBounces);
        BounceSettings.validateCount(maximumBounces);
        TerrainWorkerSettings.validatePercentage(terrainWorkerPercentage);
        if (revision < 0L) {
            throw new IllegalArgumentException("Renderer settings revision must not be negative");
        }
    }

    public boolean usesResourceNormals() {
        return this.surfaceDetailMode.usesResourceNormals();
    }

    public boolean usesGeometryDisplacement() {
        return this.surfaceDetailMode.usesGeometryDisplacement();
    }
}
