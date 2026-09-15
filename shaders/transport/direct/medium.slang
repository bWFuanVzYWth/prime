// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

#language slang 2026
module "transport/direct/medium.slang";

import "prime_abi_types.slang";
import "model/material/defaults.slang";
import "model/material/medium_boundary.slang";
import "service/bsdf/support/volume_transition.slang";
import "service/bsdf/support/boundary_volume.slang";
import "bsdf/compact/contract/volume.slang";

// Source geometry is excluded from the shadow query, so its physical transition belongs here.
// The authored geometric normal selects the endpoint even when a normal map tilts the closure.
public PrimeOpenPbrVolume primeShadowStartingMedium(
        SurfaceInteraction surface, float3 viewDirection, float3 direction,
        PrimeOpenPbrVolume incident) {
    bool boundary = (surface.adjacentInterfaceControl & PRIME_ADJACENT_INTERFACE_VALID) != 0u;
    if (!primeMaterialIsTransmissive(surface.materialControl)
            || (!boundary && (surface.materialControl & PRIME_MATERIAL_THIN_WALLED) != 0u)
            || dot(surface.geometricNormal, viewDirection)
                    * dot(surface.geometricNormal, direction) >= 0.0) {
        return incident;
    }
    if (dot(surface.geometricNormal, direction) < 0.0) {
        return primeBoundarySurfaceVolume(surface.baseColor, surface.opacity,
                surface.materialControl, surface.opticalControl, surface.mediumId);
    }
    return boundary
            ? primeAdjacentMediumVolume(surface.adjacentBaseColor,
                    surface.adjacentInterfaceControl, surface.adjacentMediumId)
            : primeVacuumMedium();
}
