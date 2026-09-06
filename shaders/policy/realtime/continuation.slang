#language slang 2026
module "policy/realtime/continuation.slang";
import "prime_abi_types.slang";
import "policy/transport/path.slang";
import "math/transport.slang";

public uint primeRealtimeBounceLimit(uint minimum, uint maximum) { return max(minimum, maximum); }
public bool primeRealtimeCanContinue(uint secondaryBounce, uint minimum, uint maximum) {
    return primeRealtimeCompletedBounceCount(secondaryBounce) < primeRealtimeBounceLimit(minimum, maximum);
}
public bool primeRealtimeNeedsRoulette(uint secondaryBounce, uint minimum, uint maximum) {
    // Landing resets the local counter to zero. Preserve its first secondary guide query before RR.
    return primeRealtimeCanContinue(secondaryBounce, minimum, maximum)
            && primeRealtimeCompletedBounceCount(secondaryBounce) >= minimum
            && primeRussianRouletteApplies(secondaryBounce, PRIME_RUSSIAN_ROULETTE_START);
}
