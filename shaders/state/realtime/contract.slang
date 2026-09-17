// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

#language slang 2026
module "state/realtime/contract.slang";

__exported import "contract/realtime/path.slang";
__exported import "math/realtime_control.slang";
import "prime_realtime_abi.slang";
import "prime_abi.slang";
import "math/realtime_storage.slang";

[[vk::binding(PRIME_DESCRIPTOR_WAVEFRONT_PATHS, PRIME_RENDERER_DESCRIPTOR_SET)]]
public RWStructuredBuffer<PrimeWavefrontPathRecord> primeWavefrontPaths;

[[vk::binding(PRIME_DESCRIPTOR_WAVEFRONT_QUEUE, PRIME_RENDERER_DESCRIPTOR_SET)]]
public RWStructuredBuffer<uint> primeWavefrontQueue;

public uint primeWavefrontIndex(uint2 pixel) {
    return pixel.y * primePush.outputExtent.x + pixel.x;
}

public uint primeWavefrontPixelCount() {
    return primePush.outputExtent.x * primePush.outputExtent.y;
}

public uint primeWavefrontTransportIndex(uint pathIndex) {
    return primeRealtimeTransportIndex(pathIndex, primeWavefrontPixelCount());
}

// Logical indices remain branch-major even when the sole transport record belongs to reflection.
public bool primeWavefrontMissingGuideOwner(uint pathIndex) {
    uint control = asuint(primeWavefrontPaths[primeWavefrontTransportIndex(pathIndex)]
            .transport.rayDirectionAndDenoiserControl.w);
    return primeWavefrontTransparentBranch(control)
            && ((pathIndex < primeWavefrontPixelCount()) != primeWavefrontTransmissionBranch(control));
}

public uint primeWavefrontBranchControl(uint pathIndex) {
    if (primeWavefrontMissingGuideOwner(pathIndex)) {
        return primeWavefrontQueue[primeMissingGuideOwnerWord(pathIndex, primeWavefrontPixelCount()) + 1u];
    }
    return asuint(primeWavefrontPaths[primeWavefrontTransportIndex(pathIndex)]
            .transport.rayDirectionAndDenoiserControl.w);
}

public uint primeWavefrontQueueCapacity(uint queue) {
    return primeRealtimeQueueCapacity(primeWavefrontPixelCount(), queue);
}

public uint primeWavefrontIndex(uint2 pixel, uint branch) {
    return primeWavefrontIndex(pixel) + branch * primeWavefrontPixelCount();
}

public uint2 primeWavefrontPixel(uint pathIndex) {
    uint pixelIndex = pathIndex % primeWavefrontPixelCount();
    return uint2(
            pixelIndex % primePush.outputExtent.x,
            pixelIndex / primePush.outputExtent.x);
}

public uint primeWavefrontQueueCommandWord(uint queue) {
    return primeRealtimeQueueCommandWord(
            primeWavefrontPixelCount(), queue);
}

public uint primeWavefrontQueueWord(uint queue, uint entry) {
    return primeRealtimeQueueWord(
            primeWavefrontPixelCount(), queue, entry);
}

public uint primeAdditionalSpecularBounces() {
    return primeWavefrontQueue[
            primeWavefrontQueueCommandWord(PRIME_WAVEFRONT_TRACE_QUEUE_0) + 3u]
            & 0xffu;
}

public uint primeMinimumRealtimeBounces() {
    return (primeWavefrontQueue[primeWavefrontQueueCommandWord(PRIME_WAVEFRONT_TRACE_QUEUE_0) + 3u]
            >> PRIME_WAVEFRONT_MINIMUM_BOUNCE_SHIFT) & PRIME_WAVEFRONT_MINIMUM_BOUNCE_MASK;
}

public bool primeWavefrontPixelTransparent(uint2 pixel) {
    uint control = asuint(
            primeWavefrontPaths[primeWavefrontTransportIndex(primeWavefrontIndex(pixel, 0u))]
                    .transport.rayDirectionAndDenoiserControl.w);
    return primeWavefrontTransparentBranch(control);
}
