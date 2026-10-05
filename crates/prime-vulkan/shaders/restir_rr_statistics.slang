// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.
#language slang 2026
module restir_rr_statistics;
import "restir/parameters.slang";
import "restir/indexing.slang";
import "restir/reservoir.slang";
import "restir/rr_decorrelation.slang";

// RA-016, docs/restir-adaptations.md: auxiliary output statistics only. No ray
// queries and no writes to the reusable reservoir/age banks. The age source is
// Falcor's current pixel statistic, not RTXDI's selected-neighbor age convention.
[[vk::binding(23, 0)]] ConstantBuffer<RestirParameters> parameters;

groupshared float rrGroupEnergy[64];
groupshared float rrMeanEnergy;

[numthreads(8, 8, 1)] void main(uint3 id : SV_DispatchThreadID, uint3 lane : SV_GroupThreadID) {
    RestirRrDecorrelationConfig config = parameters.rrDecorrelation;
    // Uniform host/frame conditions: every lane follows the same early return.
    if (!restirRrOutputActive(config, parameters.control.y != 0u))
        return;
    uint2 extent = parameters.frame.dimensions.xy;
    bool inBounds = all(id.xy < extent);
    uint offset = 0u;
    if (inBounds)
        offset = restirOffset(id.xy, extent);

    if (config.control.w != 0u) {
        float energy = 0.0;
        if (inBounds) {
            float estimate;
            if (parameters.config.features.z != 0u && parameters.config.reuse.z != 0u) {
                // Decoupled shading outputs its conditional mean; classify that
                // actual estimator, not the separate reusable RIS winner.
                estimate = PathRISState::toScalar(parameters.shadingSum[offset].rgb);
            } else {
                PathReservoir reservoir = restirFinalReservoirs(parameters)[offset];
                estimate = PathRISState::toScalar(reservoir.integrand) * reservoir.weight;
            }
            energy = isfinite(estimate) ? max(0.0, estimate) : 0.0;
        }
        uint groupLane = lane.y * 8u + lane.x;
        rrGroupEnergy[groupLane] = energy;
        GroupMemoryBarrierWithGroupSync();
        // Prime's reduction does not assume a subgroup width or lane mapping.
        // Its sum order differs from a wave-based reduction near f32 thresholds.
        if (groupLane == 0u) {
            float total = 0.0;
            uint contributors = 0u;
            for (uint i = 0u; i < 64u; ++i) {
                float value = rrGroupEnergy[i];
                total += value;
                contributors += uint(value > 0.0);
            }
            rrMeanEnergy = contributors != 0u ? total / float(contributors) : 0.0;
        }
        GroupMemoryBarrierWithGroupSync();
        if (inBounds)
            parameters.rrFireflies[offset] =
                    uint(energy > rrMeanEnergy * restirRrFireflyMultiplier(config.curve.w));
    }

    // Bounds return occurs after every possible full-group barrier.
    if (!inBounds || config.control.y != RESTIR_RR_DECORRELATION_STAGNANCY)
        return;
    float current = restirRrStagnancy(parameters.currentAges[offset]);
    int2 previousPixel = parameters.reprojection[offset];
    bool previousValid = (config.control.x & RESTIR_RR_PREVIOUS_STATISTICS_VALID) != 0u &&
                         restirInBounds(previousPixel, extent);
    float totalPrevious = 0.0;
    uint previousCount = 0u;
    if (previousValid) {
        // Public à-trous box definition: 5x5, step=2, normalize valid taps only.
        for (int y = -2; y <= 2; ++y) {
            for (int x = -2; x <= 2; ++x) {
                int2 tap = previousPixel + int2(x, y) * 2;
                if (restirInBounds(tap, extent)) {
                    totalPrevious +=
                            parameters.rrSmoothedPrevious[restirOffset(uint2(tap), extent)];
                    ++previousCount;
                }
            }
        }
    }
    float previousAverage = previousCount != 0u ? totalPrevious / float(previousCount) : current;
    parameters.rrSmoothedCurrent[offset] =
            restirRrSmoothedStagnancy(current, previousAverage, previousValid, config.curve.z);
}
