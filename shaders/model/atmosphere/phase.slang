// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

#language slang 2026
module "model/atmosphere/phase.slang";

import "model/atmosphere/sky.slang";
import "model/atmosphere/spectrum.slang";



[[vk::binding(7, 0)]] StructuredBuffer<float4> primeAtmospherePhaseLut;

public static const int ATM_PHASE_BIN_COUNT = 1024;
public static const int ATM_PHASE_SPECIES_COUNT = 4;

// The baked table stores samples at bin centres for
// mu = 1 - 2 * ((bin + 0.5) / 1024)^3. Hillaire's phase cosine uses the
// opposite direction convention, hence (1 + phaseCosine) below. The -0.5
// restores the bin-centre convention; linear interpolation is essential near
// the sharply varying forward lobe around the sun.
public float4 atmAerosolPhaseValue(int group, int species, float phaseCosine) {
    float coordinate = pow(
            clamp(0.5 + 0.5 * phaseCosine, 0.0, 1.0),
            1.0 / 3.0);
    float bin = coordinate * float(ATM_PHASE_BIN_COUNT) - 0.5;
    int lowerBin = clamp(int(floor(bin)), 0, ATM_PHASE_BIN_COUNT - 1);
    int upperBin = min(lowerBin + 1, ATM_PHASE_BIN_COUNT - 1);
    float fraction = clamp(bin - float(lowerBin), 0.0, 1.0);
    int groupBase = group * ATM_PHASE_BIN_COUNT;
    float4 lower = primeAtmospherePhaseLut[
            (groupBase + lowerBin) * ATM_PHASE_SPECIES_COUNT + species];
    float4 upper = primeAtmospherePhaseLut[
            (groupBase + upperBin) * ATM_PHASE_SPECIES_COUNT + species];
    return lerp(lower, upper, fraction);
}

public float4 atmAerosolPhaseScattering(
        int group,
        float altitudeKm,
        float phaseCosine) {
    float4 result = float4(0.0);
    for (int species = 0; species < ATM_PHASE_SPECIES_COUNT; ++species) {
        result += atmAerosolScatteringCrossSection(group, species)
                * atmAerosolDensity(species, altitudeKm)
                * atmAerosolPhaseValue(group, species, phaseCosine);
    }
    return result;
}
