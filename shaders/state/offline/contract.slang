#language slang 2026
module "state/offline/contract.slang";

import "prime_offline_abi.slang";
import "prime_abi.slang";
import "prime_abi_types.slang";

public struct PrimeOfflineTransportRecord {
    public float4 physicalOriginAndPreviousBsdfPdf;
    public uint4 sourceAndPathControl;
    public float4 rayDirectionAndControl;
    public float4 throughput;
    public uint4 medium0;
    public uint4 medium1;
    public float4 previousLightNormal;
};

// 112 bytes of transport and media plus 32 bytes of beauty metadata. Surface and selected-light
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
public static const uint PRIME_OFFLINE_CONTROL_ACTIVE = PRIME_WAVEFRONT_ACTIVE_MASK;
public static const uint PRIME_OFFLINE_CONTROL_MEDIUM_SHIFT = 1u;
public static const uint PRIME_OFFLINE_CONTROL_MEDIUM_MASK = 0x3u;
public static const uint PRIME_OFFLINE_PATH_BOUNCE_SHIFT = 0u;
public static const uint PRIME_OFFLINE_PATH_FLAGS_SHIFT = 16u;
public static const uint PRIME_OFFLINE_PATH_BYTE_MASK = 0xffu;
public static const uint PRIME_OFFLINE_PATH_FLAGS_MASK = 0x3u;
