// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.

#language slang 2026
module "state/mis.slang";

import "prime_abi_types.slang";
import "state/path.slang";
import "contract/path.slang";

// Phase-local bounce counters may reset after NEE. Only camera origin and discrete events
// lack a competing solid-angle BSDF proposal; exact source identity survives those resets.
public bool primePreviousCannotUseMis(uint2 sourcePrimitive, uint pathFlags) {
    return all(sourcePrimitive == uint2(PRIME_NO_PRIMITIVE_INDEX))
            || (pathFlags & PRIME_PATH_PREVIOUS_DISCRETE) != 0u;
}

public bool primePreviousCannotUseMis(PathState path) {
    return primePreviousCannotUseMis(path.sourcePrimitive, primePathFlags(path.control));
}

