#language slang 2026
module "policy/realtime/secondary.slang";

import "transport/scatter/phase_contract.slang";

public struct PrimeSecondaryTransportPhase : IPrimeRealtimeTransportPhase {
    uint transparentFirstHitBounce() { return 0u; }
    bool transparentPreviousUsedAreaNee(bool enabled, uint bounce) { return enabled; }
    uint ordinaryGuideHitBounce(uint primaryBounce) { return 0u; }
}
