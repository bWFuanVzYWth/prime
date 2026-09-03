package dev.prime.render.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.prime.render.diagnostic.ImageDiagnosticSelection;
import dev.prime.render.diagnostic.NrdInputView;
import dev.prime.render.diagnostic.RendererImageView;
import dev.prime.render.diagnostic.RrInputView;
import dev.prime.render.diagnostic.RrResponsivity;
import org.junit.jupiter.api.Test;

final class SessionControlsTest {
    @Test
    void defaultsAndExplicitSnapshotsAreCoherent() {
        SessionControls defaults = SessionControls.defaults();
        SessionControls changed = new SessionControls(
                true,
                true,
                true,
                new ImageDiagnosticSelection(
                        RendererImageView.NORMAL, RrInputView.OFF, NrdInputView.OFF),
                0.25F);

        assertFalse(defaults.screenshotRequested());
        assertFalse(defaults.rendererDiagnostics());
        assertFalse(defaults.rawOutput());
        assertEquals(RendererImageView.OFF, defaults.imageDiagnostics().renderer());
        assertEquals(RrInputView.OFF, defaults.imageDiagnostics().rr());
        assertEquals(NrdInputView.OFF, defaults.imageDiagnostics().nrd());
        assertEquals(RrResponsivity.DEFAULT, defaults.rrResponsivity());
        assertTrue(changed.screenshotRequested());
        assertTrue(changed.rendererDiagnostics());
        assertTrue(changed.rawOutput());
        assertEquals(RendererImageView.NORMAL, changed.imageDiagnostics().renderer());
        assertEquals(0.25F, changed.rrResponsivity());
    }

    @Test
    void selectingOneImageDomainDisablesTheOtherTwo() {
        ImageDiagnosticSelection controls = ImageDiagnosticSelection.off()
                .withRenderer(RendererImageView.GRID)
                .withRr(RrInputView.MOTION);

        assertEquals(RendererImageView.OFF, controls.renderer());
        assertEquals(RrInputView.MOTION, controls.rr());
        assertEquals(NrdInputView.OFF, controls.nrd());

        controls = controls.withNrd(NrdInputView.PRIMARY_NORMAL_ROUGHNESS);
        assertEquals(RendererImageView.OFF, controls.renderer());
        assertEquals(RrInputView.OFF, controls.rr());
        assertEquals(
                NrdInputView.PRIMARY_NORMAL_ROUGHNESS,
                controls.nrd());
    }
}
