// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

#language slang 2026
module "service/bsdf/support/medium_conversion.slang";

import "contract/medium.slang";
import "bsdf/compact/contract/volume.slang";

public PrimeOpenPbrVolume primeOpenPbrVolumeFromMedium(PrimeMedium medium) {
    PrimeOpenPbrVolume volume = {};
    volume.extinction = medium.extinction;
    volume.ior = medium.ior;
    volume.mediumId = medium.mediumId;
    volume.iorEncoding = medium.iorEncoding;
    return volume;
}

// Production material construction fixes scatter and anisotropy to zero before this boundary.
public PrimeMedium primeMediumFromOpenPbrVolume(PrimeOpenPbrVolume volume) {
    PrimeMedium medium;
    medium.extinction = volume.extinction;
    medium.ior = volume.ior;
    medium.mediumId = volume.mediumId;
    medium.iorEncoding = volume.iorEncoding;
    return medium;
}
