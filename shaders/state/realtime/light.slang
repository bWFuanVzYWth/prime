#language slang 2026
module "state/realtime/light.slang";

import "state/realtime/contract.slang";

// Member-wise access keeps cold PSR state out of ordinary continuations and prevents a storage
// record aggregate from remaining live across the integrator call tree.
public PrimeWavefrontTransportRecord primeLoadWavefrontTransportRecord(uint pathIndex) {
    PrimeWavefrontTransportRecord record;
    record.physicalOriginAndPreviousBsdfPdf =
            primeWavefrontPaths[pathIndex]
                    .transport.physicalOriginAndPreviousBsdfPdf;
    record.sourceAndPathControl =
            primeWavefrontPaths[pathIndex].transport.sourceAndPathControl;
    record.rayDirectionAndDenoiserControl =
            primeWavefrontPaths[pathIndex]
                    .transport.rayDirectionAndDenoiserControl;
    record.throughputAndReserved =
            primeWavefrontPaths[pathIndex].transport.throughputAndReserved;
    record.medium0 = primeWavefrontPaths[pathIndex].transport.medium0;
    record.medium1 = primeWavefrontPaths[pathIndex].transport.medium1;
    return record;
}

public void primeStoreWavefrontTransportRecord(
        uint pathIndex,
        PrimeWavefrontTransportRecord record) {
    primeWavefrontPaths[pathIndex]
            .transport.physicalOriginAndPreviousBsdfPdf =
            record.physicalOriginAndPreviousBsdfPdf;
    primeWavefrontPaths[pathIndex].transport.sourceAndPathControl =
            record.sourceAndPathControl;
    primeWavefrontPaths[pathIndex]
            .transport.rayDirectionAndDenoiserControl =
            record.rayDirectionAndDenoiserControl;
    primeWavefrontPaths[pathIndex].transport.throughputAndReserved =
            record.throughputAndReserved;
    primeWavefrontPaths[pathIndex].transport.medium0 = record.medium0;
    primeWavefrontPaths[pathIndex].transport.medium1 = record.medium1;
}
