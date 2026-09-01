#language slang 2026
module "transport/offline/trace.slang";

import "prime_abi.slang";
import "prime_offline_abi.slang";
import "prime_abi_types.slang";
import "state/path.slang";
import "service/camera/projection.slang";
import "service/trace/surface.slang";
import "state/offline/contract.slang";
import "state/offline/path_storage.slang";
import "state/offline/index.slang";
import "state/offline/queue.slang";
import "state/offline/surface.slang";
import "transport/offline/context.slang";
import "bsdf/compact/contract/volume.slang";
import "contract/path.slang";

public void primeTraceOfflineCamera(uint2 pixel) {
    uint pathIndex = primeOfflineIndex(pixel);
    float2 cameraSample = primeOfflineCameraSample(pixel);
    float3 traceDirection = primeCameraRayDirection(pixel, cameraSample);
    SurfaceInteraction surface = primeTraceSurface(
            primePush.cameraPosition, traceDirection);
    primeStoreOfflineSurface(pathIndex, surface);

    // Build persistent state after traversal so SER does not spill the path and medium records.
    PathState path = primeCameraPath(pixel, 0u, cameraSample);
    primeOfflinePaths[pathIndex] = primeMakeOfflineRecord(
            path,
            primeOfflineInitialVolumeStack(),
            float3(0.0),
            -1.0,
            float3(1.0),
            0u,
            true);
    // SER may repack invocations; publication after traversal remains scalar and identity-exact.
    primeAppendOfflinePath(0u, pathIndex);
}

public void primeTraceOfflineBridge(uint sourceQueue, uint launchIndex) {
    uint pathIndex = primeOfflineQueuedPath(sourceQueue, launchIndex);
    PrimeOfflineTransportRecord transport = primeOfflinePaths[pathIndex].transport;
    SurfaceInteraction surface = primeTraceSurface(
            primeOfflinePhysicalOrigin(transport),
            primeOfflineRayDirection(transport),
            primeOfflineSourcePrimitive(transport));
    primeStoreOfflineSurface(pathIndex, surface);
}
