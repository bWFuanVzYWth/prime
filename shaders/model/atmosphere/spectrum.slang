#language slang 2026
module "model/atmosphere/spectrum.slang";

import "model/atmosphere/sky.slang";

// Eight-wavelength Hillaire-style fit from sky_tracer's reference spectral path tracer.
// Producer-only: consumers receive scene-linear Rec.2020 D65 LUT values.
public struct AtmCoefficients {
    public float4 aerosolScattering;
    public float4 molecularScattering;
    public float4 extinction;
};


public float4 atmMolecularScattering(int group) {
    return group == 0
            ? float4(0.047815718, 0.03604918, 0.025453128, 0.018479607)
            : float4(0.015890262, 0.01193972, 0.009758583, 0.006705125);
}

public float4 atmOzoneCrossSection(int group) {
    return group == 0
            ? float4(2.91e-27, 1.25e-26, 7.11e-26, 1.78e-25)
            : float4(2.88e-25, 4.55e-25, 4.54e-25, 1.67e-25);
}

public float4 atmAerosolScatteringCrossSection(int group, int species) {
    if (group == 0) {
        if (species == 0) return float4(4612.53191, 4233.58582, 3786.26357, 3386.72611);
        if (species == 1) return float4(160.827371, 163.627446, 167.190338, 170.607065);
        if (species == 2) return float4(3680.43254, 3188.87152, 2652.75577, 2216.54595);
        return float4(4198.99534, 4051.87364, 3854.93395, 3659.96019);
    }
    if (species == 0) return float4(3201.76301, 2884.22958, 2666.95770, 2290.42368);
    if (species == 1) return float4(172.270989, 175.440739, 177.757050, 182.217388);
    if (species == 2) return float4(2027.79536, 1712.43763, 1511.68788, 1194.60801);
    return float4(3563.58585, 3374.33138, 3235.18858, 2972.40832);
}

public float4 atmAerosolAbsorptionCrossSection(int group, int species) {
    if (group == 0) {
        if (species == 0) return float4(90.1089989, 82.7180280, 74.4037326, 72.6561267);
        if (species == 1) return float4(70.7582688, 69.1931924, 67.2250799, 65.3678619);
        if (species == 2) return float4(10315.2120, 9565.49528, 8693.68329, 7901.44943);
        return float4(2.15273820e-4, 1.28197361e-4, 8.97120583e-5, 1.13036139e-4);
    }
    if (species == 0) return float4(74.2391720, 70.2021686, 67.9849946, 67.9558970);
    if (species == 1) return float4(64.4747043, 62.7839451, 61.5691712, 59.3296101);
    if (species == 2) return float4(7524.53535, 6899.54758, 6492.91243, 5828.49178);
    return float4(1.41520976e-4, 3.04512124e-4, 4.24030002e-4, 6.10137194e-4);
}

public float atmAerosolBaseDensity(int species) {
    if (species == 0) return 1.49e-5;
    if (species == 1) return 1.01e-5;
    if (species == 2) return 5.31e-7;
    return 0.0;
}

public float atmAerosolBackgroundDensity(int species) {
    if (species == 0) return 4.571e-7;
    if (species == 1) return 2.291e-6;
    if (species == 2) return 1.362e-8;
    return 5.312e-7;
}

public float atmAerosolDensity(int species, float altitudeKm) {
    float altitude = max(altitudeKm, 0.0);
    float falloff = exp(-altitude / 8.0);
    return lerp(
            atmAerosolBaseDensity(species) * falloff,
            atmAerosolBackgroundDensity(species) * falloff,
            smoothstep(1.0, 2.0, altitude));
}

public AtmCoefficients atmCoefficients(int group, float altitudeKm) {
    float altitude = max(altitudeKm, 0.0);
    float4 aerosolScattering = float4(0.0);
    float4 aerosolAbsorption = float4(0.0);
    for (int species = 0; species < 4; ++species) {
        float density = atmAerosolDensity(species, altitude);
        aerosolScattering += atmAerosolScatteringCrossSection(group, species) * density;
        aerosolAbsorption += atmAerosolAbsorptionCrossSection(group, species) * density;
    }
    float4 molecularScattering = atmMolecularScattering(group)
            * exp(-0.07771971 * pow(altitude, 1.16364243));
    float ozoneHeight = altitude + 1.0e-4;
    float ozoneT = log(ozoneHeight) - 3.22261;
    float ozoneDensity = 3.78547397e20 / ozoneHeight * exp(-ozoneT * ozoneT * 5.55555555);
    float4 molecularAbsorption =
            atmOzoneCrossSection(group) * 381.0 * ozoneDensity;
    AtmCoefficients result;
    result.aerosolScattering = aerosolScattering;
    result.molecularScattering = molecularScattering;
    result.extinction = aerosolScattering + aerosolAbsorption
            + molecularScattering + molecularAbsorption;
    return result;
}

public float4 atmSolarSpectrum(int group) {
    float4 reference = group == 0
            ? float4(1.7477345, 1.7629014, 2.0566432, 1.8146111)
            : float4(1.8847016, 1.8567868, 1.7294269, 1.5365043);
    // The fitted spectra originally reconstruct a 205-unit extraterrestrial sun. Scaling the
    // spectra here keeps sky radiance and Prime's explicit 12.5-unit sun on one energy scale.
    return reference * (ATM_SPACE_SUN_INTENSITY / ATM_REFERENCE_SUN_INTENSITY);
}


public float3 atmLinearRec2020FromSpectral(float4 low, float4 high) {
    float3x4 lowMatrix = float3x4(
            float4(2.015521915, 3.069387571, -5.484677337, -3.419542345),
            float4(-2.20749785, -3.495544704, 10.446526205, 23.113484232),
            float4(18.203864142, 34.463233423, 45.783739329, 0.928681094));
    float3x4 highMatrix = float3x4(
            float4(4.243623958, 55.000002375, 47.02149692, 15.768316051),
            float4(37.153565117, 34.63775886, 4.411417716, -0.743849551),
            float4(-0.456028883, -0.84876689, -0.106950912, 0.019681577));
    float3 rgb = mul(lowMatrix, low) + mul(highMatrix, high);
    float3x3 whiteBalance = float3x3(
            float3(0.973450179, -0.0199533690, 0.000904216012),
            float3(-0.00110537346, 1.01471724, -0.000617879953),
            float3(0.000549268697, -0.000413338668, 1.06404848));
    return mul(whiteBalance, rgb);
}

public float3 atmRec2020Transmittance(float4 low, float4 high) {
    float4 clearLow = atmSolarSpectrum(0);
    float4 clearHigh = atmSolarSpectrum(1);
    float3 clear = max(atmLinearRec2020FromSpectral(clearLow, clearHigh), float3(1.0e-6));
    float3 attenuated = max(atmLinearRec2020FromSpectral(clearLow * low, clearHigh * high), float3(0.0));
    return clamp(attenuated / clear, float3(0.0), float3(1.0));
}

