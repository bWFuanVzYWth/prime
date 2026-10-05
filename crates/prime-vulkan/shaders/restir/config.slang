// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.
#language slang 2026
module "restir/config.slang";
import "restir/rng.slang";
import "restir/reservoir.slang";

// RA-015, docs/restir-adaptations.md: uniform point/Hybrid options, not shader variants.
// Scalars in shift/validation are already in shader units; the host divides distance by 100.
public struct RestirConfig {
    public uint4 reuse =
            uint4(20, 3, 1, 1); // history M cap, paired neighbors, spatial rounds, fresh paths
    public uint4 features = uint4(0); // stochastic donor, duplication cap, decoupled color, debug
    public uint4 pairedSizes = uint4(254, 232, 196, 0);
    public uint4 queue =
            uint4(0, 3, 65536, 0); // fifth size, replay stride, table stride, aux readiness
    public float4 shift = float4(0.0002, 0.2, 0.2,
                                 0); // distance mean/relative sigma, roughness mean/relative sigma
    public float4 validation =
            float4(0.5, 0.1, 0.1, 0); // normal dot, relative depth, duplication exponent, padding
};
public uint restirStreamCount(RestirConfig config) {
    return config.reuse.w + config.reuse.z + 2u;
}
public uint restirTemporalStream(RestirConfig config) {
    return config.reuse.w;
}
public uint restirTemporalSurvival(RestirConfig config, bool selectedCanonical, uint previousAge) {
    return selectedCanonical ? 0u : ((config.queue.w & 2u) != 0u ? previousAge : 0u) + 1u;
}
public void restirFinalizeFresh(inout PathReservoir aggregate, uint count) {
    aggregate.finalizeReservoirWeight();
    aggregate.weight /= float(count);
    aggregate.M = 1.0;
}
public uint restirSpatialStream(RestirConfig config, uint round) {
    return config.reuse.w + 2u + round;
}
public uint restirPairSize(RestirConfig config, uint neighbor) {
    return neighbor < 4u ? config.pairedSizes[neighbor] : config.queue.x;
}
public float restirDuplicateMCap(RestirConfig config, uint duplicateCount) {
    float history = float(config.reuse.x);
    if (config.features.y == 0u || (config.queue.w & 4u) == 0u)
        return history;
    // RA-015: the exposed zero exponent means one, including zero duplicates; GLSL Pow
    // does not define 0^0. The default positive exponent keeps the original operation.
    float correction = config.validation.z == 0.0
                               ? 1.0
                               : pow(float(duplicateCount) / 288.0, config.validation.z);
    return lerp(history, 1.0, correction);
}
public void restirConfigThresholds(RestirConfig config, inout SampleGenerator sg,
                                   out float distance, out float roughness) {
    distance = config.shift.y == 0.0 ? config.shift.x
                                     : PathRISState::gaussrand(config.shift.x, config.shift.y, sg);
    roughness = config.shift.w == 0.0 ? config.shift.z
                                      : PathRISState::gaussrand(config.shift.z, config.shift.w, sg);
}
// The donor chart includes the pixel-center half. Stochastic reprojection replaces that
// half with U[0,1)^2; source replay still uses the accepted camera's actual jitter.
public float2 restirStochasticLocation(float2 location, RestirConfig config,
                                       inout SampleGenerator sg) {
    return config.features.x != 0u ? location - 0.5 + sampleNext2D(sg) : location;
}
