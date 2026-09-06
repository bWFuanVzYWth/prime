#language slang 2026
module "policy/realtime/hit_distance.slang";
import "transport/scatter/phase_contract.slang";

public static const uint PRIME_SCATTER_FIRST_HIT = 1u;
public static const uint PRIME_SCATTER_GUIDE_HIT = 2u;

// A transparent branch observes geometry before absorption; ordinary transport observes it after.
public uint primeScatterHitDistanceUpdates<Phase>(Phase phase, bool transparent, bool segmentAlive,
        uint bounce, uint guideBounce) where Phase : IPrimeRealtimeTransportPhase {
    if (!transparent && !segmentAlive) return 0u;
    uint updates = transparent && bounce == phase.transparentFirstHitBounce() ? PRIME_SCATTER_FIRST_HIT : 0u;
    uint guideHitBounce = transparent ? guideBounce + 1u : phase.ordinaryGuideHitBounce(guideBounce);
    return updates | (bounce == guideHitBounce ? PRIME_SCATTER_GUIDE_HIT : 0u);
}
