// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

#language slang 2026
module "policy/realtime/secondary.slang";

import "transport/scatter/phase_contract.slang";

public struct PrimeSecondaryTransportPhase : IPrimeRealtimeTransportPhase {
    uint transparentFirstHitBounce() { return 0u; }
    uint ordinaryGuideHitBounce(uint primaryBounce) { return 0u; }
}
