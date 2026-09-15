// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

#language slang 2026
module "model/material/fresnel_code.slang";

import "model/material/defaults.slang";

public float primeDecodeDielectricF0(uint code)
{
    // This is the physical codebook, not source validation. Distinct identities can share F0.
    // Arithmetic avoids a dynamically indexed 256-float table in every material consumer.
    return code == PRIME_FRESNEL_DEFAULT_DIELECTRIC || code >= PRIME_FRESNEL_IRON
            ? PRIME_DEFAULT_DIELECTRIC_F0
            : clamp(float(code - 1u) / 255.0,
                    PRIME_COMMON_DIELECTRIC_F0_MINIMUM,
                    PRIME_COMMON_DIELECTRIC_F0_MAXIMUM);
}
