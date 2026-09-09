// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

package dev.prime.render.diagnostic;

import java.util.Objects;

/** One valid image-diagnostic domain; selecting a non-off view clears the other two domains. */
public record ImageDiagnosticSelection(
        RendererImageView renderer,
        RrInputView rr,
        NrdInputView nrd) {
    public ImageDiagnosticSelection {
        renderer = Objects.requireNonNull(renderer, "renderer");
        rr = Objects.requireNonNull(rr, "rr");
        nrd = Objects.requireNonNull(nrd, "nrd");
        int activeDomains = (renderer.active() ? 1 : 0)
                + (rr.active() ? 1 : 0)
                + (nrd.active() ? 1 : 0);
        if (activeDomains > 1) {
            throw new IllegalArgumentException("Image diagnostic domains are mutually exclusive");
        }
    }

    public static ImageDiagnosticSelection off() {
        return new ImageDiagnosticSelection(
                RendererImageView.OFF, RrInputView.OFF, NrdInputView.OFF);
    }

    public boolean active() {
        return this.renderer.active() || this.rr.active() || this.nrd.active();
    }

    public boolean grid() {
        return this.renderer.grid() || this.rr.grid() || this.nrd.grid();
    }

    public ImageDiagnosticSelection withRenderer(RendererImageView value) {
        Objects.requireNonNull(value, "value");
        if (value == this.renderer) return this;
        return value.active()
                ? new ImageDiagnosticSelection(value, RrInputView.OFF, NrdInputView.OFF)
                : off();
    }

    public ImageDiagnosticSelection withRr(RrInputView value) {
        Objects.requireNonNull(value, "value");
        if (value == this.rr) return this;
        return value.active()
                ? new ImageDiagnosticSelection(RendererImageView.OFF, value, NrdInputView.OFF)
                : off();
    }

    public ImageDiagnosticSelection withNrd(NrdInputView value) {
        Objects.requireNonNull(value, "value");
        if (value == this.nrd) return this;
        return value.active()
                ? new ImageDiagnosticSelection(RendererImageView.OFF, RrInputView.OFF, value)
                : off();
    }
}
