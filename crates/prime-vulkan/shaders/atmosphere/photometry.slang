// Four-wavelength fit from Sky Tracer b66b163 (GPL-3.0-only).
#language slang 2026
module "atmosphere/photometry.slang";
import "atmosphere/parameters.slang";

// User-selected rendering baseline, shared by the disk and every scattering order.
public static const float ATM_SOLAR_REFERENCE_IRRADIANCE_Y = 8.0;

// CIE-weighted radiative tristimulus values in linear Rec.2020, with the source's
// solar-white-to-D65 adaptation. Photopic units are 683 * working luminance.
public float3 atmLinearRec2020FromSpectral(float4 value) {
    return value[0] * float3(5.8809709548950195, -7.0044331550598145, 90.48178100585938) +
           value[1] * float3(-14.151336669921875, 54.43069839477539, 8.974457740783691) +
           value[2] * float3(87.37126159667969, 58.01542282104492, -1.4691294431686401) +
           value[3] * float3(31.710432052612305, -1.2180235385894775, 0.05091293156147003);
}

// Fixed-source expression folds to constants; no spectral integration at a path vertex.
public float atmSolarSourceScale() {
    float3 source = atmLinearRec2020FromSpectral(ATM_SOLAR);
    float luminance = dot(float3(0.262700212, 0.677998072, 0.059301716), source);
    return ATM_SOLAR_REFERENCE_IRRADIANCE_Y / luminance;
}

public float3 atmSolarIrradiance() {
    return atmLinearRec2020FromSpectral(ATM_SOLAR) * atmSolarSourceScale();
}
