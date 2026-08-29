package dev.prime.render.scene.vanilla;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

final class DynamicSceneCaptureLifecycleTest {
    @AfterEach
    void clearCapture() {
        if (DynamicSceneCapture.active()) {
            try {
                DynamicSceneCapture.finish();
            } catch (IllegalStateException ignored) {
                // finish removes the failed session before validating its element scope.
            }
        }
    }

    @Test
    void emptyCaptureUsesTheCameraClusterAndPublishesCompatibilityWitnesses() {
        DynamicSceneCapture.begin(new Vec3(-1.0, 65.0, 64.0));
        DynamicSceneCapture.reportCompatibilityIssue(
                DynamicSceneFrame.CompatibilityIssue.CUSTOM_SUBMIT_NODE);

        DynamicSceneFrame frame = DynamicSceneCapture.finish();

        assertFalse(DynamicSceneCapture.active());
        assertTrue(frame.isEmpty());
        assertEquals(-4, frame.clusterX());
        assertEquals(4, frame.clusterY());
        assertEquals(4, frame.clusterZ());
        assertEquals(
                java.util.Set.of(DynamicSceneFrame.CompatibilityIssue.CUSTOM_SUBMIT_NODE),
                frame.compatibilityIssues());
    }

    @Test
    void nextFrameDiscardsAnUnfinishedCaptureInsteadOfInheritingItsState() {
        DynamicSceneCapture.begin(Vec3.ZERO);
        DynamicSceneCapture.reportCompatibilityIssue(
                DynamicSceneFrame.CompatibilityIssue.MISSING_MOTION_IDENTITY);

        DynamicSceneCapture.begin(new Vec3(80.0, 0.0, 0.0));
        DynamicSceneFrame frame = DynamicSceneCapture.finish();

        assertEquals(4, frame.clusterX());
        assertTrue(frame.compatibilityIssues().isEmpty());
    }

    @Test
    void elementScopesRejectNestingOutOfOrderCloseAndIncompleteFinish() {
        DynamicSceneCapture.begin(Vec3.ZERO);
        DynamicSceneCapture.beginElement(VanillaSceneBoundary.Element.ENTITY);

        assertThrows(
                IllegalStateException.class,
                () -> DynamicSceneCapture.beginElement(
                        VanillaSceneBoundary.Element.BLOCK_ENTITY));
        assertThrows(
                IllegalStateException.class,
                () -> DynamicSceneCapture.endElement(
                        VanillaSceneBoundary.Element.PARTICLE));
        assertThrows(IllegalStateException.class, DynamicSceneCapture::finish);
        assertFalse(DynamicSceneCapture.active());
    }

    @Test
    void onlyDynamicWorldElementsMayOpenCaptureScopes() {
        DynamicSceneCapture.begin(Vec3.ZERO);

        assertThrows(
                IllegalArgumentException.class,
                () -> DynamicSceneCapture.beginElement(
                        VanillaSceneBoundary.Element.FEATURE));

        DynamicSceneCapture.beginElement(VanillaSceneBoundary.Element.PARTICLE);
        DynamicSceneCapture.endElement(VanillaSceneBoundary.Element.PARTICLE);
        assertTrue(DynamicSceneCapture.finish().isEmpty());
    }

    @Test
    void inactiveMixinHooksAreNoOpsButFinishStillFailsClearly() {
        DynamicSceneCapture.beginElement(VanillaSceneBoundary.Element.ENTITY);
        DynamicSceneCapture.endElement(VanillaSceneBoundary.Element.ENTITY);
        DynamicSceneCapture.reportCompatibilityIssue(
                DynamicSceneFrame.CompatibilityIssue.CUSTOM_SUBMIT_NODE);

        assertThrows(IllegalStateException.class, DynamicSceneCapture::finish);
    }
}
