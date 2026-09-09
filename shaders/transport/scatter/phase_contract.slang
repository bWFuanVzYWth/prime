// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

#language slang 2026
module "transport/scatter/phase_contract.slang";

// Phase policy is compile-time glue. It makes bounce-domain differences explicit without
// allowing transport to import a scheduler or exposing macro-controlled alternate algorithms.
public interface IPrimeRealtimeTransportPhase {
    uint transparentFirstHitBounce();
    bool transparentPreviousUsedAreaNee(bool enabled, uint bounce);
    uint ordinaryGuideHitBounce(uint primaryBounce);
}
