// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

#language slang 2026
module "state/offline/index.slang";

import "prime_abi.slang";

public uint primeOfflinePixelCount() {
    return primePush.outputExtent.x * primePush.outputExtent.y;
}

public uint primeOfflineIndex(uint2 pixel) {
    return pixel.y * primePush.outputExtent.x + pixel.x;
}

public uint2 primeOfflinePixel(uint pathIndex) {
    return uint2(
            pathIndex % primePush.outputExtent.x,
            pathIndex / primePush.outputExtent.x);
}
