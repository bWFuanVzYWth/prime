// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

#language slang 2026
module "math/atmosphere_direction.slang";

// Endpoint samples, with extra resolution around the horizon. Visibility is evaluated separately
// so filtering never leaks sunlight through the planet or changes the visible solar disk.
public float primeAtmosphereDirectionCoordinate(float mu) {
    return 0.5 + 0.5 * sign(mu) * sqrt(abs(clamp(mu, -1.0, 1.0)));
}

public float primeAtmosphereDirectionCosine(float coordinate) {
    float signedCoordinate = 2.0 * coordinate - 1.0;
    return signedCoordinate * abs(signedCoordinate);
}
