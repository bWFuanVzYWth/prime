// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

package dev.prime.render.terrain;

import dev.prime.render.scene.CapturedSectionGeometry;
import java.util.Objects;

/** Closed CPU representation of the surface topologies supported by the packed GPU ABI. */
sealed interface SurfaceDefinition permits
        SurfaceDefinition.Single,
        SurfaceDefinition.Overlay,
        SurfaceDefinition.Bilateral,
        SurfaceDefinition.Boundary {
    enum InterfaceMode {
        SINGLE,
        OVERLAY,
        BILATERAL,
        BOUNDARY
    }

    record UvMapping(
            float u0,
            float v0,
            float u1,
            float v1,
            float u2,
            float v2,
            float u3,
            float v3) {
        static UvMapping of(CapturedSectionGeometry.Quad quad) {
            Objects.requireNonNull(quad, "quad");
            return new UvMapping(
                    quad.u(0), quad.v(0),
                    quad.u(1), quad.v(1),
                    quad.u(2), quad.v(2),
                    quad.u(3), quad.v(3));
        }

        float u(int vertex) {
            return switch (vertex) {
                case 0 -> this.u0;
                case 1 -> this.u1;
                case 2 -> this.u2;
                case 3 -> this.u3;
                default -> throw new IndexOutOfBoundsException(vertex);
            };
        }

        float v(int vertex) {
            return switch (vertex) {
                case 0 -> this.v0;
                case 1 -> this.v1;
                case 2 -> this.v2;
                case 3 -> this.v3;
                default -> throw new IndexOutOfBoundsException(vertex);
            };
        }
    }

    record MaterialBinding(
            CapturedSectionGeometry.Surface surface,
            UvMapping uv,
            TransmissiveTopology transmissiveTopology) {
        public MaterialBinding {
            Objects.requireNonNull(surface, "surface");
            Objects.requireNonNull(uv, "uv");
            Objects.requireNonNull(transmissiveTopology, "transmissiveTopology");
        }

        static MaterialBinding of(
                CapturedSectionGeometry.Quad quad,
                TransmissiveTopology topology) {
            return new MaterialBinding(quad.surface(), UvMapping.of(quad), topology);
        }
    }

    record MediumEndpoint(
            CapturedSectionGeometry.Surface surface,
            float referenceU,
            float referenceV,
            TransmissiveTopology transmissiveTopology) {
        public MediumEndpoint {
            Objects.requireNonNull(surface, "surface");
            Objects.requireNonNull(transmissiveTopology, "transmissiveTopology");
            if (!Float.isFinite(referenceU) || !Float.isFinite(referenceV)) {
                throw new IllegalArgumentException("Medium reference UV must be finite");
            }
        }
    }

    /** A solid SINGLE declares a vacuum exterior; it never means an unresolved outside medium. */
    record Single(MaterialBinding primary) implements SurfaceDefinition {
        public Single {
            Objects.requireNonNull(primary, "primary");
        }

        @Override
        public InterfaceMode interfaceMode() {
            return InterfaceMode.SINGLE;
        }
    }

    record Overlay(
            MaterialBinding primary,
            MaterialBinding secondary,
            boolean positiveOnly) implements SurfaceDefinition {
        public Overlay {
            Objects.requireNonNull(primary, "primary");
            Objects.requireNonNull(secondary, "secondary");
        }

        @Override
        public InterfaceMode interfaceMode() {
            return InterfaceMode.OVERLAY;
        }

    }

    record Bilateral(
            MaterialBinding primary,
            MaterialBinding secondary) implements SurfaceDefinition {
        public Bilateral {
            Objects.requireNonNull(primary, "primary");
            Objects.requireNonNull(secondary, "secondary");
        }

        @Override
        public InterfaceMode interfaceMode() {
            return InterfaceMode.BILATERAL;
        }
    }

    record Boundary(
            MaterialBinding primary,
            MediumEndpoint positiveMedium,
            MediumEndpoint negativeMedium) implements SurfaceDefinition {
        public Boundary {
            Objects.requireNonNull(primary, "primary");
            Objects.requireNonNull(positiveMedium, "positiveMedium");
            Objects.requireNonNull(negativeMedium, "negativeMedium");
            if (primary.transmissiveTopology() != TransmissiveTopology.SOLID
                    || positiveMedium.transmissiveTopology() != TransmissiveTopology.SOLID
                    || negativeMedium.transmissiveTopology() != TransmissiveTopology.SOLID
                    || !primary.surface().equals(negativeMedium.surface())) {
                throw new IllegalArgumentException(
                        "A medium boundary requires two solid endpoints and a matching negative substrate");
            }
        }

        @Override
        public InterfaceMode interfaceMode() {
            return InterfaceMode.BOUNDARY;
        }
    }

    static SurfaceDefinition single(MaterialBinding material) {
        return new Single(material);
    }

    static SurfaceDefinition bilateral(
            MaterialBinding positive,
            MaterialBinding negative) {
        return new Bilateral(positive, negative);
    }

    static SurfaceDefinition overlay(
            MaterialBinding overlay,
            MaterialBinding substrate,
            boolean positiveOnly) {
        return new Overlay(overlay, substrate, positiveOnly);
    }

    static SurfaceDefinition boundary(
            MaterialBinding primary,
            MediumEndpoint positiveMedium,
            MediumEndpoint negativeMedium) {
        return new Boundary(primary, positiveMedium, negativeMedium);
    }

    MaterialBinding primary();

    InterfaceMode interfaceMode();
}
