// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

#language slang 2026
module "policy/realtime/landing.slang";

import "transport/scatter/phase_contract.slang";

public struct PrimeLandingTransportPhase : IPrimeRealtimeTransportPhase {
    uint transparentFirstHitBounce() { return 1u; }
    uint ordinaryGuideHitBounce(uint primaryBounce) { return primaryBounce + 1u; }
}
