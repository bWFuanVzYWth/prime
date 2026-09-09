// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

#language slang 2026
module "service/trace/context.slang";

// Explicit operation context keeps side-effect ownership visible without carrying diagnostic
// state in production shaders.
public struct PrimeIntegratorContext {};

public PrimeIntegratorContext primeIntegratorContextCreate() {
    return {};
}
