// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

#language slang 2026
module "state/realtime/secondary_transition.slang";

import "prime_abi_types.slang";
import "state/path.slang";
import "contract/path.slang";
import "state/realtime/contract.slang";
import "state/realtime/transport.slang";

// Secondary transport owns a fresh local bounce sequence.
public void primeEnterSecondaryAfterNee(inout PathState path) {
    path.control = primePathWithBounce(path.control, 0u);
}

public PrimeWavefrontTransportRecord primeEnterSecondaryRecord(
        PrimeWavefrontTransportRecord record) {
    record.sourceAndPathControl.w =
            record.sourceAndPathControl.w & ~PRIME_WAVEFRONT_BYTE_MASK;
    uint control = asuint(record.rayDirectionAndDenoiserControl.w);
    control &= ~(PRIME_WAVEFRONT_BYTE_MASK
            << PRIME_WAVEFRONT_PRIMARY_BOUNCE_SHIFT);
    record.rayDirectionAndDenoiserControl.w = asfloat(control);
    record = primeWavefrontWithQueuedPsrControl(record, 0u);
    return primeWavefrontWithGuideFallback(record, false);
}
