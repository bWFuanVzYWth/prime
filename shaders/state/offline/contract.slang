#language slang 2026
module "state/offline/contract.slang";

import "prime_offline_abi.slang";
import "prime_abi.slang";
import "prime_abi_types.slang";

// 96 bytes of transport and media plus 32 bytes of beauty metadata. Surface and selected-light
// scratch live in the queue prefix so the four narrow stages do not inflate persistent paths.
public struct PrimeOfflinePathRecord {
    public PrimeOfflineTransportRecord transport;
    public float4 radianceAndPrimaryDistance;
    public float4 primaryAlbedoAndMaterialFlags;
};

[[vk::binding(PRIME_DESCRIPTOR_WAVEFRONT_PATHS, PRIME_RENDERER_DESCRIPTOR_SET)]]
public RWStructuredBuffer<PrimeOfflinePathRecord> primeOfflinePaths;

[[vk::binding(PRIME_DESCRIPTOR_WAVEFRONT_QUEUE, PRIME_RENDERER_DESCRIPTOR_SET)]]
public RWStructuredBuffer<uint> primeOfflineQueue;

public static const uint PRIME_OFFLINE_PATH_PREVIOUS_AREA_NEE = 2u;
