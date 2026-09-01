#language slang 2026
module "state/offline/transport.slang";

import "prime_abi_types.slang";
import "prime_offline_abi.slang";
import "state/path.slang";
import "policy/runtime/config.slang";
import "state/offline/contract.slang";
import "state/offline/path_storage.slang";
import "bsdf/compact/contract/volume.slang";
import "math/sampling.slang";
import "contract/path.slang";

public PrimeOfflineTransportRecord primeMakeOfflineTransport(
        PathState path,
        PrimeOpenPbrVolumeStack volumeStack,
        bool enabled) {
    return primePackOfflineTransport(path, volumeStack, enabled);
}

public PathState primeOfflinePath(
        uint2 pixel,
        PrimeOfflineTransportRecord record) {
    PathState path;
    primeUnpackOfflineTransportPath(record, path);
    path.sampleSeed = primePathSampleSeed(pixel, primeSampleEpoch(), 0u);
    return path;
}

public PrimeOpenPbrVolumeStack primeOfflineVolumeStack(
        PrimeOfflineTransportRecord record) {
    return primeUnpackOfflineTransportVolumeStack(record);
}

public bool primeOfflineIsActive(PrimeOfflinePathRecord record) {
    return primeOfflineTransportIsActive(record.transport);
}
