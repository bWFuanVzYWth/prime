// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

#language slang 2026
module "service/bsdf/support/interface.slang";

import "model/material/defaults.slang";
import "model/material/translation.slang";
import "model/material/medium_boundary.slang";
import "bsdf/compact/math/spectrum.slang";

public float primeMinecraftInterfaceIor(uint materialControl, uint opticalControl) {
    PrimeOpticalMaterial translated = primeDecodeOpticalMaterial(
            0.0, opticalControl, materialControl);
    return primeMaterialIsWater(materialControl)
            ? 1.333
            : primeOpenPbrF0ToIor(translated.dielectricF0);
}

public bool primeHasAdjacentMedium(uint adjacentInterfaceControl) {
    return (adjacentInterfaceControl & PRIME_ADJACENT_INTERFACE_VALID) != 0u;
}

public bool primeUsesAdjacentAirGap(
        bool usesAirGap,
        uint materialControl,
        uint opticalControl,
        uint adjacentInterfaceControl) {
    return (adjacentInterfaceControl & PRIME_ADJACENT_MICRO_GAP_ELIGIBLE) != 0u
            && primeAirGapCompatible(
                    usesAirGap,
                    materialControl,
                    opticalControl & PRIME_OPTICAL_FRESNEL_MASK,
                    primeAdjacentMaterialControl(adjacentInterfaceControl),
                    primeAdjacentFresnelCode(adjacentInterfaceControl));
}
