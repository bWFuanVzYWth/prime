package dev.prime.render.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.prime.render.AstronomySettings;
import dev.prime.render.DisplaySettings;
import dev.prime.render.LightingSettings;
import dev.prime.render.MaterialSettings;
import dev.prime.render.RendererSettings;
import dev.prime.render.SurfaceDetailMode;
import dev.prime.render.post.PostProcessingMode;
import dev.prime.render.post.ReconstructionQualityMode;
import dev.prime.render.terrain.VoxelSurfaceSettings;
import org.junit.jupiter.api.Test;

final class RendererLifecycleTest {
    @Test
    void failureIsOwnedByTheLifecycleAndInactiveReloadIsAlreadyReady() {
        RendererLifecycle lifecycle = new RendererLifecycle();
        assertEquals(RuntimeState.UNAVAILABLE, lifecycle.state());
        assertTrue(lifecycle.beginResourceReload().ready().isDone());

        lifecycle.fail(new IllegalStateException("host rejected frame"));

        assertEquals(RuntimeState.FAILED, lifecycle.state());
        assertEquals("IllegalStateException: host rejected frame", lifecycle.failureReason());
    }

    @Test
    void initializationIsSingleShotAndDisabledConfigurationRemainsDisabledWithoutAWorld() {
        RendererLifecycle lifecycle = new RendererLifecycle();

        assertThrows(NullPointerException.class, () -> lifecycle.initialize(null));
        assertFalse(lifecycle.initialized());

        lifecycle.initialize(settings(false));

        assertTrue(lifecycle.initialized());
        assertEquals(RuntimeState.DISABLED, lifecycle.state());
        lifecycle.observeWorld(null, true);
        assertEquals(RuntimeState.DISABLED, lifecycle.state());
        assertThrows(IllegalStateException.class, () -> lifecycle.initialize(settings(true)));
        lifecycle.shutdown();
        assertEquals(RuntimeState.UNAVAILABLE, lifecycle.state());
        assertNull(lifecycle.renderer());
    }

    @Test
    void enabledInitializationKeepsUnavailableStateUntilVulkanBootstrapSucceeds() {
        RendererLifecycle lifecycle = new RendererLifecycle();

        lifecycle.initialize(settings(true));

        assertTrue(lifecycle.initialized());
        assertEquals(RuntimeState.UNAVAILABLE, lifecycle.state());
        assertFalse(lifecycle.finishResourceReload(
                lifecycle.beginResourceReload(), null, true));
        lifecycle.abortResourceReload(lifecycle.beginResourceReload());
        lifecycle.retireFailed(null, null);
        lifecycle.shutdown();
        lifecycle.shutdown();
        assertEquals(RuntimeState.UNAVAILABLE, lifecycle.state());
    }

    @Test
    void failureReasonRejectsNullAndShutdownClearsTheStickyRuntimeState() {
        RendererLifecycle lifecycle = new RendererLifecycle();
        assertThrows(NullPointerException.class, () -> lifecycle.setFailureReason(null));

        lifecycle.fail(new IllegalArgumentException("bad frame"));
        lifecycle.observeWorld(null, true);

        assertEquals(RuntimeState.FAILED, lifecycle.state());
        lifecycle.shutdown();
        assertEquals(RuntimeState.UNAVAILABLE, lifecycle.state());
    }

    private static RendererSettings settings(boolean enabled) {
        return new RendererSettings(
                enabled,
                SurfaceDetailMode.DEFAULT,
                VoxelSurfaceSettings.DEFAULT_STEPS,
                PostProcessingMode.DISABLED,
                ReconstructionQualityMode.DEFAULT,
                AstronomySettings.defaults(),
                new LightingSettings.Snapshot(
                        LightingSettings.DEFAULT_SUN_QUARTER_STEPS,
                        LightingSettings.DEFAULT_STAR_QUARTER_STEPS,
                        LightingSettings.DEFAULT_BLOCK_LIGHT_QUARTER_STEPS,
                        0L),
                new MaterialSettings.Snapshot(
                        MaterialSettings.DEFAULT_ROUGHNESS_STEPS,
                        0L),
                new DisplaySettings.Snapshot(
                        DisplaySettings.DEFAULT_FINAL_EXPOSURE_QUARTER_STEPS,
                        DisplaySettings.DEFAULT_AUTO_EXPOSURE_COMPENSATION_STEPS),
                0L);
    }
}
