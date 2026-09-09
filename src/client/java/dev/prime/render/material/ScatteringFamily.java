// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

package dev.prime.render.material;

/** Closed set of material families stored in the two-bit primitive recipe field. */
public enum ScatteringFamily {
    OPAQUE(0),
    DIELECTRIC_SOLID(1),
    DIELECTRIC_THIN(2),
    FOLIAGE_THIN(3);

    private final int encoded;

    ScatteringFamily(int encoded) {
        this.encoded = encoded;
    }

    public int encoded() {
        return this.encoded;
    }

    public static ScatteringFamily fromEncoded(int encoded) {
        return switch (encoded) {
            case 0 -> OPAQUE;
            case 1 -> DIELECTRIC_SOLID;
            case 2 -> DIELECTRIC_THIN;
            case 3 -> FOLIAGE_THIN;
            default -> throw new IllegalArgumentException("Invalid scattering family");
        };
    }
}
