#language slang 2026
module "entry/offline/sample_resolve.raygeneration.slang";

import "service/camera/projection.slang";
import "service/reconstruct/offline.slang";
import "state/offline/contract.slang";
import "state/offline/index.slang";

[shader("raygeneration")]
void main() {
    uint2 pixel = DispatchRaysIndex().xy;
    primeWriteOfflineOutput(
            pixel,
            primeOfflineCameraSample(pixel),
            primeOfflinePaths[primeOfflineIndex(pixel)]);
}
