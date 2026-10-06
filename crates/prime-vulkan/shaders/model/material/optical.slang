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
    // CPU canonical B contains only the supported SSS strength, not raw porosity/category bytes.
    result.opticalControl = bytes.y | bytes.z << PRIME_OPTICAL_SUBSURFACE_SHIFT;
    return result;
}
