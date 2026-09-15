// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

#language slang 2026
module "model/material/optical.slang";

import "model/material/defaults.slang";

public struct PrimeCanonicalOptics {
    public float roughness;
    public uint opticalControl;
};

public PrimeCanonicalOptics primeDecodeOpticalSample(float4 sampleValue) {
    uint3 bytes = uint3(round(sampleValue.rgb * 255.0));
    PrimeCanonicalOptics result;
    // Preserve filter/quantization order. Contracted division/subtraction can make byte 255
    // slightly negative, so the encoding boundary must retain the exact zero endpoint.
    result.roughness = clamp(1.0 - float(bytes.x) / 255.0, 0.0, 1.0);
    uint subsurface = bytes.z > 64u ? bytes.z - 64u : 0u;
    uint porosity = bytes.z <= 64u ? bytes.z : 0u;
    result.opticalControl = bytes.y | subsurface << PRIME_OPTICAL_SUBSURFACE_SHIFT
            | porosity << PRIME_OPTICAL_POROSITY_SHIFT;
    return result;
}

public uint primeSurfaceOpticalControl(uint opticalControl, uint materialControl) {
    // Texture identity is shared by material roles. Only the final surface knows its topology.
    return (materialControl & PRIME_MATERIAL_THIN_WALLED) != 0u
            ? opticalControl : opticalControl & ~(0xffu << PRIME_OPTICAL_SUBSURFACE_SHIFT);
}
