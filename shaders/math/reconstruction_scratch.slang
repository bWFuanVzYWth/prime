// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

#language slang 2026
module "math/reconstruction_scratch.slang";

import "prime_realtime_abi.slang";

// Four area-light words and four mutually exclusive interface/sun words belong to each pixel.
public uint primeReconstructionGuideWord(uint pixelIndex, uint component) {
    return pixelIndex * (PRIME_WAVEFRONT_AREA_GUIDE_RECORD_SIZE / 4u) + component;
}
