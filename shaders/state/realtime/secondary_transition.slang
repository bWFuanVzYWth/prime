// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

#language slang 2026
module "state/realtime/secondary_transition.slang";

import "prime_abi_types.slang";
import "state/path.slang";
import "contract/path.slang";
import "state/realtime/contract.slang";

// Secondary transport owns a fresh local bounce sequence.
public void primeEnterSecondaryAfterNee(inout PathState path) {
    path.control = primePathWithBounce(path.control, 0u);
}

public PrimeWavefrontTransportRecord primeEnterSecondaryRecord(
        PrimeWavefrontTransportRecord record) {
    PrimeWavefrontContinuationControl control;
    control.guide = record.sourceAndPathControl.z;
    control.path = record.sourceAndPathControl.w;
    control.denoiser = asuint(record.rayDirectionAndDenoiserControl.w);
    control = primeEnterSecondaryControl(control);
    record.sourceAndPathControl.z = control.guide;
    record.sourceAndPathControl.w = control.path;
    record.rayDirectionAndDenoiserControl.w = asfloat(control.denoiser);
    return record;
}
