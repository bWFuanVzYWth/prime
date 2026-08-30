#language slang 2026
module "state/path.slang";

import "contract/path.slang";

// Bounce and flags share one exact control word. Geometric state remains full-precision fields.
public uint primeMakePathStateControl(uint bounce, uint flags) {
    return (bounce & PRIME_PATH_STATE_BOUNCE_MASK)
            | ((flags & PRIME_PATH_STATE_FLAGS_MASK)
                    << PRIME_PATH_STATE_FLAGS_SHIFT);
}

public uint primePathBounce(uint control) {
    return control & PRIME_PATH_STATE_BOUNCE_MASK;
}

public uint primePathWithBounce(uint control, uint bounce) {
    return (control & ~PRIME_PATH_STATE_BOUNCE_MASK)
            | (bounce & PRIME_PATH_STATE_BOUNCE_MASK);
}

public uint primeIncrementPathBounce(uint control) {
    return primePathWithBounce(control, primePathBounce(control) + 1u);
}

public uint primePathFlags(uint control) {
    return (control >> PRIME_PATH_STATE_FLAGS_SHIFT)
            & PRIME_PATH_STATE_FLAGS_MASK;
}

public uint primePathWithFlags(uint control, uint flags) {
    return (control
                    & ~(PRIME_PATH_STATE_FLAGS_MASK
                            << PRIME_PATH_STATE_FLAGS_SHIFT))
            | ((flags & PRIME_PATH_STATE_FLAGS_MASK)
                    << PRIME_PATH_STATE_FLAGS_SHIFT);
}

public uint primePathWithAddedFlags(uint control, uint flags) {
    return primePathWithFlags(control, primePathFlags(control) | flags);
}
