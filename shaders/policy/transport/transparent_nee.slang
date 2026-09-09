// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

#language slang 2026
module "policy/transport/transparent_nee.slang";

public struct PrimeTransparentNeeInterfaceDecision
{
    public bool blocks;
    public float terminalDistance;
};

public PrimeTransparentNeeInterfaceDecision primeTransparentNeeInterfaceDecision(
        bool unbiasedBsdfOnly,
        float terminalDistance,
        float interfaceDistance)
{
    PrimeTransparentNeeInterfaceDecision decision;
    decision.blocks = unbiasedBsdfOnly;
    decision.terminalDistance = unbiasedBsdfOnly
            ? min(terminalDistance, interfaceDistance)
            : terminalDistance;
    return decision;
}
