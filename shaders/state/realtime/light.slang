// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

#language slang 2026
module "state/realtime/light.slang";

import "state/realtime/contract.slang";

// Member-wise access keeps cold PSR state out of ordinary continuations and prevents a storage
// record aggregate from remaining live across the integrator call tree.
public PrimeWavefrontTransportRecord primeLoadWavefrontTransportRecord(uint pathIndex) {
    PrimeWavefrontTransportRecord record;
    record.physicalOriginAndPreviousBsdfPdf =
            primeWavefrontPaths[primeWavefrontTransportIndex(pathIndex)]
                    .transport.physicalOriginAndPreviousBsdfPdf;
    record.sourceAndPathControl =
            primeWavefrontPaths[primeWavefrontTransportIndex(pathIndex)].transport.sourceAndPathControl;
    record.rayDirectionAndDenoiserControl =
            primeWavefrontPaths[primeWavefrontTransportIndex(pathIndex)]
                    .transport.rayDirectionAndDenoiserControl;
    record.throughputAndReserved =
            primeWavefrontPaths[primeWavefrontTransportIndex(pathIndex)].transport.throughputAndReserved;
    record.medium = primeWavefrontPaths[primeWavefrontTransportIndex(pathIndex)].transport.medium;
    return record;
}

public void primeStoreWavefrontTransportRecord(
        uint pathIndex,
        PrimeWavefrontTransportRecord record) {
    primeWavefrontPaths[primeWavefrontTransportIndex(pathIndex)]
            .transport.physicalOriginAndPreviousBsdfPdf =
            record.physicalOriginAndPreviousBsdfPdf;
    primeWavefrontPaths[primeWavefrontTransportIndex(pathIndex)].transport.sourceAndPathControl =
            record.sourceAndPathControl;
    primeWavefrontPaths[primeWavefrontTransportIndex(pathIndex)]
            .transport.rayDirectionAndDenoiserControl =
            record.rayDirectionAndDenoiserControl;
    primeWavefrontPaths[primeWavefrontTransportIndex(pathIndex)].transport.throughputAndReserved =
            record.throughputAndReserved;
    primeWavefrontPaths[primeWavefrontTransportIndex(pathIndex)].transport.medium = record.medium;
}
