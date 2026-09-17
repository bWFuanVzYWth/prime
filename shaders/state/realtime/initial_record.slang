// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

#language slang 2026
module "state/realtime/initial_record.slang";

import "prime_abi_types.slang";
import "state/path.slang";
import "state/realtime/contract.slang";
import "state/realtime/transport.slang";
import "math/queued_psr.slang";
import "contract/realtime/guide.slang";
import "model/realtime/transport.slang";
import "contract/medium.slang";

public uint4 primePackWavefrontPsrState(PrimeQueuedPsrState state) {
    return asuint(state.rotation);
}

public PrimeWavefrontPathRecord primeWavefrontWithQueuedPsrState(
        PrimeWavefrontPathRecord record,
        PrimeQueuedPsrState state) {
    record.transport = primeWavefrontWithQueuedPsrControl(
            record.transport, primeQueuedPsrStorageFlags(state));
    record.psrPacked = primePackWavefrontPsrState(state);
    record.transport.throughputAndReserved.w = state.firstDirectionLength.w;
    return record;
}

public PrimeWavefrontPathRecord primeWavefrontWithPrimaryPreviousPosition(
        PrimeWavefrontPathRecord record, PrimeDenoiserGuides guides) {
    record.psrPacked.xyz = asuint(guides.primaryHasMotion
            ? guides.primaryPreviousPosition
            : guides.primaryPosition);
    record.psrPacked.w = (record.psrPacked.w & ~PRIME_WAVEFRONT_PRIMARY_MOTION)
            | (guides.primaryHasMotion ? PRIME_WAVEFRONT_PRIMARY_MOTION : 0u);
    return record;
}

public PrimeWavefrontPathRecord primeMakeWavefrontRecord(
        PathState path,
        PrimeMedium medium,
        PrimeDenoiserState denoiserState,
        PrimeDenoiserGuides guides,
        bool enabled) {
    PrimeWavefrontPathRecord record;
    record.transport = primeMakeWavefrontTransportRecord(
            path,
            medium,
            denoiserState,
            guides.primaryMaterialFlags,
            enabled);
    record = primeWavefrontWithQueuedPsrState(
            record, primeEmptyQueuedPsrState());
    record = primeWavefrontWithPrimaryPreviousPosition(record, guides);
    return record;
}
